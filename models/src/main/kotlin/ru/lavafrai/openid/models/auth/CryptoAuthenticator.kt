package ru.lavafrai.openid.models.auth

import javax.crypto.Cipher

/**
 * Authenticates the exact cipher prepared by the repository.
 * The UI must return only after successful authentication and propagate cancellation or failure.
 * The repository owns the cipher and must complete the operation before committing a change.
 */
fun interface CryptoAuthenticator {
    suspend fun authenticate(cipher: Cipher)
}
