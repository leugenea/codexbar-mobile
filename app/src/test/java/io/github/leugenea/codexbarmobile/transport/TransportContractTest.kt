package io.github.leugenea.codexbarmobile.transport

import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

class TransportContractTest {
    private val clock = VirtualTransportClock()
    private val fake = FakeProviderTransport(clock)
    private val request = ReadRequest(ReadOperation.USAGE, ReadDeadline.after(clock.now()))
    private val outcomes = mutableListOf<TransportResult>()

    @Test fun cancellationClosesFakeAndSuppressesAllLateResultsExactlyOnce() {
        val handle = fake.read(request, outcomes::add)
        handle.cancel()
        handle.cancel()
        val pending = fake.pending.single()
        assertTrue(pending.closed)
        assertFalse(pending.complete(success()))
        assertFalse(pending.complete(TransportResult.Failure(TransportFailure.NETWORK)))
        fake.tick()
        assertEquals(listOf(TransportResult.Failure(TransportFailure.CANCELLED)), outcomes)
        assertEquals(request, pending.request)
    }

    @Test fun responseWinsThenCancellationAndDuplicatesCannotPublish() {
        val handle = fake.read(request, outcomes::add)
        val response = success()
        assertTrue(fake.pending.single().complete(response))
        handle.cancel()
        assertFalse(fake.pending.single().complete(success()))
        assertTrue(fake.pending.single().closed)
        assertEquals(listOf(response), outcomes)
    }

    @Test fun deadlineTickTerminatesHungFakeWithoutWallTimeOrSleeps() {
        fake.read(request, outcomes::add)
        fake.tick()
        clock.wall = clock.wall.plusSeconds(3600)
        fake.tick()
        assertTrue(outcomes.isEmpty())
        clock.advance(30_000)
        fake.tick()
        fake.tick()
        assertTrue(fake.pending.single().closed)
        assertFalse(fake.pending.single().complete(success()))
        assertEquals(listOf(TransportResult.Failure(TransportFailure.DEADLINE_EXCEEDED)), outcomes)
    }

    @Test fun alreadyExpiredReadAndLateResponseProduceDeadlineOnly() {
        clock.advance(30_000)
        val handle = fake.read(request, outcomes::add)
        handle.cancel()
        assertEquals(listOf(TransportResult.Failure(TransportFailure.DEADLINE_EXCEEDED)), outcomes)
        outcomes.clear()
        val delivery = TerminalDelivery(request.deadline, clock, outcomes::add)
        assertTrue(delivery.complete(success()))
        assertEquals(listOf(TransportResult.Failure(TransportFailure.DEADLINE_EXCEEDED)), outcomes)
    }

    @Test fun completionAndCancellationRaceHasOneWinnerWithoutDeadlock() {
        val count = AtomicInteger()
        val start = CountDownLatch(1)
        val done = CountDownLatch(2)
        val delivery = TerminalDelivery(request.deadline, clock) { count.incrementAndGet() }
        val actions = listOf<() -> Unit>({ delivery.cancel() }, { delivery.complete(success()) })
        val workers = actions.map { action ->
            Thread { start.await(); action(); done.countDown() }.also { it.start() }
        }
        start.countDown()
        assertTrue(done.await(5, java.util.concurrent.TimeUnit.SECONDS))
        workers.forEach { it.join() }
        assertEquals(1, count.get())
        assertFalse(delivery.complete(success()))
    }

    @Test fun reentrantAndThrowingCallbacksStillConsumeSingleTerminalOwnership() {
        lateinit var delivery: TerminalDelivery
        delivery = TerminalDelivery(request.deadline, clock) { delivery.cancel(); outcomes.add(it) }
        assertTrue(delivery.complete(success()))
        assertEquals(1, outcomes.size)
        val throwing = TerminalDelivery(request.deadline, clock) { throw IllegalStateException("Synthetic callback failure") }
        assertThrows(IllegalStateException::class.java) { throwing.complete(success()) }
        assertFalse(throwing.complete(success()))
        throwing.cancel()
    }

    @Test fun independentFakeCallsDoNotCrossCancel() {
        val first = fake.read(request, outcomes::add)
        fake.read(request.copy(operation = ReadOperation.RESET_INVENTORY), outcomes::add)
        first.cancel()
        assertFalse(fake.pending[1].closed)
        assertTrue(fake.pending[1].complete(success()))
        assertEquals(2, outcomes.size)
    }

    @Test fun deadlineCallbackCanStartAnotherReadWithoutMutatingTheTickIteration() {
        fake.read(request) { result ->
            outcomes.add(result)
            val next = ReadRequest(ReadOperation.RESET_INVENTORY, ReadDeadline.after(clock.now()))
            fake.read(next, outcomes::add)
        }
        clock.advance(30_000)
        fake.tick()
        assertEquals(2, fake.pending.size)
        assertEquals(listOf(TransportResult.Failure(TransportFailure.DEADLINE_EXCEEDED)), outcomes)
        assertFalse(fake.pending[1].closed)
        assertTrue(fake.pending[1].complete(success()))
        assertEquals(2, outcomes.size)
    }

    @Test fun boundedBodyCopiesInputAndOutputAndRedactsDiagnostics() {
        val marker = "synthetic-sensitive-marker"
        val bytes = marker.toByteArray()
        val response = TransportResult.Response.bounded(200, bytes) as TransportResult.Response
        bytes.fill(0)
        assertEquals(marker, String(response.body.copyBytes()))
        val output = response.body.copyBytes()
        output.fill(0)
        assertEquals(marker, String(response.body.copyBytes()))
        assertEquals(marker.length, response.body.size)
        assertEquals(RetryAfter.Missing, response.retryAfter)
        assertEquals(200, response.status)
        assertFalse(response.toString().contains(marker))
        assertFalse(response.body.toString().contains(marker))
    }

    @Test fun responseBoundsRejectOversizeAndInvalidStatusCategorically() {
        val atLimit = TransportResult.Response.bounded(599, ByteArray(ResponseBody.MAX_BYTES))
        assertEquals(ResponseBody.MAX_BYTES, (atLimit as TransportResult.Response).body.size)
        val tooLarge = TransportResult.Response.bounded(200, ByteArray(ResponseBody.MAX_BYTES + 1))
        assertEquals(TransportResult.Failure(TransportFailure.BODY_TOO_LARGE), tooLarge)
        listOf(0, 99, 600, Int.MAX_VALUE).forEach { status ->
            assertEquals(TransportResult.Failure(TransportFailure.INVALID_RESPONSE), TransportResult.Response.bounded(status, byteArrayOf()))
        }
        assertTrue(TransportResult.Response.bounded(100, byteArrayOf()) is TransportResult.Response)
    }

    @Test fun errorShapesCannotCarrySecretsOrThrowableChains() {
        val marker = "synthetic-sensitive-marker"
        TransportFailure.entries.forEach { category ->
            val failure = TransportResult.Failure(category)
            assertFalse(failure.toString().contains(marker))
            assertFalse(Throwable::class.java.isAssignableFrom(failure.javaClass))
            val fields = failure.javaClass.declaredFields.filterNot { it.isSynthetic || java.lang.reflect.Modifier.isStatic(it.modifiers) }
            assertEquals(listOf(TransportFailure::class.java), fields.map { it.type })
        }
        ReadError.entries.forEach { assertFalse(it.toString().contains(marker)) }
    }

    private fun success(): TransportResult = TransportResult.Response.bounded(200, byteArrayOf())
}
