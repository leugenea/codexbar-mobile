package io.github.leugenea.codexbarmobile.credentials

internal fun protectedStore(persistence: ProtectedCredentialPersistence): CredentialStore {
    val store = SerializedCredentialStore(persistence, persistence::activate)
    return object : CredentialStore by store {
        override fun beginRotation(generation: SessionGeneration): CredentialResult<Unit> {
            val readable = store.read(generation)
            if (readable is CredentialResult.Failure) return readable
            return persistence.rotation(generation, true)
        }
        override fun finishRotation(generation: SessionGeneration) = persistence.rotation(generation, false)
    }
}
