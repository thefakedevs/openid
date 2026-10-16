package ru.lavafrai.openid.models.auth

import ru.lavafrai.openid.models.errors.DiagnosticFailure
import ru.lavafrai.openid.models.errors.FailureDiagnostic
import ru.lavafrai.openid.models.errors.FailureKind
import ru.lavafrai.openid.models.errors.FailureReason
/** Safe failure codes shared by storage, the domain flow and the authentication UI. */
enum class AuthenticationFailure {
    NO_SCREEN_LOCK,
    NO_BIOMETRIC_HARDWARE,
    NOT_ENROLLED,
    TEMPORARILY_UNAVAILABLE,
    LOCKED_OUT,
    SECURITY_UPDATE_REQUIRED,
    UNSUPPORTED,
    KEY_INVALIDATED,
    NOT_AUTHENTICATED,
    FAILED,
}

class AuthenticationException(
    val reason: AuthenticationFailure,
    failure: Throwable? = null,
    details: FailureDiagnostic? = null,
) : IllegalStateException("Authentication failed: $reason"),
    DiagnosticFailure {
    override val diagnostic =
        details ?: FailureDiagnostic.capture(
            "AUTH_${reason.name}",
            FailureKind.STORAGE,
            failure ?: this,
            if (reason == AuthenticationFailure.KEY_INVALIDATED) {
                FailureReason.BIOMETRIC_KEY_INVALIDATED
            } else {
                FailureReason.UNSPECIFIED
            },
        )
}
