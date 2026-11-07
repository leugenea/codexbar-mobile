package io.github.leugenea.codexbarmobile

import android.content.Context
import androidx.annotation.VisibleForTesting
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import io.github.leugenea.codexbarmobile.auth.AuthState
import io.github.leugenea.codexbarmobile.auth.AuthTransport
import io.github.leugenea.codexbarmobile.credentials.syncDirectory
import io.github.leugenea.codexbarmobile.account.AccountCatalog
import io.github.leugenea.codexbarmobile.transport.HttpTransportAdapter
import io.github.leugenea.codexbarmobile.transport.SystemTransportClock
import io.github.leugenea.codexbarmobile.transport.TransportClock
import kotlinx.coroutines.delay
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/** One holder for the default application process and its retained entry runtimes. */
internal object NativeConnection {
    private var owner: AccountRuntimeOwner? = null
    private var controller: ConnectionController? = null
    private val productionFactory: (Context) -> ConnectionController = ::create
    @set:VisibleForTesting(otherwise = VisibleForTesting.NONE)
    internal var factory: (Context) -> ConnectionController = productionFactory

    @Synchronized fun get(context: Context): ConnectionController =
        controller ?: factory(context.applicationContext).also { controller = it }

    /** Instrumentation only: install a test factory, then drain the previous owner's durable work. */
    @VisibleForTesting(otherwise = VisibleForTesting.NONE)
    @Synchronized internal fun resetForTests() {
        check(factory !== productionFactory) { "Test factory required" }
        controller?.let { old -> kotlinx.coroutines.runBlocking {
            kotlinx.coroutines.withTimeout(5_000) { old.shutdown() }
        } }
        owner?.let { old -> kotlinx.coroutines.runBlocking {
            kotlinx.coroutines.withTimeout(5_000) { old.shutdown() }
        } }
        owner = null
        controller = null
    }

    private fun create(context: Context): ConnectionController = processOwner(context).legacy()

    @Synchronized private fun processOwner(context: Context): AccountRuntimeOwner {
        owner?.let { return it }
        val catalog = try { catalog(context) } catch (_: Exception) { null }
        val periodic = PeriodicUsageRefresh(androidx.work.WorkManager.getInstance(context.applicationContext))
        return AccountRuntimeOwner(context, catalog, productionTransport(context), SystemTransportClock, { delay(it) },
            periodic.entryEnrollment(catalog?.legacyEntry()?.partition)).also { owner = it }
    }

    /** Internal entry ingress; no selected-dashboard or all-account scheduler policy. */
    @Synchronized internal fun entry(context: Context, id: UUID): ConnectionController? {
        check(factory === productionFactory)
        return processOwner(context.applicationContext).runtime(id)
    }

    @VisibleForTesting(otherwise = VisibleForTesting.NONE)
    internal fun create(context: Context, transport: AuthTransport, clock: TransportClock,
        pause: suspend (Long) -> Unit): ConnectionController = createController(context, transport, clock, pause, {})

    @VisibleForTesting(otherwise = VisibleForTesting.NONE)
    internal fun create(context: Context, transport: AuthTransport, clock: TransportClock,
        pause: suspend (Long) -> Unit, backgroundEnrollment: (BackgroundRefreshTarget?) -> Unit): ConnectionController =
        createController(context, transport, clock, pause, backgroundEnrollment)

    private fun createController(context: Context, suppliedTransport: AuthTransport?, clock: TransportClock,
        pause: suspend (Long) -> Unit, backgroundEnrollment: (BackgroundRefreshTarget?) -> Unit): ConnectionController {
        return createOwner(context, suppliedTransport ?: productionTransport(context), clock, pause, backgroundEnrollment).legacy()
    }

    internal fun createOwner(context: Context, transport: AuthTransport, clock: TransportClock,
        pause: suspend (Long) -> Unit, legacyEnrollment: (BackgroundRefreshTarget?) -> Unit = {}): AccountRuntimeOwner {
        val catalog = try { catalog(context.applicationContext) } catch (_: Exception) { null }
        return AccountRuntimeOwner(context, catalog, transport, clock, pause, legacyEnrollment)
    }

    private fun productionTransport(context: Context): AuthTransport {
        val version = context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "0.1.0"
        // Explicit GET requests carry a captured bearer; adapter.read is never used by this gate.
        val adapter = HttpTransportAdapter(NativeFeasibilityReader::url, { error("Bearer owner required") }, version)
        return AuthTransport(adapter::execute)
    }

    internal fun session(context: Context): UUID = catalog(context).snapshot().legacySlot

    private fun catalog(context: Context): AccountCatalog {
        val catalog = AccountCatalog.open(context.noBackupFilesDir, legacySession = { legacySession(context) })
        val selector = File(context.noBackupFilesDir, "connection-session")
        if (selector.exists()) require(legacySession(context) == catalog.snapshot().legacySlot)
        return catalog
    }

    private fun legacySession(context: Context): UUID {
        val target = File(context.noBackupFilesDir, "connection-session")
        if (target.exists()) {
            val text = target.inputStream().use { input ->
                val bytes = ByteArray(37)
                val size = input.read(bytes)
                require(size == 36 && input.read() == -1) { "Invalid local session" }
                bytes.copyOf(size).toString(Charsets.US_ASCII)
            }
            val session = UUID.fromString(text)
            require(session.toString() == text) { "Invalid local session" }
            return session
        }
        check(!legacyArtifacts(context.noBackupFilesDir)) { "Missing local session selector" }
        val session = UUID.randomUUID()
        val temporary = File.createTempFile("connection-", ".tmp", context.noBackupFilesDir)
        try {
            FileOutputStream(temporary).use { it.write(session.toString().toByteArray(Charsets.US_ASCII)); it.fd.sync() }
            check(temporary.renameTo(target)) { "Local session write failed" }
            syncDirectory(context.noBackupFilesDir)
        } finally { temporary.delete() }
        return session
    }

    private fun legacyArtifacts(root: File): Boolean {
        if (File(root, "usage-history").list()?.isNotEmpty() == true) return true
        return File(root, "credentials").listFiles()?.any {
            File(it, "value.bin").exists() || File(it, "rotation-pending").exists()
        } == true
    }
}

@Composable
internal fun ConnectionContent(state: ConnectionState, controller: ConnectionController, openAccountSettings: () -> Unit) {
    LiveUsageScreen(state)
    Text(stringResource(R.string.gate_identity))
    Text(stringResource(R.string.gate_boundary))
    Text(stringResource(R.string.gate_lifecycle))
    Text(stringResource(R.string.gate_state, state.phase.name, state.problem?.name ?: "—"),
        Modifier.testTag("gate-state"))
    if (state.auth is AuthState.Connected) Text(stringResource(R.string.gate_exchange_completed))
    if (DashboardPresentation.loginVisible(state.phase)) AccountSettingsLink(openAccountSettings)
    OutlinedButton(onClick = { controller.readUsage() }, enabled = !state.busy && controller.session.snapshot() is SessionResult.Ready,
        modifier = Modifier.testTag("read-usage")) { Text(stringResource(R.string.gate_read)) }
    OutlinedButton(onClick = { controller.readUsage(refreshSession = true) },
        enabled = !state.busy && controller.session.snapshot() is SessionResult.Ready,
        modifier = Modifier.testTag("refresh-session")) { Text(stringResource(R.string.gate_refresh)) }
}
