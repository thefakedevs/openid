package ru.lavafrai.openid.ui

import javax.crypto.Cipher
import ru.lavafrai.openid.domain.models.QrGenerationStage
import ru.lavafrai.openid.models.account.AccountSummary
import ru.lavafrai.openid.models.auth.AuthenticationFailure
import ru.lavafrai.openid.models.digitalid.QrSession
import ru.lavafrai.openid.models.pin.PinProtection
import ru.lavafrai.openid.models.pin.SavedPinState
import ru.lavafrai.openid.navigation.ImportMode
import ru.lavafrai.openid.navigation.PocketDestination

data class ImportForm(
    val link: String = "",
    val password: String = "",
) {
    override fun toString(): String = "ImportForm(<redacted>)"
}

enum class AuthKind(val title: String) {
    LEGACY_PROFILE_MIGRATION("Обновите хранилище профиля"),
    BIOMETRIC("Подтвердите доступ к PIN Digital ID"),
}

class AuthRequest(
    val kind: AuthKind,
    val cipher: Cipher,
) {
    override fun toString(): String = "AuthRequest(kind=$kind, cipher=<redacted>)"
}

data class PocketUiState(
    val destination: PocketDestination = PocketDestination.START,
    val account: AccountSummary? = null,
    val savedPin: SavedPinState = SavedPinState.Loading,
    val importMode: ImportMode = ImportMode.NEW,
    val importForm: ImportForm = ImportForm(),
    val accountMigrationRequired: Boolean = false,
    val pinProtection: PinProtection = PinProtection.NOT_SAVED,
    val pin: String = "",
    val busy: Boolean = false,
    val message: String? = null,
    val qr: QrSession? = null,
    val qrStage: QrGenerationStage? = null,
    val retrySeconds: Int = 0,
    val confirmDelete: Boolean = false,
    val authPrompt: AuthRequest? = null,
) {
    val hasBack: Boolean get() = destination in setOf(
        PocketDestination.SETUP,
        PocketDestination.QR,
        PocketDestination.SETTINGS,
    ) || (destination == PocketDestination.IMPORT && importMode == ImportMode.REPLACE)

    override fun toString(): String = "PocketUiState(destination=$destination, busy=$busy, secrets=<redacted>)"
}

sealed interface PocketAction {
    data class EditLink(val value: String) : PocketAction

    data class EditPassword(val value: String) : PocketAction

    data class EditPin(val value: String) : PocketAction

    data object MigrateAccountStorage : PocketAction

    data class SelectPinProtection(val value: PinProtection) : PocketAction

    data object ContinueImport : PocketAction

    data object CommitImport : PocketAction

    data object SubmitPin : PocketAction

    data object SubmitPinWithoutSaving : PocketAction

    data object UseSavedPin : PocketAction

    data object OpenSettings : PocketAction

    data object ApplySettings : PocketAction

    data object ReplaceAccount : PocketAction

    data object AskDelete : PocketAction

    data object ConfirmDelete : PocketAction

    data object DismissDelete : PocketAction

    data class ConfirmAuth(val request: AuthRequest) : PocketAction

    data class CancelAuth(val request: AuthRequest) : PocketAction

    data class AuthFailed(
        val request: AuthRequest,
        val reason: AuthenticationFailure,
    ) : PocketAction

    data object Back : PocketAction

    data object RetryStartup : PocketAction
}
