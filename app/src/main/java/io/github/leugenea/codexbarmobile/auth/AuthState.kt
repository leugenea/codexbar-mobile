package io.github.leugenea.codexbarmobile.auth

import io.github.leugenea.codexbarmobile.credentials.SensitiveValue
import io.github.leugenea.codexbarmobile.credentials.SessionGeneration

/** Categorical diagnostics only; no response, exception chain or inferred identity. */
enum class AuthStage { USERCODE, POLL, EXCHANGE, STORE }
enum class AuthFailure { MALFORMED, HTTP_STATUS, RATE_LIMITED, NETWORK, DEADLINE, TRANSPORT, STORAGE, STALE_OWNER }

sealed interface AuthState {
    data object Idle : AuthState
    data object RequestingCode : AuthState
    /** In-memory only except explicit owner-checked, sensitive-marked user copy; never saved state or logs. */
    class AwaitingUser(val userCode: SensitiveValue) : AuthState {
        val verificationUrl: String = AuthProtocol.VERIFICATION_URL
        override fun toString(): String = "AuthState.AwaitingUser(redacted)"
    }
    data object Exchanging : AuthState
    class Connected(val generation: SessionGeneration) : AuthState {
        override fun toString(): String = "AuthState.Connected(identity-unresolved, ttl-unknown)"
    }
    data class Failed(val stage: AuthStage, val category: AuthFailure) : AuthState
    data object Cancelled : AuthState
}

/** Internal control flow, never constructed with untrusted text or an exception cause. */
internal class AuthAbort(val category: AuthFailure) : RuntimeException(category.name, null, false, false)
