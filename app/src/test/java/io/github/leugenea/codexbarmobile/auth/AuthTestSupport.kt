package io.github.leugenea.codexbarmobile.auth

import io.github.leugenea.codexbarmobile.credentials.*
import io.github.leugenea.codexbarmobile.transport.*
import kotlinx.coroutines.CompletableDeferred
import java.time.Instant

/** Original synthetic protocol fixtures, NOT captures, credentials or upstream copied fixtures. */
internal object SyntheticAuth {
    const val DEVICE = """{"device_auth_id":"synthetic-device-id","user_code":"SYNTHETIC-CODE"}"""
    const val AUTHORIZATION = """{"authorization_code":"synthetic-authorization","code_verifier":"synthetic-verifier"}"""
    const val TOKENS = """{"access_token":"synthetic-access","refresh_token":"synthetic-refresh"}"""
    val secrets = listOf("synthetic-device-id", "SYNTHETIC-CODE", "synthetic-authorization", "synthetic-verifier", "synthetic-access", "synthetic-refresh")
    fun response(text: String, status: Int = 200, retry: RetryAfter = RetryAfter.Missing) =
        TransportResult.Response.bounded(status, text.toByteArray(), retry)
    fun body(text: String) = (response(text) as TransportResult.Response).body
}

internal class AuthClock : TransportClock {
    var millis = 0L
    val sleeps = mutableListOf<Long>()
    var gate: CompletableDeferred<Unit>? = null
    var onSleep: (() -> Unit)? = null
    override fun now() = TransportTime(Instant.parse("2026-01-01T00:00:00Z").plusMillis(millis), millis)
    suspend fun pause(duration: Long) {
        sleeps += duration
        onSleep?.invoke()
        gate?.await()
        millis += duration
    }
}

internal class AuthFake : AuthTransport {
    class Call(val request: ProviderHttpRequest, val deadline: ReadDeadline, val terminal: (TransportResult) -> Unit) {
        var cancelled = false
        fun reply(result: TransportResult) = terminal(result)
    }
    val calls = mutableListOf<Call>()
    var respond: (Call) -> Unit = {}
    override fun execute(request: ProviderHttpRequest, deadline: ReadDeadline, terminal: (TransportResult) -> Unit): CancellationHandle {
        val call = Call(request, deadline, terminal)
        calls += call
        respond(call)
        return CancellationHandle { call.cancelled = true }
    }
    fun success() {
        respond = { call -> call.reply(SyntheticAuth.response(when (call.request.url.encodedPath) {
            "/api/accounts/deviceauth/usercode" -> SyntheticAuth.DEVICE
            "/api/accounts/deviceauth/token" -> SyntheticAuth.AUTHORIZATION
            else -> SyntheticAuth.TOKENS
        })) }
    }
}

internal class AuthPersistence : CredentialPersistence {
    var saved: CredentialEnvelope? = null
    var commits = 0
    var discards = 0
    var beforePrepare: (() -> Unit)? = null
    var failure: CredentialFailure? = null
    override fun read(): CredentialResult<CredentialEnvelope> = saved?.let { CredentialResult.Success(it) }
        ?: CredentialResult.Failure(CredentialFailure.MISSING)
    override fun prepare(envelope: CredentialEnvelope): CredentialResult<PreparedCredentialWrite> {
        beforePrepare?.invoke()
        failure?.let { return CredentialResult.Failure(it) }
        return CredentialResult.Success(object : PreparedCredentialWrite {
            override fun commit(): CredentialResult<Unit> {
                saved = envelope
                commits++
                return CredentialResult.Success(Unit)
            }
            override fun discard() { discards++ }
        })
    }
    override fun delete(): CredentialResult<Unit> { saved = null; return CredentialResult.Success(Unit) }
}
