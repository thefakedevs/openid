package ru.lavafrai.openid.models.account

import ru.lavafrai.openid.models.errors.FailureDiagnostic
/**
 * Safe to expose while the account credentials are locked. [tokenProfile] mirrors the stored
 * account's [ImportedDigitalId.tokenProfile] and is the authoritative source of the profile kind
 * for both UI routing and domain access checks; it is written atomically with the account, so it
 * never drifts out of sync with the saved credentials.
 */
data class AccountSummary(
    val accountId: Long,
    val tokenProfile: Boolean = false,
)

sealed interface AccountState {
    data object Loading : AccountState

    data class Unavailable(val diagnostic: FailureDiagnostic) : AccountState

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
