package io.github.leugenea.codexbarmobile

import android.content.Context
import android.content.ContextWrapper
import io.github.leugenea.codexbarmobile.auth.AuthTransport
import io.github.leugenea.codexbarmobile.credentials.KeystoreCredentialStore
import io.github.leugenea.codexbarmobile.history.*
import io.github.leugenea.codexbarmobile.transport.*
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import java.io.File
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/** Synthetic transport, isolated production-default owner, actual no-backup SQLite and Keystore. */
internal class HistoryNavigationFixture {
    private val application = ApplicationProvider.getApplicationContext<Context>()
    private val originalFactory = NativeConnection.factory
    val root = File(application.noBackupFilesDir, "synthetic-navigation-${UUID.randomUUID()}").canonicalFile
    val context: Context = object : ContextWrapper(application) {
        override fun getApplicationContext(): Context = this
        override fun getNoBackupFilesDir(): File = root
    }
    val transport = NavigationTransport()
    val observer = Any()
    val owner get() = NativeConnection.get(context)

    fun prepare() {
        assertTrue(root.mkdir())
        NativeConnection.factory = { NativeConnection.create(context, transport, transport.clock, transport::pause) }
        NativeConnection.resetForTests()
    }

    fun cleanup() {
        transport.releaseHeld()
        try { NativeConnection.resetForTests() }
        finally {
            NativeConnection.factory = originalFactory
            try {
                val credentials = KeystoreCredentialStore(context, NativeConnection.session(context))
                credentials.delete(credentials.openSession())
            } finally { root.deleteRecursively() }
        }
    }

    /** Arrangement only: original synthetic admissions, not claimed as provider recording evidence. */
    fun seedLocalPage() {
        val current = owner
        val ready = current.session.snapshot() as SessionResult.Ready
        val access = requireNotNull(current.historyCapability(ready.envelope.generation))
        val first = current.historySnapshots.value.storage!!.entries.single()
        val usage = current.state.value.refresh.usage.success!!.usage!!
        for (ordinal in 2L..35L) {
            val seconds = ordinal - 1
            val clock = HistoryClock(first.clock.epoch, (first.clock.monotonicMillis ?: 0) + seconds * 1000)
            val event = HistoryEvent.Observed(usage.copy(observedAt = usage.observedAt!!.plusSeconds(seconds)))
            val result = access.append(HistoryAdmission(access.partition, ObservationId(ordinal), clock, event))
            assertTrue("Synthetic navigation admission $ordinal: $result", result is HistoryAppendOutcome.Stored)
        }
        assertTrue(File(root, "usage-history/history.db").isFile)
    }

    fun freshRuntime() { NativeConnection.resetForTests() }
}

internal class NavigationTransport : AuthTransport {
    class HeldCall(val path: String, private val result: TransportResult, private val terminal: (TransportResult) -> Unit) {
        @Volatile var cancelled = false
        private val delivered = java.util.concurrent.atomic.AtomicBoolean()
        fun reply() { if (delivered.compareAndSet(false, true)) terminal(result) }
    }
    val requests = AtomicLong()
    val gets = AtomicLong()
    private val routes = ConcurrentHashMap<String, AtomicLong>()
    private val held = CopyOnWriteArrayList<HeldCall>()
    @Volatile var heldPath: String? = null
    @Volatile var usageBody = USAGE
    @Volatile var inventoryBody = """{"available_count":0,"credits":[]}"""
    private val millis = AtomicLong()
    val clock = TransportClock { TransportTime(Instant.ofEpochSecond(1_800_000_000).plusMillis(millis.get()), millis.get()) }
    @Volatile var usageStatus = 200
    @Volatile var inventoryStatus = 200
    fun advance(value: Long) { millis.addAndGet(value) }
    suspend fun pause(value: Long) { require(value >= 0) }
    fun counts(): Map<String, Long> = routes.mapValues { it.value.get() }
    fun heldCall(path: String): HeldCall? = held.lastOrNull { it.path == path }
    fun releaseHeld() { heldPath = null; held.forEach { it.reply() } }

    override fun execute(request: ProviderHttpRequest, deadline: ReadDeadline, terminal: (TransportResult) -> Unit): CancellationHandle {
        requests.incrementAndGet()
        if (request is ProviderHttpRequest.Get) gets.incrementAndGet()
        val path = request.url.encodedPath
        routes.computeIfAbsent(path) { AtomicLong() }.incrementAndGet()
        val call = HeldCall(path, response(path), terminal)
        if (path == heldPath) held += call else call.reply()
        return CancellationHandle { call.cancelled = true }
    }

    private fun response(path: String): TransportResult = when (path) {
        ReadOperation.USAGE.path -> body(usageBody, usageStatus)
        ReadOperation.RESET_INVENTORY.path -> body(inventoryBody, inventoryStatus)
        "/api/accounts/deviceauth/usercode" -> body("""{"device_auth_id":"synthetic-navigation","user_code":"SYNTHETIC-NAVIGATION"}""")
        "/api/accounts/deviceauth/token" -> body("""{"authorization_code":"synthetic-navigation-code","code_verifier":"synthetic-navigation-verifier"}""")
        "/oauth/token" -> body("""{"access_token":"synthetic-navigation-access","refresh_token":"synthetic-navigation-refresh"}""")
        else -> throw AssertionError("Unexpected synthetic navigation route")
    }

    private fun body(value: String, status: Int = 200) = TransportResult.Response.bounded(status, value.toByteArray())

    companion object {
        const val USAGE = """{"rate_limit":{"primary_window":{"limit_window_seconds":18000,"used_percent":12.375,"reset_at":1800003600},"secondary_window":{"limit_window_seconds":604800,"used_percent":87.5,"reset_at":1800003600}}}"""
    }
}
