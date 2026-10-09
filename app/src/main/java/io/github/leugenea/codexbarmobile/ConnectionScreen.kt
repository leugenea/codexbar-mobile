package io.github.leugenea.codexbarmobile

import android.content.Context
import androidx.annotation.VisibleForTesting
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.leugenea.codexbarmobile.auth.AuthState
import io.github.leugenea.codexbarmobile.auth.AuthTransport
import io.github.leugenea.codexbarmobile.auth.DeviceCodeAuthenticator
import io.github.leugenea.codexbarmobile.credentials.KeystoreCredentialStore
import io.github.leugenea.codexbarmobile.history.HistoryLifetimeCoordinator
import io.github.leugenea.codexbarmobile.history.SQLiteHistoryLifetimeStorage
import io.github.leugenea.codexbarmobile.history.SQLiteHistoryStore
import io.github.leugenea.codexbarmobile.transport.HttpTransportAdapter
import io.github.leugenea.codexbarmobile.transport.SystemTransportClock
import io.github.leugenea.codexbarmobile.transport.TransportClock
import kotlinx.coroutines.delay
import io.github.leugenea.codexbarmobile.usage.Field
import io.github.leugenea.codexbarmobile.usage.WindowSelection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/** One holder for the entire default application process, not one entry per Activity or slot. */
internal object NativeConnection {
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
        controller = null
    }

    private fun create(context: Context): ConnectionController =
        createController(context.applicationContext, null, SystemTransportClock) { delay(it) }

    @VisibleForTesting(otherwise = VisibleForTesting.NONE)
    internal fun create(context: Context, transport: AuthTransport, clock: TransportClock,
        pause: suspend (Long) -> Unit): ConnectionController = createController(context, transport, clock, pause)

    private fun createController(context: Context, suppliedTransport: AuthTransport?, clock: TransportClock,
        pause: suspend (Long) -> Unit): ConnectionController {
        val app = context.applicationContext
        val session = try { session(app) } catch (_: Exception) { null }
        val store = KeystoreCredentialStore(app, session ?: UUID.randomUUID())
        val transport = suppliedTransport ?: productionTransport(app)
        return ConnectionController(store, DeviceCodeAuthenticator(transport, store, clock, pause),
            NativeFeasibilityReader(transport, clock, pause),
            CoroutineScope(SupervisorJob() + Dispatchers.IO), storageReady = session != null, refreshClock = clock,
            history = HistoryLifetimeCoordinator(SQLiteHistoryLifetimeStorage(SQLiteHistoryStore.open(app))))
    }

    private fun productionTransport(context: Context): AuthTransport {
        val version = context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "0.1.0"
        // Explicit GET requests carry a captured bearer; adapter.read is never used by this gate.
        val adapter = HttpTransportAdapter(NativeFeasibilityReader::url, { error("Bearer owner required") }, version)
        return AuthTransport(adapter::execute)
    }

    internal fun session(context: Context): UUID {
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
        val session = UUID.randomUUID()
        val temporary = File.createTempFile("connection-", ".tmp", context.noBackupFilesDir)
        try {
            FileOutputStream(temporary).use { it.write(session.toString().toByteArray(Charsets.US_ASCII)); it.fd.sync() }
            check(temporary.renameTo(target)) { "Local session write failed" }
        } finally { temporary.delete() }
        return session
    }
}

@Composable
internal fun ConnectionScreen(state: ConnectionState, controller: ConnectionController, openBrowser: () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        LiveUsageScreen(state) { controller.readUsage() }
        Text(stringResource(R.string.gate_identity))
        Text(stringResource(R.string.gate_boundary))
        Text(stringResource(R.string.gate_lifecycle))
        Text(stringResource(R.string.gate_state, state.phase.name, state.problem?.name ?: "—"),
            Modifier.testTag("gate-state"))
        if (state.auth is AuthState.Connected) Text(stringResource(R.string.gate_exchange_completed))
        val awaiting = state.auth as? AuthState.AwaitingUser
        if (awaiting != null) {
            Text(awaiting.verificationUrl, Modifier.testTag("verification-url"))
            // Plain Text only: no text field/saver, selection container, clipboard or diagnostics.
            Text(awaiting.userCode.copyBytes().toString(Charsets.UTF_8), Modifier.testTag("device-code"))
            Button(onClick = openBrowser, modifier = Modifier.testTag("open-browser")) {
                Text(stringResource(R.string.gate_browser))
            }
        }
        Button(onClick = controller::connect, enabled = !state.busy, modifier = Modifier.testTag("connect")) {
            Text(stringResource(if (state.phase == ConnectionPhase.REAUTH_REQUIRED) R.string.gate_reauth else R.string.gate_connect))
        }
        OutlinedButton(onClick = { controller.readUsage() }, enabled = !state.busy && controller.session.snapshot() is SessionResult.Ready,
            modifier = Modifier.testTag("read-usage")) { Text(stringResource(R.string.gate_read)) }
        OutlinedButton(onClick = { controller.readUsage(refreshSession = true) },
            enabled = !state.busy && controller.session.snapshot() is SessionResult.Ready,
            modifier = Modifier.testTag("refresh-session")) { Text(stringResource(R.string.gate_refresh)) }
        OutlinedButton(onClick = controller::cancel,
            enabled = state.phase !in setOf(ConnectionPhase.RESTORING, ConnectionPhase.SIGNING_OUT),
            modifier = Modifier.testTag("cancel-connect")) {
            Text(stringResource(R.string.gate_cancel))
        }
        OutlinedButton(onClick = controller::signOut, enabled = state.phase != ConnectionPhase.SIGNING_OUT,
            modifier = Modifier.testTag("sign-out")) {
            Text(stringResource(R.string.gate_sign_out))
        }
        state.observations?.let { observations ->
            EndpointFacts(observations.usage)
            observations.usage.usage?.let { usage ->
                WindowFacts(stringResource(R.string.gate_five_hour), usage.fiveHour)
                WindowFacts(stringResource(R.string.gate_weekly), usage.weekly)
                Text(stringResource(R.string.gate_flags, fact(usage.allowed), fact(usage.limitReached)))
                Text(stringResource(R.string.gate_summary_count, fact(usage.bankedAvailableCount)))
            }
            EndpointFacts(observations.inventory)
            Text(stringResource(R.string.gate_inventory_count, observations.inventory.availableCount?.let(::fact) ?: "—"))
            observations.inventory.expiries.forEach { Text(stringResource(R.string.gate_expiry, fact(it))) }
        }
    }
}

@Composable
private fun EndpointFacts(observation: EndpointObservation) {
    Text(stringResource(R.string.gate_endpoint, observation.operation.name, observation.status?.toString() ?: "—",
        observation.observedAt?.toString() ?: "—", observation.error?.name ?: "—"))
}

@Composable
private fun WindowFacts(label: String, selection: WindowSelection) {
    Text(stringResource(R.string.gate_window_state, label, selection.state.name))
    selection.candidates.forEach { window ->
        Text(stringResource(R.string.gate_window, fact(window.durationSeconds), fact(window.usedPercent),
            fact(window.reset.absolute), fact(window.reset.relativeSeconds), window.reset.discrepant.toString()))
    }
}

private fun fact(field: Field<*>): String = field.value?.toString() ?: "${field.knowledge.name}/${field.reason?.name ?: "—"}"
