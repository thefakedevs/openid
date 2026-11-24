package ru.lavafrai.openid.domain.models

import kotlin.time.Duration
import ru.lavafrai.openid.domain.usecases.DigitalIdPreflightFailure
import ru.lavafrai.openid.models.auth.AuthenticationFailure
import ru.lavafrai.openid.models.digitalid.QrSession
import ru.lavafrai.openid.models.errors.FailureDiagnostic

enum class QrGenerationStage {
    UNLOCKING_ACCOUNT,
    CONNECTING_MAX,
    LOGGING_IN,
    RESOLVING_BOT,
    GETTING_LAUNCH,
    CHECKING_SECURITY,
    GETTING_PIN,
    AUTHORIZING,
    SAVING_PIN,
    GENERATING_QR,
    REFRESHING_QR,
}

sealed interface QrGenerationFailure {
    data class Authentication(val reason: AuthenticationFailure) : QrGenerationFailure

    data class Preflight(val reason: DigitalIdPreflightFailure) : QrGenerationFailure

    data object InvalidPin : QrGenerationFailure

    data object AuthorizationRejected : QrGenerationFailure

    data object CapturedTokenRejected : QrGenerationFailure

    data object AlreadyRunning : QrGenerationFailure

    data object OperationFailed : QrGenerationFailure
}

sealed interface QrGenerationState {
    data class Loading(val stage: QrGenerationStage) : QrGenerationState

    data class Ready(
        val qr: QrSession,
        val refreshInterval: Duration? = null,
    ) : QrGenerationState

    /** A recoverable request failure pauses the session until an explicit refresh request. */
    data class Failed(
        val reason: QrGenerationFailure,
        val diagnostic: FailureDiagnostic,
        val stage: QrGenerationStage,
        val canRetryInSession: Boolean = false,
    ) : QrGenerationState
}
