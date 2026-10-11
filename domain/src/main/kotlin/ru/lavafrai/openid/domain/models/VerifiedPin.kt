package ru.lavafrai.openid.domain.models

import ru.lavafrai.openid.models.account.AccountLease
import ru.lavafrai.openid.models.account.ImportedAccount

/**
 * Issued only after successful Digital ID authorization. Valid during onPinVerified only.
 * The callback may save it but must not retain it; it is closed before requesting the first QR.
 */
class VerifiedPin internal constructor(
    lease: AccountLease,
    account: ImportedAccount,
    private val pin: UnlockedPin,
) : AutoCloseable {
    val accountId: Long = account.accountId
    private var boundLease: AccountLease? = lease
    private var snapshot: ImportedAccount? = account

    @Synchronized
    internal fun copyPin(): CharArray {
        val lease = checkNotNull(boundLease) { "Verified PIN is closed" }
        check(lease.account === snapshot) { "Verified PIN belongs to an outdated account lease" }
        return pin.copyPin()
    }

    @Synchronized
    override fun close() {
        pin.close()
        boundLease = null
        snapshot = null
    }

    override fun toString(): String = "VerifiedPin(accountId=$accountId, pin=<redacted>)"
}
