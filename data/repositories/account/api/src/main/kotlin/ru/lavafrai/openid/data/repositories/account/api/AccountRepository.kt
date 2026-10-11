package ru.lavafrai.openid.data.repositories.account.api

import kotlinx.coroutines.flow.StateFlow
import ru.lavafrai.openid.models.account.AccountLease
import ru.lavafrai.openid.models.account.AccountState
import ru.lavafrai.openid.models.account.AccountSummary
import ru.lavafrai.openid.models.account.ImportedAccount
import ru.lavafrai.openid.models.auth.CryptoAuthenticator
import ru.lavafrai.openid.models.max.MaxAccessToken

/** Credentials are always encrypted with a device key, without per-use user authentication. */
interface AccountRepository {
    /** Unlocked while at least one valid lease exists; no credentials are exposed in this state. */
    val accountState: StateFlow<AccountState>

    suspend fun getSummary(): AccountSummary?

    /** Creates the single stored account; fails if one already exists. */
    suspend fun save(account: ImportedAccount)

    suspend fun unlock(): AccountLease

    /** Persists a rotated token only for a valid lease from the current storage generation. */
    suspend fun updateMaxToken(
        lease: AccountLease,
        token: MaxAccessToken,
    )

    /**
     * Atomically replaces an existing account, including same-ID re-imports, and revokes its leases.
     * Domain must clear the old saved PIN first. Failed writes leave the previous account intact.
     */
    suspend fun replace(account: ImportedAccount)

    /** One-time compatibility operation for old authenticated profiles. Never creates that mode. */
    suspend fun migrateLegacyProtection(authenticator: CryptoAuthenticator)

    /** Removes credentials and their owned keys, and revokes leases. Domain also clears PIN. */
    suspend fun delete()
}
