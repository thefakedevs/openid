package ru.lavafrai.openid.models.pin

import ru.lavafrai.openid.models.errors.FailureDiagnostic
enum class PinProtection {
    NOT_SAVED,
    BIOMETRIC,
}

sealed interface SavedPinState {
    data object Loading : SavedPinState

    data class Unavailable(val diagnostic: FailureDiagnostic) : SavedPinState

    data object NotSaved : SavedPinState

    /** A saved PIN always requires strong biometric authentication. */
    data class Saved(val accountId: Long) : SavedPinState
}

/**
 * Bound to the issuing repository, account ID and stored PIN generation.
 * Close, PIN replacement and deletion revoke the lease.
 * Implementations must reject revoked leases, clear their owned PIN buffer and make close idempotent.
 */
interface PinLease : AutoCloseable {
    val accountId: Long

    /** Returns a caller-owned copy that the caller must clear after use. */
    fun copyPin(): CharArray

    override fun close()
}
