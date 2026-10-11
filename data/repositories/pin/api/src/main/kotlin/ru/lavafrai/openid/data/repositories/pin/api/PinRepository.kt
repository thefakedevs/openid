package ru.lavafrai.openid.data.repositories.pin.api

import kotlinx.coroutines.flow.StateFlow
import ru.lavafrai.openid.models.auth.CryptoAuthenticator
import ru.lavafrai.openid.models.pin.PinLease
import ru.lavafrai.openid.models.pin.SavedPinState

/** Every saved PIN requires strong biometrics; device credentials cannot substitute. */
interface PinRepository {
    val pinState: StateFlow<SavedPinState>

    /**
     * Saves or replaces the single account-bound PIN after biometric confirmation.
     * PINs must contain four ASCII digits. The caller remains responsible for clearing its buffer.
     */
    suspend fun save(
        accountId: Long,
        pin: CharArray,
        authenticator: CryptoAuthenticator,
    )

    suspend fun unlock(
        accountId: Long,
        authenticator: CryptoAuthenticator,
    ): PinLease

    /** Idempotently deletes only this account's PIN and its owned keys, and revokes its leases. */
    suspend fun delete(accountId: Long)
}
