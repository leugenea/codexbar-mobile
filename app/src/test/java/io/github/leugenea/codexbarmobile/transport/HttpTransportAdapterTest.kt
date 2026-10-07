package io.github.leugenea.codexbarmobile.transport

import io.github.leugenea.codexbarmobile.credentials.SensitiveValue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Call
import okhttp3.Callback
import okhttp3.EventListener
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody as WireBody
import okio.Buffer
import okio.BufferedSource
import okio.ForwardingSource
import okio.Timeout
import okio.buffer
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class HttpTransportAdapterTest {
    private fun secret(text: String = "synthetic-only") = SensitiveValue.copyOf(text.toByteArray())
    private fun deadline(duration: Long = 30_000) = ReadDeadline.after(SystemTransportClock.now(), duration)
    private fun adapter(server: MockWebServer, timeout: Long = 30_000) = HttpTransportAdapter(
        { server.url(it.path) }, { secret() }, "0.1.0", readTimeoutMillis = timeout)

    @Test fun selectedReadsHaveCallerRoutesAndHonestHeaders() = MockWebServer().apply { start() }.use { server ->
        val transport = adapter(server)
        ReadOperation.entries.forEach { operation ->
            server.enqueue(MockResponse.Builder().body("{}").addHeader("Retry-After", "2").build())
            val result = Receipt()
            transport.read(ReadRequest(operation, deadline()), result::accept)
            val response = result.await() as TransportResult.Response
            assertEquals(200, response.status)
            assertTrue(response.retryAfter is RetryAfter.NotBefore)
            assertEquals("{}", response.body.copyBytes().toString(Charsets.UTF_8))
            val request = server.takeRequest(5, TimeUnit.SECONDS)!!
            assertEquals("GET", request.method)
            assertEquals(operation.path, request.target)
            assertEquals("Bearer synthetic-only", request.headers["Authorization"])
            assertEquals("codexbar-mobile/0.1.0", request.headers["User-Agent"])
            assertEquals("application/json", request.headers["Accept"])
            assertNull(request.headers["Content-Type"])
            assertEquals("Response(status=200, body=redacted)", response.toString())
        }
        assertEquals(30_000, transport.client.callTimeoutMillis)
        assertFalse(transport.client.followRedirects)
        assertFalse(transport.client.followSslRedirects)
        assertFalse(transport.client.retryOnConnectionFailure)
        assertTrue(transport.client.interceptors.isEmpty())
        assertTrue(transport.client.networkInterceptors.isEmpty())
    }

    @Test fun formAndJsonPostsDoNotInventRoutesOrAttachBearer() = MockWebServer().apply { start() }.use { server ->
        val transport = adapter(server)
        val form = ProviderHttpRequest.FormPost(server.url("/synthetic/exchange"),
            mapOf("code" to secret("a +&é")))
        val json = ProviderHttpRequest.JsonPost(server.url("/synthetic/poll"),
            Json.parseToJsonElement("""{"value":12,"text":"12","flag":true}"""))
        listOf(form, json).forEach { selected ->
            server.enqueue(MockResponse.Builder().body("{}").build())
            val result = Receipt()
            transport.execute(selected, deadline(), result::accept)
            assertTrue(result.await() is TransportResult.Response)
            val request = server.takeRequest(5, TimeUnit.SECONDS)!!
            assertEquals("POST", request.method)
            assertEquals(selected.url.encodedPath, request.target)
            assertNull(request.headers["Authorization"])
            assertEquals("ProviderHttpRequest(redacted)", selected.toString())
            if (selected === form) {
                assertEquals("application/x-www-form-urlencoded", request.headers["Content-Type"])
                assertEquals("code=a+%2B%26%C3%A9", request.body!!.utf8())
            } else {
                assertEquals("application/json; charset=utf-8", request.headers["Content-Type"])
                assertEquals(json.tree.toString(), request.body!!.utf8())
            }
        }
    }

    @Test fun redirectsNeverForwardBearerToSecondOrigin() = MockWebServer().apply { start() }.use { origin ->
        MockWebServer().apply { start() }.use { destination ->
            val transport = adapter(origin)
            listOf(destination.url("/must-not-follow"),
                destination.url("/tls-must-not-follow").newBuilder().scheme("https").build()).forEach { location ->
                origin.enqueue(MockResponse.Builder().code(302).addHeader("Location", location).body("").build())
                val result = Receipt()
                transport.read(ReadRequest(ReadOperation.USAGE, deadline()), result::accept)
                assertEquals(302, (result.await() as TransportResult.Response).status)
                assertEquals(0, destination.requestCount)
            }
        }
    }

    @Test fun rejectsRemoteCleartextCredentialsFragmentsAndInvalidJsonRequests(): Unit = MockWebServer().apply { start() }.use { server ->
        val transport = adapter(server)
        val requests = listOf(
            ProviderHttpRequest.Get("http://example.invalid/".toHttpUrl(), secret()),
            ProviderHttpRequest.Get("https://user:pass@example.invalid/".toHttpUrl(), secret()),
            ProviderHttpRequest.Get(server.url("/#fragment"), secret()),
            ProviderHttpRequest.Get(server.url("/"), secret("bad\nheader")),
            ProviderHttpRequest.Get(server.url("/"), SensitiveValue.copyOf(byteArrayOf(0xc3.toByte(), 0x28))),
            ProviderHttpRequest.JsonPost(server.url("/"), JsonPrimitive(Double.NaN)))
        requests.forEach {
            val result = Receipt()
            transport.execute(it, deadline(), result::accept)
            assertEquals(TransportResult.Failure(TransportFailure.INVALID_RESPONSE), result.await())
        }
        assertEquals(0, server.requestCount)
        assertThrows(IllegalArgumentException::class.java) {
            HttpTransportAdapter({ server.url("/") }, { secret() }, "bad\nversion")
        }
        assertThrows(IllegalArgumentException::class.java) { adapter(server, 0) }
    }

    @Test fun rejectsKnownAndChunkedOversizeButAcceptsExactLimit() = MockWebServer().apply { start() }.use { server ->
        val transport = adapter(server)
        val huge = "x".repeat(ResponseBody.MAX_BYTES + 1)
        listOf(MockResponse.Builder().body(huge).build(),
            MockResponse.Builder().chunkedBody(huge, 8192).build()).forEach { reply ->
            server.enqueue(reply)
            val result = Receipt()
            transport.read(ReadRequest(ReadOperation.USAGE, deadline()), result::accept)
            assertEquals(TransportResult.Failure(TransportFailure.BODY_TOO_LARGE), result.await())
        }
        server.enqueue(MockResponse.Builder().body("x".repeat(ResponseBody.MAX_BYTES)).build())
        val exact = Receipt()
        transport.read(ReadRequest(ReadOperation.USAGE, deadline()), exact::accept)
        assertEquals(ResponseBody.MAX_BYTES, (exact.await() as TransportResult.Response).body.size)
    }

    @Test fun readTimeoutIsCategoricalAndClosesTheRealCall() = MockWebServer().apply { start() }.use { server ->
        server.enqueue(MockResponse.Builder().body("{}").throttleBody(1, 2, TimeUnit.SECONDS).build())
        val observed = ObservedCall()
        val transport = HttpTransportAdapter({ server.url("/") }, { secret() }, "0.1.0",
            readTimeoutMillis = 1000, calls = observed::create)
        val result = Receipt()
        transport.read(ReadRequest(ReadOperation.USAGE, deadline()), result::accept)
        await(observed.bodyStarted, "body acquired before read timeout")
        assertEquals(TransportResult.Failure(TransportFailure.NETWORK), result.await())
        await(observed.finished, "read-timeout call settled")
        assertTrue(observed.call.get().isCanceled())
        assertEquals(1, result.count.get())
    }

    @Test fun realCancellationClosesCallWhileBodyIsOwned() = MockWebServer().apply { start() }.use { server ->
        server.enqueue(MockResponse.Builder().body("{}").throttleBody(1, 2, TimeUnit.SECONDS).build())
        val observed = ObservedCall()
        val transport = HttpTransportAdapter({ server.url("/") }, { secret() }, "0.1.0", calls = observed::create)
        val result = Receipt()
        val handle = transport.read(ReadRequest(ReadOperation.USAGE, deadline()), result::accept)
        await(observed.bodyStarted, "response body acquired")
        handle.cancel()
        assertEquals(TransportResult.Failure(TransportFailure.CANCELLED), result.await())
        await(observed.finished, "cancelled real call settled")
        assertTrue(observed.call.get().isCanceled())
        handle.cancel()
        assertEquals(1, result.count.get())
    }

    @Test fun operationDeadlineCancelsStalledRealCall() = MockWebServer().apply { start() }.use { server ->
        server.enqueue(MockResponse.Builder().body("{}").headersDelay(1, TimeUnit.SECONDS).build())
        val observed = ObservedCall()
        val transport = HttpTransportAdapter({ server.url("/") }, { secret() }, "0.1.0", calls = observed::create)
        val result = Receipt()
        transport.read(ReadRequest(ReadOperation.USAGE, deadline(80)), result::accept)
        assertEquals(TransportResult.Failure(TransportFailure.DEADLINE_EXCEEDED), result.await())
        await(observed.finished, "deadline call settled")
        assertTrue(observed.call.get().isCanceled())
        assertEquals(1, result.count.get())
    }

    @Test fun expiredDeadlineDoesNotCreateACallAndRemainingBudgetOwnsTimeout() = MockWebServer().apply { start() }.use { server ->
        val now = AtomicReference(TransportTime(Instant.EPOCH, 100))
        val requestDeadline = ReadDeadline.after(now.get(), 20)
        val captured = AtomicReference<ControlledCall>()
        val transport = HttpTransportAdapter({ server.url("/") }, { secret() }, "0.1.0", { now.get() },
            calls = { client, request -> ControlledCall(client.newCall(request)).also { captured.set(it) } })
        now.set(TransportTime(Instant.EPOCH, 110))
        val result = Receipt()
        val handle = transport.read(ReadRequest(ReadOperation.USAGE, requestDeadline), result::accept)
        assertEquals(TimeUnit.MILLISECONDS.toNanos(10), captured.get().timeout().timeoutNanos())
        now.set(TransportTime(Instant.EPOCH, 120))
        captured.get().succeed()
        assertEquals(TransportResult.Failure(TransportFailure.DEADLINE_EXCEEDED), result.await())
        handle.cancel()
        val expired = Receipt()
        transport.read(ReadRequest(ReadOperation.USAGE, requestDeadline), expired::accept).cancel()
        assertEquals(TransportResult.Failure(TransportFailure.DEADLINE_EXCEEDED), expired.await())
        assertEquals(1, expired.count.get())
    }

    @Test fun lateCallbacksAfterCancelAndSuccessCloseBodiesWithoutDoubleDelivery() = MockWebServer().apply { start() }.use { server ->
        val controlled = AtomicReference<ControlledCall>()
        val transport = HttpTransportAdapter({ server.url("/") }, { secret() }, "0.1.0",
            calls = { client, request -> ControlledCall(client.newCall(request)).also { controlled.set(it) } })
        listOf(true, false).forEach { cancel ->
            val result = Receipt()
            val handle = transport.read(ReadRequest(ReadOperation.USAGE, deadline()), result::accept)
            val call = controlled.get()
            if (cancel) handle.cancel() else call.succeed()
            val body = TrackingBody()
            call.succeed(body)
            call.fail()
            handle.cancel()
            assertTrue(body.closed.get() > 0)
            assertEquals(1, result.count.get())
            if (cancel) assertEquals(TransportResult.Failure(TransportFailure.CANCELLED), result.await())
            else assertTrue(result.await() is TransportResult.Response)
        }
    }

    @Test fun cancellationClosesPublishedBodyBeforeDeliveringTerminal() = MockWebServer().apply { start() }.use { server ->
        val controlled = AtomicReference<ControlledCall>()
        val transport = HttpTransportAdapter({ server.url("/") }, { secret() }, "0.1.0",
            calls = { client, request -> ControlledCall(client.newCall(request)).also { controlled.set(it) } })
        val body = TrackingBody(blockRead = true)
        val result = Receipt()
        val handle = transport.read(ReadRequest(ReadOperation.USAGE, deadline())) {
            assertTrue(body.closed.get() > 0)
            result.accept(it)
        }
        val worker = Thread { controlled.get().succeed(body) }.apply { start() }
        try {
            await(body.readEntered, "bounded reader entered")
            handle.cancel()
            assertTrue(controlled.get().isCanceled())
            assertEquals(0, result.count.get())
            body.release.countDown()
            assertEquals(TransportResult.Failure(TransportFailure.CANCELLED), result.await())
        } finally {
            body.release.countDown()
            worker.join(5000)
        }
        assertFalse(worker.isAlive)
        assertEquals(1, result.count.get())
    }

    @Test fun overlappingCancellationsWaitForTheShutdownOwnerToCloseBody() = MockWebServer().apply { start() }.use { server ->
        val controlled = AtomicReference<ControlledCall>()
        val transport = HttpTransportAdapter({ server.url("/") }, { secret() }, "0.1.0",
            calls = { client, request -> ControlledCall(client.newCall(request)).also { controlled.set(it) } })
        val body = TrackingBody(blockRead = true, blockClose = true)
        val result = Receipt()
        val handle = transport.read(ReadRequest(ReadOperation.USAGE, deadline())) {
            assertTrue(body.closed.get() > 0)
            result.accept(it)
        }
        val responseWorker = Thread { controlled.get().succeed(body) }.apply { start() }
        try {
            await(body.readEntered, "body acquired before racing cancellation")
            handle.cancel()
            assertTrue(controlled.get().isCanceled())
            body.release.countDown()
            await(body.closeEntered, "reader entered body close")
            handle.cancel()
            assertEquals(0, result.count.get())
            body.releaseClose.countDown()
            assertEquals(TransportResult.Failure(TransportFailure.CANCELLED), result.await())
        } finally {
            body.releaseClose.countDown()
            body.release.countDown()
            responseWorker.join(5000)
        }
        assertFalse(responseWorker.isAlive)
        assertEquals(1, result.count.get())
    }

    @Test fun blockedDeadlineCallbackDoesNotDelayAnIndependentDeadline(): Unit = MockWebServer().apply { start() }.use { server ->
        val controlled = AtomicReference<ControlledCall>()
        val transport = HttpTransportAdapter({ server.url("/") }, { secret() }, "0.1.0",
            calls = { client, request -> ControlledCall(client.newCall(request)).also { controlled.set(it) } })
        val firstEntered = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val firstDone = CountDownLatch(1)
        val callbackFailure = AtomicReference<Throwable>()
        transport.read(ReadRequest(ReadOperation.USAGE, deadline(50))) {
            firstEntered.countDown()
            try { await(releaseFirst, "first callback released") }
            catch (failure: Throwable) { callbackFailure.set(failure) }
            finally { firstDone.countDown() }
        }
        try {
            await(firstEntered, "first deadline callback entered")
            val second = Receipt()
            transport.read(ReadRequest(ReadOperation.USAGE, deadline(50)), second::accept)
            val secondCall = controlled.get()
            assertEquals(TransportResult.Failure(TransportFailure.DEADLINE_EXCEEDED), second.await())
            assertTrue(secondCall.isCanceled())
        } finally {
            releaseFirst.countDown()
            await(firstDone, "blocked callback finished")
        }
        callbackFailure.get()?.let { throw it }
    }

    @Test fun earlyDeadlineChecksRescheduleUntilTheBudgetActuallyExpires() = MockWebServer().apply { start() }.use { server ->
        val now = AtomicReference(TransportTime(Instant.EPOCH, 100))
        val earlyCheck = CountDownLatch(1)
        val reads = AtomicInteger()
        val clock = TransportClock {
            if (reads.incrementAndGet() >= 4) earlyCheck.countDown()
            now.get()
        }
        val transport = HttpTransportAdapter({ server.url("/") }, { secret() }, "0.1.0", clock,
            calls = { client, request -> ControlledCall(client.newCall(request)) })
        val result = Receipt()
        val handle = transport.read(ReadRequest(ReadOperation.USAGE, ReadDeadline.after(now.get(), 20)), result::accept)
        try {
            await(earlyCheck, "early timer check observed")
            assertEquals(0, result.count.get())
            now.set(TransportTime(Instant.EPOCH, 120))
            assertEquals(TransportResult.Failure(TransportFailure.DEADLINE_EXCEEDED), result.await())
        } finally { handle.cancel() }
    }

    @Test fun callFailuresAreRedactedAndCancelOwnedResources() = MockWebServer().apply { start() }.use { server ->
        val controlled = AtomicReference<ControlledCall>()
        val url = server.url("/synthetic-only").newBuilder().scheme("https").build()
        val transport = HttpTransportAdapter({ url }, { secret() }, "0.1.0",
            calls = { client, request -> ControlledCall(client.newCall(request)).also { controlled.set(it) } })
        val result = Receipt()
        val handle = transport.read(ReadRequest(ReadOperation.USAGE, deadline()), result::accept)
        controlled.get().fail()
        assertEquals(TransportResult.Failure(TransportFailure.NETWORK), result.await())
        assertTrue(controlled.get().isCanceled())
        handle.cancel()
        assertEquals(1, result.count.get())
    }

    @Test fun bodyReadFailureClosesOwnedBodyAndRedactsCause() = MockWebServer().apply { start() }.use { server ->
        val controlled = AtomicReference<ControlledCall>()
        val transport = HttpTransportAdapter({ server.url("/") }, { secret() }, "0.1.0",
            calls = { client, request -> ControlledCall(client.newCall(request)).also { controlled.set(it) } })
        val result = Receipt()
        transport.read(ReadRequest(ReadOperation.USAGE, deadline()), result::accept)
        val body = TrackingBody(failRead = true)
        controlled.get().succeed(body)
        assertEquals(TransportResult.Failure(TransportFailure.NETWORK), result.await())
        assertTrue(body.closed.get() > 0)
        assertFalse(result.await().toString().contains("synthetic-secret"))
    }

    private class Receipt {
        val count = AtomicInteger()
        private val completed = CountDownLatch(1)
        private val result = AtomicReference<TransportResult>()
        fun accept(value: TransportResult) { result.set(value); count.incrementAndGet(); completed.countDown() }
        fun await(): TransportResult { await(completed, "terminal receipt"); return result.get() }
    }

    private class ObservedCall {
        val call = AtomicReference<Call>()
        val bodyStarted = CountDownLatch(1)
        val finished = CountDownLatch(1)
        fun create(client: OkHttpClient, request: Request): Call = client.newCall(request).also {
            call.set(it)
            it.addEventListener(object : EventListener() {
                override fun responseBodyStart(call: Call) { bodyStarted.countDown() }
                override fun callEnd(call: Call) { finished.countDown() }
                override fun callFailed(call: Call, ioe: IOException) { finished.countDown() }
            })
        }
    }

    private class ControlledCall(private val delegate: Call) : Call by delegate {
        private lateinit var callback: Callback
        override fun enqueue(responseCallback: Callback) { callback = responseCallback }
        override fun cancel() { delegate.cancel() }
        fun fail() { callback.onFailure(this, IOException("synthetic-secret")) }
        fun succeed(body: WireBody = TrackingBody()) {
            callback.onResponse(this, Response.Builder().request(request()).protocol(Protocol.HTTP_1_1)
                .code(200).message("OK").body(body).build())
        }
    }

    private class TrackingBody(
        private val blockRead: Boolean = false, private val failRead: Boolean = false,
        private val blockClose: Boolean = false,
    ) : WireBody() {
        val closed = AtomicInteger()
        val readEntered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val closeEntered = CountDownLatch(1)
        val releaseClose = CountDownLatch(1)
        private val reading = AtomicBoolean()
        private val stream = object : ForwardingSource(Buffer().writeUtf8("{}")) {
            override fun read(sink: Buffer, byteCount: Long): Long {
                reading.set(true)
                try {
                    readEntered.countDown()
                    if (blockRead) await(release, "body released")
                    if (failRead) throw IOException("synthetic-secret")
                    return super.read(sink, byteCount)
                } finally { reading.set(false) }
            }
            override fun close() { closed.incrementAndGet(); super.close() }
        }.buffer()
        override fun contentType(): MediaType? = null
        override fun contentLength(): Long = -1
        override fun source(): BufferedSource = stream
        override fun close() {
            check(!reading.get()) { "Body must close on the reader owner" }
            closeEntered.countDown()
            if (blockClose) await(releaseClose, "body close released")
            closed.incrementAndGet()
            super.close()
        }
    }

    private companion object {
        fun await(latch: CountDownLatch, step: String) {
            assertTrue(step, latch.await(5, TimeUnit.SECONDS))
        }
    }
}
