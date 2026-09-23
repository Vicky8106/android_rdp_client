package com.freerdp.feature.session.security

interface CredentialStore {
    fun saveSecret(profileId: String, secret: CharArray)
    fun getSecret(profileId: String): CharArray?
    fun deleteSecret(profileId: String)
    fun clearAll()

    // Convenience compatibility methods returning Result
    fun saveCredential(profileId: String, secret: CharArray): Result<Unit> = runCatching {
        saveSecret(profileId, secret)
    }

    fun getCredential(profileId: String): Result<CharArray?> = runCatching {
        getSecret(profileId)
    }

    fun deleteCredential(profileId: String): Result<Unit> = runCatching {
        deleteSecret(profileId)
    }
}
