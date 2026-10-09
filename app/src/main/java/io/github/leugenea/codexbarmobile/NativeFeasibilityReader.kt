package io.github.leugenea.codexbarmobile

import io.github.leugenea.codexbarmobile.auth.AuthTransport
import io.github.leugenea.codexbarmobile.auth.await
import io.github.leugenea.codexbarmobile.credentials.SensitiveValue
import io.github.leugenea.codexbarmobile.transport.*
import io.github.leugenea.codexbarmobile.usage.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.time.Instant

/** Full decoded observations are in memory only; diagnostics never expose provider text. */
internal class EndpointObservation(
    val operation: ReadOperation,
    val status: Int? = null,
    val observedAt: Instant? = null,
    val error: ReadError? = null,
    val usage: UsageObservation? = null,
    val inventory: BankedResetObservation? = null,
    val notBeforeMillis: Long? = null,
    val receivedAtMillis: Long? = null,
) {
    // Keep the A8 screen's allowlisted facts without maintaining a second JSON parser.
    val availableCount: Field<Long>? get() = inventory?.reportedAvailableCount
    val expiries: List<Field<Instant>> get() = inventory?.items.orEmpty().map {
        it.value?.expiresAt ?: Field(it.knowledge, reason = it.reason)
    }
    override fun toString(): String = "EndpointObservation(operation=$operation, status=$status, observedAt=$observedAt, error=$error, payload=redacted)"
}
internal data class FeasibilityObservations(val usage: EndpointObservation, val inventory: EndpointObservation) {
    val successful: Boolean get() = usage.error == null && inventory.error == null
}

/** A2 owns read policy, A4 owns I/O/JSON syntax, A9 decodes through A1 validation. */
internal class NativeFeasibilityReader(
    private val transport: AuthTransport,
    internal val clock: TransportClock = SystemTransportClock,
    private val pause: suspend (Long) -> Unit = { delay(it) },
) {
    private val policy = ReadPolicy(clock, ReadBackoff { retry -> retry * 1_000L })

    fun session(store: io.github.leugenea.codexbarmobile.credentials.CredentialStore, scope: kotlinx.coroutines.CoroutineScope,
        storageDispatcher: kotlinx.coroutines.CoroutineDispatcher = kotlinx.coroutines.Dispatchers.IO,
        invalidated: () -> Unit = {}, removing: () -> Unit = {}) =
        SessionCoordinator(store, transport, scope, clock, storageDispatcher, invalidated, removing)

    suspend fun read(bearer: SensitiveValue): FeasibilityObservations {
        val usage = endpoint(ReadOperation.USAGE, bearer)
        return FeasibilityObservations(usage, endpoint(ReadOperation.RESET_INVENTORY, bearer, usage.usage))
    }

    internal suspend fun endpoint(
        operation: ReadOperation, bearer: SensitiveValue, usage: UsageObservation? = null,
        session: SessionCoordinator? = null, envelope: io.github.leugenea.codexbarmobile.credentials.CredentialEnvelope? = null,
        alreadyRefreshed: Boolean = false, admissionDeferred: (Long) -> Unit = {},
    ): EndpointObservation = withTimeoutOrNull(ReadDeadline.MAX_DURATION_MILLIS) {
        boundedEndpoint(operation, bearer, usage, session, envelope, alreadyRefreshed, admissionDeferred)
    } ?: EndpointObservation(operation, error = ReadError.DEADLINE_EXCEEDED)

    private suspend fun boundedEndpoint(
        operation: ReadOperation, bearer: SensitiveValue, usage: UsageObservation?,
        session: SessionCoordinator?, envelope: io.github.leugenea.codexbarmobile.credentials.CredentialEnvelope?,
        alreadyRefreshed: Boolean, admissionDeferred: (Long) -> Unit,
    ): EndpointObservation {
        var credentials = envelope
        var token = bearer
        val request = ReadRequest(operation, ReadDeadline.after(clock.now()))
        var attempt = ReadAttempt(refreshRequested = alreadyRefreshed)
        try {
            while (true) {
                currentCoroutineContext().ensureActive()
                val result = request(ProviderHttpRequest.Get(url(operation), token), request.deadline, session, credentials)
                currentCoroutineContext().ensureActive()
                val receivedAt = clock.now()
                val decision = policy.evaluate(request, attempt, result)
                // Preserve A2's boundary on the owner lane before a cancellable retry wait.
                decision.notBeforeMillis?.let(admissionDeferred)
                val status = (result as? TransportResult.Response)?.status
                when (decision.action) {
                    ReadAction.SUCCEED -> return project(operation, result as TransportResult.Response, receivedAt, usage)
                    ReadAction.RETRY_AT -> {
                        attempt = decision.next
                        pause((requireNotNull(decision.notBeforeMillis) - clock.now().monotonicMillis).coerceAtLeast(0))
                    }
                    ReadAction.SERIALIZED_REFRESH_AND_RETRY -> {
                        when (val refreshed = refresh(session, credentials, request.deadline)) {
                            is SessionResult.Ready -> { credentials = refreshed.envelope; token = credentials.accessToken; attempt = decision.next }
                            is SessionResult.Failed -> return EndpointObservation(operation, status, error = refreshed.problem.readError())
                        }
                    }
                    else -> {
                        return finished(operation, status, decision.error, session, credentials, decision.notBeforeMillis)
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return EndpointObservation(operation, error = ReadError.INVALID_RESPONSE)
        }
    }

    private suspend fun request(
        request: ProviderHttpRequest.Get, deadline: ReadDeadline, session: SessionCoordinator?,
        credentials: io.github.leugenea.codexbarmobile.credentials.CredentialEnvelope?,
    ): TransportResult {
        if (!valid(session, credentials)) return TransportResult.Failure(TransportFailure.CANCELLED)
        val result = if (session == null || credentials == null) transport.await(request, deadline)
            else session.request(credentials, request, deadline)
        return if (valid(session, credentials)) result else TransportResult.Failure(TransportFailure.CANCELLED)
    }

    private fun valid(session: SessionCoordinator?, credentials: io.github.leugenea.codexbarmobile.credentials.CredentialEnvelope?) =
        session == null || (credentials != null && session.accepts(credentials))

    private suspend fun refresh(
        session: SessionCoordinator?, credentials: io.github.leugenea.codexbarmobile.credentials.CredentialEnvelope?, deadline: ReadDeadline,
    ): SessionResult = if (session == null || credentials == null) SessionResult.Failed(SessionProblem.REAUTHORIZE)
        else session.refresh(credentials, deadline)

    private suspend fun finished(
        operation: ReadOperation, status: Int?, error: ReadError?, session: SessionCoordinator?,
        credentials: io.github.leugenea.codexbarmobile.credentials.CredentialEnvelope?, notBeforeMillis: Long?,
    ): EndpointObservation {
        if (error == ReadError.REAUTHORIZE && credentials != null) session?.requireReauthorization(credentials)
        return EndpointObservation(operation, status, error = error, notBeforeMillis = notBeforeMillis)
    }

    private fun project(
        operation: ReadOperation, response: TransportResult.Response, observed: TransportTime, usage: UsageObservation?,
    ): EndpointObservation = when (operation) {
        ReadOperation.USAGE -> decoded(operation, response.status,
            UsageResponseParser.parse(response.body, observed.wall, observed.wall)) { observation ->
            EndpointObservation(operation, response.status, observation.observedAt, usage = observation, receivedAtMillis = observed.monotonicMillis)
        }
        ReadOperation.RESET_INVENTORY -> decoded(operation, response.status,
            BankedResetResponseParser.parse(response.body, usage, observed.wall, observed.wall)) { observation ->
            EndpointObservation(operation, response.status, observation.observedAt, inventory = observation, receivedAtMillis = observed.monotonicMillis)
        }
    }

    private fun <T> decoded(
        operation: ReadOperation, status: Int, result: PayloadResult<T>, project: (T) -> EndpointObservation,
    ): EndpointObservation = when (result) {
        is PayloadResult.Decoded -> project(result.observation)
        is PayloadResult.Failure -> EndpointObservation(operation, status, error = ReadError.INVALID_RESPONSE)
    }

    internal companion object {
        fun url(operation: ReadOperation) = ("https://chatgpt.com" + operation.path).toHttpUrl()
    }
}
