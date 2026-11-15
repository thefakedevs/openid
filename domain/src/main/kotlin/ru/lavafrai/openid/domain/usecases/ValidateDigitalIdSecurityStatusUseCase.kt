package ru.lavafrai.openid.domain.usecases

import ru.lavafrai.openid.models.account.ImportedDigitalId
import ru.lavafrai.openid.models.digitalid.SecurityStatus

enum class DigitalIdPreflightFailure {
    UNSUPPORTED_PROFILE,
    DEVICE_MISMATCH,
    TIMESTAMP_MISMATCH,
    ATTEMPTS_EXHAUSTED,
    COOLDOWN,
    PIN_SALT_MISSING,
}

class DigitalIdPreflightException(val reason: DigitalIdPreflightFailure) : IllegalStateException(reason.name)

/** Run before requesting a manual PIN or sending an authorization attempt. */
class ValidateDigitalIdSecurityStatusUseCase {
    operator fun invoke(
        account: ImportedDigitalId,
        status: SecurityStatus,
    ) {
        check(
            status.tokenLevel == "lite" || status.tokenLevel == "high",
            DigitalIdPreflightFailure.UNSUPPORTED_PROFILE,
        )
        // Captured-token profiles trust the server values; PIN profiles must match them exactly.
        account.biometricDeviceId?.let { check(status.deviceId == it, DigitalIdPreflightFailure.DEVICE_MISMATCH) }
        account.creationTimestamp?.let {
            check(status.timestamp.matches(it), DigitalIdPreflightFailure.TIMESTAMP_MISMATCH)
        }
        val attemptsLeft = status.attemptsLeft
        if (!account.tokenProfile) {
            check(attemptsLeft != null, DigitalIdPreflightFailure.ATTEMPTS_EXHAUSTED)
        }
        check(attemptsLeft == null || attemptsLeft > 0, DigitalIdPreflightFailure.ATTEMPTS_EXHAUSTED)
        val cooldown = status.cooldown
        check(
            cooldown == null || (cooldown.isFinite() && cooldown <= 0),
            DigitalIdPreflightFailure.COOLDOWN,
        )
        if (account.biometricToken == null) {
            check(!account.pinSalt.isNullOrEmpty(), DigitalIdPreflightFailure.PIN_SALT_MISSING)
        }
    }

    private fun check(
        condition: Boolean,
        reason: DigitalIdPreflightFailure,
    ) {
        if (!condition) throw DigitalIdPreflightException(reason)
    }
}
