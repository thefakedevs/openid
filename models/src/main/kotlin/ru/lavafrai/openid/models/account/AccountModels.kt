package ru.lavafrai.openid.models.account

/** Safe to expose while the account credentials are locked. */
data class AccountSummary(val accountId: Long)

sealed interface AccountState {
    data object Loading : AccountState

    data object Unavailable : AccountState

    data object Empty : AccountState

    data class Locked(val summary: AccountSummary) : AccountState

    data class Unlocked(val summary: AccountSummary) : AccountState

    /** An older authenticated key must be opened once before moving to device-key storage. */
    data class MigrationRequired(val summary: AccountSummary) : AccountState
}

/**
 * A snapshot of decrypted account material, bound to the issuing repository and its storage generation.
 * Close, account replacement, migration and deletion revoke the lease.
 * Re-importing the same account ID also revokes it.
 * Implementations must reject revoked leases and make close idempotent.
 * Closing cannot erase an account object or immutable token strings already retained by the caller.
 */
interface AccountLease : AutoCloseable {
    /** Reading a closed or revoked lease must fail, even if the caller previously read its snapshot. */
    val account: ImportedAccount

    override fun close()
}
