package io.github.leugenea.codexbarmobile.history

import io.github.leugenea.codexbarmobile.*
import io.github.leugenea.codexbarmobile.auth.*
import io.github.leugenea.codexbarmobile.credentials.*
import io.github.leugenea.codexbarmobile.transport.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlin.coroutines.CoroutineContext
import java.time.Instant

/** Original synthetic integrated owner/reader/reducer fixture, with no Android stub execution. */
@OptIn(ExperimentalCoroutinesApi::class)
internal class RecordingFixture(val test: TestScope, capacity: Int = 16) {
    private val dispatcher = StandardTestDispatcher(test.testScheduler)
    val storage = RecordingDispatcher(dispatcher)
    val journal = LifetimeJournal()
    val fake = AuthFake()
    var usage = SyntheticAuth.response(USAGE)
    var inventory = SyntheticAuth.response("""{"available_count":0,"credits":[]}""")
    var heldPath: String? = null
    var wallOffset = 0L
    private var bound: SessionGeneration? = null
    private val persistence = FakeCredentialPersistence()
    private val rebound = object : CredentialPersistence by persistence {
        override fun read(): CredentialResult<CredentialEnvelope> = persistence.durable?.let {
            CredentialResult.Success(CredentialEnvelope(requireNotNull(bound), it.accessToken, it.refreshToken))
        } ?: CredentialResult.Failure(CredentialFailure.MISSING)
    }
    val credentials = SerializedCredentialStore(rebound, activate = { bound = it })
    private val clock = TransportClock {
        TransportTime(Instant.ofEpochSecond(1_800_000_000).plusMillis(test.currentTime + wallOffset), test.currentTime)
    }
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val observer = Any()
    init {
        persistence.durable = syntheticEnvelope(credentials.openSession())
        fake.respond = { call ->
            val path = call.request.url.encodedPath
            if (path != heldPath) call.reply(when (path) {
                ReadOperation.USAGE.path -> usage
                ReadOperation.RESET_INVENTORY.path -> inventory
                "/api/accounts/deviceauth/usercode" -> SyntheticAuth.response(SyntheticAuth.DEVICE)
                "/api/accounts/deviceauth/token" -> SyntheticAuth.response(SyntheticAuth.AUTHORIZATION)
                else -> SyntheticAuth.response(SyntheticAuth.TOKENS)
            })
        }
    }
    val owner = ConnectionController(credentials, DeviceCodeAuthenticator(fake, credentials, clock, storageDispatcher = dispatcher),
        NativeFeasibilityReader(fake, clock), scope, mutationDispatcher = dispatcher,
        storageDispatcher = storage, history = HistoryLifetimeCoordinator(journal.storage()), historyQueueCapacity = capacity)
    val snapshot get() = owner.historySnapshots.value
    val gets get() = fake.calls.filter { it.request is ProviderHttpRequest.Get }
    fun settle() = test.runCurrent()
    fun visible(value: Boolean) { owner.usageForeground(observer, value); settle() }
    fun read() { owner.readUsage(); settle() }
    fun advance(millis: Long) { test.advanceTimeBy(millis); settle() }
    fun finish() { storage.release(); owner.close(); settle() }
    fun points() = journal.entries.flatMap { it.windows }.mapNotNull { it.point }
    companion object {
        const val USAGE = """{"rate_limit":{"primary_window":{"limit_window_seconds":18000,"used_percent":12.375,"reset_at":1800003600}}}"""
    }
}

/** Positive held dispatch boundary; never sleeps or chooses a thread by name. */
internal class RecordingDispatcher(private val delegate: CoroutineDispatcher) : CoroutineDispatcher() {
    var held = false
    private val queued = ArrayDeque<Pair<CoroutineContext, Runnable>>()
    val pending get() = queued.size
    override fun dispatch(context: CoroutineContext, block: Runnable) {
        if (held) queued.addLast(context to block) else delegate.dispatch(context, block)
    }
    fun release() {
        held = false
        while (queued.isNotEmpty()) {
            val (context, block) = queued.removeFirst()
            delegate.dispatch(context, block)
        }
    }
}
