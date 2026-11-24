package ru.lavafrai.openid.domain.usecases

import ru.lavafrai.openid.domain.QrGenerationAlreadyRunningException
import ru.lavafrai.openid.domain.models.InvalidPinException
import ru.lavafrai.openid.domain.models.QrGenerationFailure
import ru.lavafrai.openid.domain.models.QrGenerationStage
import ru.lavafrai.openid.domain.models.QrGenerationState
import ru.lavafrai.openid.models.auth.AuthenticationException
import ru.lavafrai.openid.models.errors.DiagnosticFailure
import ru.lavafrai.openid.models.errors.FailureDiagnostic
import ru.lavafrai.openid.models.errors.FailureKind

internal class DigitalIdAuthorizationRejectedException : IllegalStateException("Digital ID authorization rejected")

internal class CapturedTokenRejectedException(cause: Throwable? = null) :
    IllegalStateException("Digital ID token rejected", cause)

internal suspend fun requireDigitalIdAuthorization(
    capturedTokenProfile: Boolean,
    authorize: suspend () -> Boolean,
) {
    val authorized = try {
        authorize()
    } catch (failure: Exception) {
        if (capturedTokenProfile &&
            (failure as? DiagnosticFailure)?.diagnostic?.code == "DIGITAL_ID_HTTP_REJECTED_400"
        ) {
            throw CapturedTokenRejectedException(failure)
        }
        throw failure
    }
    if (!authorized) {
        if (capturedTokenProfile) throw CapturedTokenRejectedException()
        throw DigitalIdAuthorizationRejectedException()
    }
}

internal fun Throwable.toQrFailure(stage: QrGenerationStage): QrGenerationState.Failed {
    val reason = when (this) {
        is AuthenticationException -> QrGenerationFailure.Authentication(reason)
        is DigitalIdPreflightException -> QrGenerationFailure.Preflight(reason)
        is InvalidPinException -> QrGenerationFailure.InvalidPin
        is DigitalIdAuthorizationRejectedException -> QrGenerationFailure.AuthorizationRejected
        is CapturedTokenRejectedException -> QrGenerationFailure.CapturedTokenRejected
        is QrGenerationAlreadyRunningException -> QrGenerationFailure.AlreadyRunning
        else -> QrGenerationFailure.OperationFailed
    }
    val diagnostic = when (this) {
        is DigitalIdPreflightException -> FailureDiagnostic.capture(
            "PREFLIGHT_${this.reason.name}",
            FailureKind.ACCESS_DENIED,
            this,
        )

        is DigitalIdAuthorizationRejectedException -> FailureDiagnostic.capture(
            "PIN_REJECTED",
            FailureKind.ACCESS_DENIED,
            this,
        )

        is CapturedTokenRejectedException -> FailureDiagnostic.capture(
            "DID_CAPTURED_TOKEN_REJECTED",
            FailureKind.ACCESS_DENIED,
            this,
        )

        is InvalidPinException -> FailureDiagnostic.capture("PIN_INVALID", FailureKind.INVALID_DATA, this)

        else -> FailureDiagnostic.from(this)
    }
    return QrGenerationState.Failed(reason, diagnostic, stage)
}
