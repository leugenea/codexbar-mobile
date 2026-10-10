package io.github.leugenea.codexbarmobile

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.leugenea.codexbarmobile.credentials.KeystoreCredentialStore
import io.github.leugenea.codexbarmobile.history.*
import io.github.leugenea.codexbarmobile.transport.ReadError
import io.github.leugenea.codexbarmobile.transport.ReadOperation
import io.github.leugenea.codexbarmobile.usage.WindowKind
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.math.BigDecimal
import java.security.KeyStore
import java.time.Instant

/** #90: real MainActivity/process owner, synthetic transport, real isolated Keystore/SQLite. */
@RunWith(AndroidJUnit4::class)
class HistoryIsolationTest {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var fixture: HistoryIsolationFixture
    private val owner get() = fixture.owner
    private val transport get() = fixture.transport

    @Before fun prepare() { fixture = HistoryIsolationFixture(); fixture.prepare() }
    @After fun cleanup() { fixture.cleanup() }

    @Test fun heldWindowQueriesRejectSupersessionBeforeAndAfterAuthoritativePublication() {
        seedDormant()
        ActivityScenario.launch(MainActivity::class.java).use {
            openHistory()
            page(query(), 32)
            for (published in listOf(false, true)) {
                supersede("window published=$published", "history-select-WEEKLY", query(WindowKind.WEEKLY),
                    "history-select-FIVE_HOUR", query(), 32, published)
            }
            assertDormant()
        }
    }

    @Test fun heldPageQueriesRejectSupersessionBeforeAndAfterAuthoritativePublication() {
        seedDormant()
        ActivityScenario.launch(MainActivity::class.java).use {
            openHistory()
            page(query(), 32)
            for (published in listOf(false, true)) {
                supersede("page published=$published", "history-next-page", query(after = 32),
                    "history-first-page", query(), 3, published)
            }
            assertDormant()
        }
    }

    private fun supersede(step: String, first: String, older: HistoryGraphQuery, second: String,
        newer: HistoryGraphQuery, oldCount: Int, published: Boolean) {
        if (published) { click(first); page(older, oldCount) }
        val gate = fixture.holdNextRead(step)
        try {
            click(if (published) second else first)
            held(step, gate, if (published) newer else older)
            if (!published) click(second)
            requested("$step successor command", newer)
            val boundary = fixture.publications.size
            assertNoPage()
            gate.release()
            page(newer, 32)
            await("$step actual storage return") { gate.returned }
            val subsequent = fixture.publications.drop(boundary).filter { it.storage != null }
            assertTrue("$step successor must actually publish", subsequent.isNotEmpty())
            assertTrue("$step old query must never republish", subsequent.all { it.query == newer })
            val captured = (gate.receipt!!.outcome as HistoryReadOutcome.Ready).snapshot
            if (published) assertTrue("$step must accept its own successor result", subsequent.any { it.storage === captured })
            else assertTrue("$step must reject the exact held predecessor result even when window reads share a storage cursor",
                subsequent.none { it.storage === captured })
            assertEquals((1L..32L).toList(), owner.historySnapshots.value.storage!!.entries.map { it.id.ordinal })
        } finally { gate.release() }
    }

    @Test fun heldUsageAndInventoryResponsesCannotResurrectHistoryAcrossLogoutAndRelogin() {
        for (operation in listOf(ReadOperation.USAGE, ReadOperation.RESET_INVENTORY)) {
            logoutWithHeldResponse(operation)
            transport.usageBody = NavigationTransport.USAGE
            transport.inventoryBody = """{"available_count":0,"credits":[]}"""
            fixture.native.freshRuntime()
            phase(ConnectionPhase.IDLE)
        }
    }

    private fun logoutWithHeldResponse(operation: ReadOperation) {
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.waitForIdle(); click("connection-tab"); phase(ConnectionPhase.IDLE)
            click("connect"); phase(ConnectionPhase.OBSERVED)
            await("initial login recorded") { owner.historySnapshots.value.storage?.lastAdmitted?.ordinal == 1L }
            click("history-toggle"); page(query(), 1)
            val old = owner.historySnapshots.value
            val oldAccess = fixture.capability()
            val before = transport.counts()
            val gate = fixture.holdNextRead("logout ${operation.name} old SQLite page")
            try {
                click("history-select-WEEKLY"); held("old weekly page", gate, query(WindowKind.WEEKLY))
                transport.heldPath = operation.path
                transport.advance(1000)
                owner.readUsage()
                await("${operation.name} request actually admitted") { transport.heldCall(operation.path) != null }
                val late = transport.heldCall(operation.path)!!
                click("history-toggle"); click("remove-account"); click("remove-account-confirm")
                phase(ConnectionPhase.SIGNED_OUT)
                click("history-toggle")
                fixture.settled("logout ${operation.name} processed")
                assertTrue(late.cancelled)
                assertNull(old.displayPermission!!.current())
                assertRevoked(oldAccess)
                assertNoPage()
                assertDeleted()
                val boundary = fixture.publications.size
                transport.heldPath = null
                if (operation == ReadOperation.USAGE) late.reply() // Pre-successor-publication ordering.
                transport.usageBody = NavigationTransport.USAGE.replace("12.375", "42.125").replace("87.5", "66.75")
                transport.inventoryBody = """{"available_count":2,"credits":[]}"""
                transport.advance(1000)
                click("history-toggle"); click("connect"); phase(ConnectionPhase.OBSERVED)
                await("successor capability is positively adopted before releasing old read") {
                    owner.historySnapshots.value.generation != null && owner.historySnapshots.value.generation !== old.generation
                }
                val successorAccess = fixture.capability()
                assertNotEquals(old.partition, successorAccess.partition)
                assertTrue(successorAccess.read(HistoryReadQuery(successorAccess.partition, 1)) is HistoryReadOutcome.Ready)
                gate.release()
                await("successor only ordinal one recorded") { owner.historySnapshots.value.storage?.lastAdmitted?.ordinal == 1L }
                click("history-toggle"); page(query(), 1)
                val successor = owner.historySnapshots.value
                val counts = transport.counts()
                transport.advance(1000) // A stale completion would acquire this different receipt clock.
                if (operation == ReadOperation.RESET_INVENTORY) late.reply() // Post-successor-publication ordering.
                fixture.settled("late ${operation.name} callback submitted")
                assertEquals(listOf(BigDecimal("42.125")), owner.historySnapshots.value.points.map { it.usedPercent })
                assertEquals(successor.partition, owner.historySnapshots.value.partition)
                assertEquals(successor.live, owner.historySnapshots.value.live)
                assertEquals(2L, owner.state.value.refresh.inventory.success!!.inventory!!.reportedAvailableCount.value)
                assertEquals(counts, transport.counts())
                assertTrue(fixture.publications.drop(boundary).filter { it.storage != null }.all { it.partition == successor.partition })
                click("history-details-toggle")
                text("Measured used percent (exact %): 42.125").performScrollTo().assertIsDisplayed()
                absent("Measured used percent (exact %): 12.375")
                absent(old.live.usage.sourceObservedAt!!.toString(), substring = true)
                val gets = if (operation == ReadOperation.USAGE) 1L else 2L
                assertReadAndLoginDelta(before, gets)
                owner.signOut(); phase(ConnectionPhase.SIGNED_OUT); assertDeleted()
            } finally { gate.release(); transport.releaseHeld() }
        }
    }

    @Test fun heldLocalPagesLoseLastObserverAuthorityBeforeFreshResumeAndRecreationAdoption() {
        seedDormant()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            openHistory(); page(query(), 32)
            for (recreate in listOf(false, true)) retireHeldPage(scenario, recreate)
            assertDormant()
        }
        await("finished Activity retires the last post-recreation capability") { owner.historySnapshots.value.generation == null }
        assertDormant()
    }

    private fun retireHeldPage(scenario: ActivityScenario<MainActivity>, recreate: Boolean) {
        val old = owner.historySnapshots.value
        val access = fixture.capability()
        val gate = fixture.holdNextRead("lifecycle recreate=$recreate")
        try {
            click("history-select-WEEKLY"); held("lifecycle held weekly read", gate, query(WindowKind.WEEKLY))
            val boundary = fixture.publications.size
            if (recreate) scenario.recreate() else {
                scenario.moveToState(Lifecycle.State.CREATED)
                await("last STOP observer actually retires history") { owner.historySnapshots.value.generation == null }
                assertNull(old.displayPermission!!.current())
                scenario.moveToState(Lifecycle.State.RESUMED)
            }
            compose.waitForIdle()
            await("fresh lifecycle capability adopted while predecessor result stays held") {
                owner.historySnapshots.value.generation != null && owner.historySnapshots.value.generation !== old.generation
            }
            scenario.onActivity { assertSame(owner, it.connection) }
            assertTrue("actual STOP publication must precede re-adoption",
                fixture.publications.drop(boundary).any { it.generation == null && it.readiness == HistoryReadiness.UNAVAILABLE })
            val fresh = fixture.capability()
            assertNotSame(access, fresh)
            assertEquals(access.partition, fresh.partition)
            assertTrue(fresh.read(HistoryReadQuery(fresh.partition, 1)) is HistoryReadOutcome.Ready)
            assertRevoked(access) // Deliberately after the positive successor proof, not an already-dead-port oracle.
            assertNotSame(old.displayPermission!!.context, old.displayPermission.current())
            if (recreate) click("history-toggle")
            assertNoPage()
            gate.release(); page(query(), 32)
            val current = owner.historySnapshots.value.generation
            assertTrue(fixture.publications.drop(boundary).filter { it.storage != null }.all { it.generation === current })
            assertDormant()
        } finally { gate.release() }
    }

    @Test fun integratedStaleUsageIndependentInventoryLossAndRetentionNeverBecomeEmptyOrCurrent() {
        fixture.limits = HistoryStorageLimits(observations = 3) // Synthetic tighter ceiling, never a production-policy change.
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.waitForIdle(); click("connection-tab"); phase(ConnectionPhase.IDLE)
            click("connect"); phase(ConnectionPhase.OBSERVED)
            await("original accepted usage persisted") { owner.historySnapshots.value.storage?.lastAdmitted?.ordinal == 1L }
            fixture.native.seedLocalPage()
            fixture.failRead = true
            transport.advance(1000); owner.readUsage(); phase(ConnectionPhase.OBSERVED)
            await("actual SQLite read failure acknowledges unconfirmed usage") { owner.historySnapshots.value.lostSamples == 1L }
            fixture.failRead = false
            transport.usageStatus = 403
            transport.advance(900_000); owner.readUsage(); phase(ConnectionPhase.OBSERVED)
            await("B2 reevaluation marks only retained usage stale") {
                val live = owner.historySnapshots.value.live
                live.usage.stale && !live.inventory.stale && live.usage.error == ReadError.FORBIDDEN && !live.refreshing
            }
            click("history-toggle"); page(query(), 3, HistoryReadiness.ERROR)
            val source = owner.historySnapshots.value
            assertEquals((33L..35L).toList(), source.storage!!.entries.map { it.id.ordinal })
            assertEquals(35L, source.storage.lastAdmitted!!.ordinal)
            assertEquals(32L, source.storage.cutoffs.getValue(HistoryTruncation.OBSERVATION_CAP).ordinal)
            assertEquals(1L, source.lostSamples)
            assertNotEquals(source.live.usage.sourceObservedAt, source.live.inventory.sourceObservedAt)
            assertEquals(HistoryRecorderProblem.READ_FAILURE, source.problem)
            text("Retained endpoint facts are stale.").performScrollTo().assertIsDisplayed()
            text("Latest endpoint error: forbidden").assertExists()
            text("Retention truncated history: observation cap").assertExists()
            text("Evicted through admission ordinal: 32").assertExists()
            text("Last successful source observation (exact UTC): ${Instant.ofEpochSecond(1_800_000_001)}").assertExists()
            text("Last successful source observation (exact UTC): ${Instant.ofEpochSecond(1_800_000_901)}").assertExists()
            compose.onNodeWithTag("history-loss").assertTextEquals("Samples lost or unconfirmed: 1; history is incomplete.")
            absent("History page empty")
            absent("No entries in this admitted page")
            click("history-details-toggle")
            text("Measured used percent (exact %): 12.375").performScrollTo().assertIsDisplayed()
            val counts = transport.counts()
            click("history-select-WEEKLY"); page(query(WindowKind.WEEKLY), 3, HistoryReadiness.ERROR)
            assertEquals(counts, transport.counts())
            assertEquals(9L, transport.requests.get()); assertEquals(6L, transport.gets.get())
        }
    }

    private fun seedDormant() {
        phase(ConnectionPhase.IDLE)
        owner.usageForeground(fixture.native.observer, true); fixture.settled("seed foreground registered")
        owner.connect(); phase(ConnectionPhase.OBSERVED)
        await("seed endpoint persisted") { owner.historySnapshots.value.storage?.entries?.size == 1 }
        fixture.native.seedLocalPage()
        val counts = transport.counts()
        fixture.native.freshRuntime(); phase(ConnectionPhase.RESTORED)
        assertEquals(counts, transport.counts())
        assertDormant()
    }

    private fun openHistory() { compose.waitForIdle(); click("connection-tab"); click("history-toggle") }
    private fun click(tag: String) {
        val node = compose.onNodeWithTag(tag)
        if (tag !in setOf("connection-tab", "offline-tab", "remove-account-confirm")) node.performScrollTo()
        node.performClick(); compose.waitForIdle()
    }

    private fun query(kind: WindowKind = WindowKind.FIVE_HOUR, after: Long = 0) =
        HistoryGraphQuery(32, after.takeIf { it > 0 }?.let(::ObservationId), kind)

    private fun held(step: String, gate: HistoryQueryGate, expected: HistoryGraphQuery) {
        await("$step actual SQLite result captured") { gate.reached() }
        requested(step, expected)
        val receipt = gate.receipt!!
        assertEquals(expected.limit, receipt.query.limit)
        assertEquals(expected.after, receipt.query.after)
        assertEquals(owner.historySnapshots.value.partition, receipt.query.partition)
        assertTrue("$step must hold a real successful read, not an error", receipt.outcome is HistoryReadOutcome.Ready)
        assertFalse(gate.returned)
    }

    private fun requested(step: String, expected: HistoryGraphQuery) {
        fixture.settled("$step owner/query command receipt")
        await("$step requested query publication") {
            owner.historySnapshots.value.let { it.query == expected && it.readiness == HistoryReadiness.LOADING && it.storage == null }
        }
        compose.onNodeWithTag("history-page-cursor").assertTextEquals("Exclusive admission cursor: ${expected.after?.ordinal ?: 0} · Page limit: 32")
    }

    private fun page(expected: HistoryGraphQuery, count: Int, readiness: HistoryReadiness = HistoryReadiness.READY) {
        await("actual page $expected count=$count readiness=$readiness") {
            owner.historySnapshots.value.let { it.query == expected && it.generation != null && it.storage?.entries?.size == count && it.readiness == readiness }
        }
        compose.onNodeWithTag("history-readiness").assertTextEquals(if (readiness == HistoryReadiness.ERROR)
            "History error; retained facts may be shown" else "Admitted history page")
        compose.onNodeWithTag("history-page-cursor").assertTextEquals("Exclusive admission cursor: ${expected.after?.ordinal ?: 0} · Page limit: 32")
    }

    private fun assertNoPage() {
        compose.onNodeWithTag("history-page-ordinals").assertDoesNotExist()
        compose.onNodeWithTag("history-details-toggle").assertDoesNotExist()
        compose.onNodeWithTag("history-chart-five-hour-canvas", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithTag("history-chart-weekly-canvas", useUnmergedTree = true).assertDoesNotExist()
        absent("Measured used percent", substring = true)
    }

    private fun assertRevoked(access: HistoryRuntimeAccess) {
        assertEquals(HistoryReadOutcome.Unavailable(HistoryUnavailable.PARTITION_REVOKED), access.read(HistoryReadQuery(access.partition, 1)))
    }

    private fun assertDeleted() {
        val native = fixture.native
        val slot = NativeConnection.session(native.context)
        assertFalse(KeystoreCredentialStore.file(native.context, slot).exists())
        assertFalse(KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.containsAlias(KeystoreCredentialStore.alias(native.context, slot)))
        assertFalse(File(native.root, "usage-history/history.db").exists())
    }

    private fun assertDormant() {
        assertEquals(ConnectionPhase.RESTORED, owner.state.value.phase)
        assertEquals(5L, transport.requests.get()); assertEquals(2L, transport.gets.get())
        assertEquals(mapOf("/api/accounts/deviceauth/usercode" to 1L, "/api/accounts/deviceauth/token" to 1L,
            "/oauth/token" to 1L, ReadOperation.USAGE.path to 1L, ReadOperation.RESET_INVENTORY.path to 1L), transport.counts())
        owner.historySnapshots.value.storage?.let { assertEquals(35L, it.lastAdmitted!!.ordinal) }
    }

    private fun assertReadAndLoginDelta(before: Map<String, Long>, heldGets: Long) {
        val expected = before.toMutableMap()
        listOf("/api/accounts/deviceauth/usercode", "/api/accounts/deviceauth/token", "/oauth/token").forEach { expected[it] = expected.getValue(it) + 1 }
        expected[ReadOperation.USAGE.path] = expected.getValue(ReadOperation.USAGE.path) + 2
        expected[ReadOperation.RESET_INVENTORY.path] = expected.getValue(ReadOperation.RESET_INVENTORY.path) + heldGets
        assertEquals(expected, transport.counts())
        assertEquals(expected.values.sum(), transport.requests.get())
        assertEquals(expected.getValue(ReadOperation.USAGE.path) + expected.getValue(ReadOperation.RESET_INVENTORY.path), transport.gets.get())
    }

    private fun phase(expected: ConnectionPhase) = await("phase $expected") {
        owner.state.value.let { it.phase == expected && !it.refresh.refreshing }
    }

    private fun await(step: String, condition: () -> Boolean) {
        try { compose.waitUntil(timeoutMillis = 8_000, condition = condition) }
        catch (error: ComposeTimeoutException) {
            throw AssertionError("$step: phase=${owner.state.value.phase}, history=${owner.historySnapshots.value.readiness}, query=${owner.historySnapshots.value.query}, reads=${fixture.reads.size}, requests=${transport.counts()}", error)
        }
    }

    private fun text(value: String) = compose.onNode(hasAnyAncestor(hasTestTag("connection-history")) and hasText(value), useUnmergedTree = true)
    private fun absent(value: String, substring: Boolean = false) = compose.onAllNodes(
        hasAnyAncestor(hasTestTag("connection-history")) and hasText(value, substring = substring), useUnmergedTree = true).assertCountEquals(0)
}
