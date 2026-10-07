package io.github.leugenea.codexbarmobile.credentials

/** Local isolation only. Neither this namespace nor a generation proves provider identity. */
class LocalCredentialNamespace internal constructor() {
    override fun toString(): String = "LocalCredentialNamespace(local-only)"
}

/** A fresh, identity-compared capability per local session; never reuse after invalidation. */
class SessionGeneration internal constructor(val namespace: LocalCredentialNamespace) {
    override fun toString(): String = "SessionGeneration(local-only)"
}

/** A8 must establish authoritative selected-flow account/workspace evidence before A10. */
sealed interface AccountWorkspaceBinding {
    data object Unresolved : AccountWorkspaceBinding
}

/**
 * One immutable, complete replacement unit, never a token patch. A null refresh token
 * explicitly means absent, not "keep the previous token". No inferred identity or TTL.
 * A6 owns authenticated serialization/versioning and pending-binding restoration policy.
 */
class CredentialEnvelope(
    val generation: SessionGeneration,
    val accessToken: SensitiveValue,
    val refreshToken: SensitiveValue?,
) {
    val accountWorkspace: AccountWorkspaceBinding = AccountWorkspaceBinding.Unresolved
    override fun toString(): String = "CredentialEnvelope(redacted, accountWorkspace=unresolved)"
}
