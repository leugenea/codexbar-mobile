package io.github.leugenea.codexbarmobile.credentials

internal fun protectedStore(persistence: ProtectedCredentialPersistence): CredentialStore =
    SerializedCredentialStore(persistence, persistence::activate)
