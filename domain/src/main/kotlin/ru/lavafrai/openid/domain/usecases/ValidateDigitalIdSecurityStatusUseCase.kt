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

/** Run before requesting the PIN or sending an authorization attempt. */
class ValidateDigitalIdSecurityStatusUseCase {
    operator fun invoke(
        account: ImportedDigitalId,
        status: SecurityStatus,
    ) {
        check(
            status.tokenLevel == "lite" || status.tokenLevel == "high",
            DigitalIdPreflightFailure.UNSUPPORTED_PROFILE,
        )
        check(status.deviceId == account.biometricDeviceId, DigitalIdPreflightFailure.DEVICE_MISMATCH)
        check(status.timestamp.matches(account.creationTimestamp), DigitalIdPreflightFailure.TIMESTAMP_MISMATCH)
        check(status.attemptsLeft > 0, DigitalIdPreflightFailure.ATTEMPTS_EXHAUSTED)
        val cooldown = status.cooldown
        check(
            cooldown == null || (cooldown.isFinite() && cooldown <= 0),
            DigitalIdPreflightFailure.COOLDOWN,
        )
        check(!account.pinSalt.isNullOrEmpty(), DigitalIdPreflightFailure.PIN_SALT_MISSING)
    }

    private fun check(
        condition: Boolean,
        reason: DigitalIdPreflightFailure,
    ) {
        if (!condition) throw DigitalIdPreflightException(reason)
    }
}
