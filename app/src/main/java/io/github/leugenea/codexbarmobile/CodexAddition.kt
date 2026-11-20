package io.github.leugenea.codexbarmobile

import io.github.leugenea.codexbarmobile.auth.AuthState
import io.github.leugenea.codexbarmobile.credentials.CredentialEnvelope
import io.github.leugenea.codexbarmobile.history.HistoryPartition
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

internal sealed interface AdditionResult {
    data class Added(val id: UUID) : AdditionResult
    enum class Rejected : AdditionResult { CANCELLED, BROWSER, AUTH, STORAGE, CONFLICT }
}

/** Internal caller authority, deliberately not a ConnectionController or a saved entry handle. */
internal class CodexAddition(private val owner: AccountRuntimeOwner) {
    private val progress = MutableStateFlow<AuthState>(AuthState.Idle)
    val auth = progress.asStateFlow()
    private val completion = CompletableDeferred<AdditionResult>()

    suspend fun result() = completion.await()
    suspend fun cancel() = owner.cancelAddition(this)
    suspend fun retryDurability() = owner.recoverAddition(this)
    suspend fun browserFailed(expected: AuthState.AwaitingUser) = owner.cancelAddition(this, expected)
    suspend fun verificationUrl(expected: AuthState.AwaitingUser) = owner.additionUrl(this, expected)

    internal fun report(auth: AuthState) { progress.value = auth }
    internal fun complete(result: AdditionResult) {
        if (progress.value !is AuthState.Failed) progress.value = AuthState.Idle
        completion.complete(result)
    }
}

internal sealed interface PreparedAddition {
    data class Ready(val auth: AuthState.Connected, val envelope: CredentialEnvelope,
        val partition: HistoryPartition) : PreparedAddition
    data object AuthFailure : PreparedAddition
    data object StorageFailure : PreparedAddition
}
