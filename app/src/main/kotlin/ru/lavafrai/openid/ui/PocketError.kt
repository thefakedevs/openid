package ru.lavafrai.openid.ui

import android.content.Context
import androidx.annotation.StringRes
import ru.lavafrai.openid.R
import ru.lavafrai.openid.domain.models.QrGenerationFailure
import ru.lavafrai.openid.domain.models.QrGenerationStage
import ru.lavafrai.openid.domain.models.QrGenerationState
import ru.lavafrai.openid.domain.usecases.DigitalIdPreflightFailure
import ru.lavafrai.openid.models.auth.AuthenticationException
import ru.lavafrai.openid.models.errors.FailureDiagnostic
import ru.lavafrai.openid.models.errors.FailureKind
import ru.lavafrai.openid.models.errors.FailureReason

enum class ErrorOperation(@StringRes val labelRes: Int) {
    STARTUP(R.string.operation_startup),
    IMPORT(R.string.operation_import),
    ROOT_IMPORT(R.string.operation_root_import),
    UNLOCK(R.string.operation_unlock),
    QR(R.string.operation_qr),
    SETTINGS(R.string.operation_settings),
    DELETE(R.string.operation_delete),
    MIGRATION(R.string.operation_migration),
    RENDER_QR(R.string.operation_render_qr),
}

enum class ErrorRecovery(@StringRes val labelRes: Int) {
    STARTUP(R.string.action_retry),
    EDIT_IMPORT(R.string.recovery_edit_import),
    IMPORT(R.string.recovery_import),
    ROOT_IMPORT(R.string.recovery_root_import),
    BIOMETRIC(R.string.recovery_biometric),
    UNLOCK(R.string.recovery_unlock),
    QR(R.string.recovery_qr),
    SETTINGS(R.string.recovery_settings),
    DELETE(R.string.recovery_delete),
    MIGRATION(R.string.recovery_migration),
    REPLACE(R.string.profile_replace),
}

data class PocketError(
    val title: UiText,
    val description: UiText,
    val diagnostic: FailureDiagnostic,
    val operation: ErrorOperation,
    val recovery: ErrorRecovery? = null,
    val stage: QrGenerationStage? = null,
) {
    fun report(context: Context): String = buildString {
        appendLine(context.getString(R.string.diagnostic_clip_label, context.getString(R.string.app_name)))
        appendLine(context.getString(R.string.diagnostic_operation, context.getString(operation.labelRes)))
        stage?.let { appendLine(context.getString(R.string.diagnostic_stage, it.name)) }
        appendLine(context.getString(R.string.diagnostic_code, diagnostic.code))
        appendLine(context.getString(R.string.diagnostic_kind, diagnostic.kind))
        appendLine(context.getString(R.string.diagnostic_redaction_notice))
        appendLine()
        append(diagnostic.trace)
    }
}

fun Throwable.toPocketError(
    operation: ErrorOperation,
    recovery: ErrorRecovery? = null,
): PocketError {
    val error = FailureDiagnostic.from(this).toPocketError(operation, recovery)
    return if (this is AuthenticationException) {
        error.copy(title = UiText(R.string.error_auth_title), description = reason.message())
    } else {
        error
    }
}

fun FailureDiagnostic.toPocketError(
    operation: ErrorOperation,
    recovery: ErrorRecovery? = null,
): PocketError {
    val (title, description) = when {
        reason == FailureReason.IMPORT_DECRYPTION_FAILED ->
            UiText(R.string.error_decryption_title) to UiText(R.string.error_decryption_description)

        reason == FailureReason.INVALID_IMPORT_PASSWORD ->
            UiText(R.string.error_password_title) to UiText(R.string.error_password_description)

        reason == FailureReason.INVALID_IMPORT_LINK ->
            UiText(R.string.error_import_link_title) to UiText(R.string.error_import_link_description)

        operation == ErrorOperation.ROOT_IMPORT && kind == FailureKind.INVALID_DATA ->
            rootImportMessage(code)

        reason == FailureReason.STORAGE_KEY_MISSING ->
            UiText(R.string.error_storage_key_title) to
                UiText(R.string.error_storage_key_description)

        kind == FailureKind.NETWORK ->
            UiText(R.string.error_network_title) to UiText(R.string.error_network_description)

        kind == FailureKind.TIMEOUT ->
            UiText(R.string.error_timeout_title) to UiText(R.string.error_timeout_description)

        kind == FailureKind.TLS ->
            UiText(R.string.error_tls_title) to UiText(R.string.error_tls_description)

        kind == FailureKind.RATE_LIMIT ->
            UiText(R.string.error_rate_limit_title) to UiText(R.string.error_rate_limit_description)

        kind == FailureKind.ACCESS_DENIED ->
            UiText(R.string.error_access_denied_title) to UiText(R.string.error_access_denied_description)

        kind == FailureKind.SERVER ->
            UiText(R.string.error_server_title) to UiText(R.string.error_server_description)

        kind == FailureKind.STORAGE || operation == ErrorOperation.STARTUP ->
            UiText(R.string.error_storage_title) to
                UiText(R.string.error_storage_description)

        operation == ErrorOperation.RENDER_QR ->
            UiText(R.string.error_render_qr_title) to UiText(R.string.error_render_qr_description)

        kind == FailureKind.INVALID_DATA && operation == ErrorOperation.IMPORT ->
            UiText(R.string.error_import_data_title) to
                UiText(R.string.error_import_data_description)

        kind == FailureKind.INVALID_DATA ->
            UiText(R.string.error_response_title) to
                UiText(R.string.error_response_description)

        else ->
            when (operation) {
                ErrorOperation.IMPORT -> UiText(R.string.error_import_title)
                ErrorOperation.ROOT_IMPORT -> UiText(R.string.error_root_import_title)
                ErrorOperation.SETTINGS -> UiText(R.string.error_settings_title)
                ErrorOperation.DELETE -> UiText(R.string.error_delete_title)
                ErrorOperation.MIGRATION -> UiText(R.string.error_migration_title)
                ErrorOperation.UNLOCK -> UiText(R.string.error_unlock_title)
                ErrorOperation.QR, ErrorOperation.RENDER_QR -> UiText(R.string.error_qr_title)
                ErrorOperation.STARTUP -> UiText(R.string.error_startup_title)
            } to
                UiText(R.string.error_generic_description)
    }
    val action = if (operation == ErrorOperation.IMPORT && kind == FailureKind.INVALID_DATA) {
        ErrorRecovery.EDIT_IMPORT
    } else {
        recovery
    }
    return PocketError(title, description, this, operation, action)
}

/** Auto-import failures map to stable diagnostic codes instead of raw exception messages. */
private fun rootImportMessage(code: String): Pair<UiText, UiText> = when (code) {
    "ROOT_ACCESS_DENIED" ->
        UiText(R.string.error_root_denied_title) to UiText(R.string.error_root_denied_description)

    "MAX_APP_MISSING" ->
        UiText(R.string.error_max_missing_title) to UiText(R.string.error_max_missing_description)

    "DID_MATERIAL_NOT_FOUND" ->
        UiText(R.string.error_did_material_title) to UiText(R.string.error_did_material_description)

    "MAX_IMPORT_CHANGED", "MAX_SESSION_AMBIGUOUS" ->
        UiText(R.string.error_root_changed_title) to UiText(R.string.error_root_changed_description)

    "MAX_PREFERENCES_MISSING", "MAX_ACCOUNT_ID_MISSING" ->
        UiText(R.string.error_root_prefs_title) to UiText(R.string.error_root_prefs_description)

    else -> UiText(R.string.error_root_import_title) to UiText(R.string.error_generic_description)
}

fun QrGenerationState.Failed.toPocketError(recovery: ErrorRecovery?): PocketError {
    val operation = if (stage in setOf(QrGenerationStage.GENERATING_QR, QrGenerationStage.REFRESHING_QR)) {
        ErrorOperation.QR
    } else {
        ErrorOperation.UNLOCK
    }
    val base = diagnostic.toPocketError(operation, recovery).copy(stage = stage)
    if (stage == QrGenerationStage.UNLOCKING_ACCOUNT && diagnostic.reason == FailureReason.STORAGE_KEY_MISSING) {
        return base.copy(recovery = ErrorRecovery.REPLACE)
    }
    if (stage == QrGenerationStage.GETTING_PIN &&
        diagnostic.reason in setOf(FailureReason.BIOMETRIC_KEY_INVALIDATED, FailureReason.STORAGE_KEY_MISSING)
    ) {
        return base.copy(
            title = UiText(R.string.error_saved_pin_title),
            description = UiText(R.string.error_saved_pin_description),
            recovery = null,
        )
    }
    if (stage == QrGenerationStage.SAVING_PIN) {
        val detail = (reason as? QrGenerationFailure.Authentication)?.reason?.message() ?: base.description
        return base.copy(
            title = UiText(R.string.error_save_pin_title),
            description = UiText(R.string.error_save_pin_description, listOf(detail)),
        )
    }
    return when (val failure = reason) {
        is QrGenerationFailure.Authentication -> base.copy(
            title = UiText(R.string.error_biometric_title),
            description = failure.reason.message(),
        )

        QrGenerationFailure.AuthorizationRejected -> base.copy(
            title = UiText(R.string.error_pin_rejected_title),
            description = UiText(R.string.error_pin_rejected_description),
            recovery = null,
        )

        QrGenerationFailure.InvalidPin -> base.copy(
            title = UiText(R.string.error_pin_invalid_title),
            description = UiText(R.string.error_pin_invalid_description),
            recovery = null,
        )

        QrGenerationFailure.AlreadyRunning -> base.copy(
            title = UiText(R.string.error_running_title),
            description = UiText(R.string.error_running_description),
            recovery = null,
        )

        QrGenerationFailure.OperationFailed -> base

        is QrGenerationFailure.Preflight -> when (failure.reason) {
            DigitalIdPreflightFailure.ATTEMPTS_EXHAUSTED -> base.copy(
                title = UiText(R.string.error_attempts_title),
                description = UiText(R.string.error_attempts_description),
                recovery = null,
            )

            DigitalIdPreflightFailure.COOLDOWN -> base.copy(
                title = UiText(R.string.error_cooldown_title),
                description = UiText(R.string.error_cooldown_description),
            )

            DigitalIdPreflightFailure.DEVICE_MISMATCH, DigitalIdPreflightFailure.TIMESTAMP_MISMATCH -> base.copy(
                title = UiText(R.string.error_profile_changed_title),
                description = UiText(R.string.error_profile_changed_description),
                recovery = ErrorRecovery.REPLACE,
            )

            DigitalIdPreflightFailure.PIN_SALT_MISSING, DigitalIdPreflightFailure.UNSUPPORTED_PROFILE -> base.copy(
                title = UiText(R.string.error_profile_unsupported_title),
                description = UiText(R.string.error_profile_unsupported_description),
                recovery = ErrorRecovery.REPLACE,
            )
        }
    }
}
