package ru.lavafrai.openid.ui

import androidx.annotation.StringRes
import javax.crypto.Cipher
import ru.lavafrai.openid.R
import ru.lavafrai.openid.data.repositories.maximport.api.RootImportStage
import ru.lavafrai.openid.domain.models.QrGenerationStage
import ru.lavafrai.openid.models.account.AccountSummary
import ru.lavafrai.openid.models.auth.AuthenticationFailure
import ru.lavafrai.openid.models.digitalid.QrSession
import ru.lavafrai.openid.models.errors.FailureDiagnostic
import ru.lavafrai.openid.models.pin.PinProtection
import ru.lavafrai.openid.models.pin.SavedPinState
import ru.lavafrai.openid.navigation.ImportMethod
import ru.lavafrai.openid.navigation.ImportMode
import ru.lavafrai.openid.navigation.PocketDestination
import ru.lavafrai.openid.navigation.RootSource

data class ImportForm(
    val link: String = "",
    val password: String = "",
) {
    override fun toString(): String = "ImportForm(<redacted>)"
}

enum class RootCheckState { IDLE, CHECKING, AVAILABLE, UNAVAILABLE }

enum class AuthKind(@StringRes val titleRes: Int) {
    LEGACY_PROFILE_MIGRATION(R.string.auth_migration_title),
    BIOMETRIC(R.string.auth_pin_title),
}

class AuthRequest(
    val kind: AuthKind,
    val cipher: Cipher,
) {
    override fun toString(): String = "AuthRequest(kind=$kind, cipher=<redacted>)"
}

class PocketNotice(val message: UiText)

data class PinSettingsState(
    val selection: PinProtection,
    val initial: PinProtection,
)

data class PocketUiState(
    val destination: PocketDestination = PocketDestination.START,
    val account: AccountSummary? = null,
    val savedPin: SavedPinState = SavedPinState.Loading,
    val importMode: ImportMode = ImportMode.NEW,
    val importMethod: ImportMethod? = null,
    val rootSource: RootSource? = null,
    val rootCheck: RootCheckState = RootCheckState.IDLE,
    val maxInstalled: Boolean = true,
    val suManagers: List<String> = emptyList(),
    val rootStage: RootImportStage? = null,
    val tokenProfile: Boolean = false,
    val importForm: ImportForm = ImportForm(),
    val accountMigrationRequired: Boolean = false,
    val unlockPinProtection: PinProtection = PinProtection.NOT_SAVED,
    val setupPinProtection: PinProtection = PinProtection.NOT_SAVED,
    val settings: PinSettingsState? = null,
    val pin: String = "",
    val screenBusy: Boolean = false,
    val message: UiText? = null,
    val notice: PocketNotice? = null,
    val screenError: PocketError? = null,
    val session: QrSessionUiState = QrSessionUiState(),
    val autoUnlockPending: Boolean = true,
    val replaceSavedPin: Boolean = false,
    val retrySeconds: Int = 0,
    val confirmDelete: Boolean = false,
    val authPrompt: AuthRequest? = null,
) {
    val editingImport: Boolean get() =
        destination == PocketDestination.METHOD || destination == PocketDestination.IMPORT ||
            destination == PocketDestination.ROOT_SOURCE || destination == PocketDestination.SETUP
    val busy: Boolean get() = screenBusy || (!editingImport && session.opening)
    val error: PocketError? get() = screenError ?: session.error.takeUnless { editingImport }
    val qr: QrSession? get() = session.qr
    val qrStage: QrGenerationStage? get() = session.stage
    val qrRefreshAtMillis: Long? get() = session.refreshAtMillis
    val qrRefreshIntervalMillis: Long get() = session.refreshIntervalMillis
    val rootAvailable: Boolean get() = rootCheck == RootCheckState.AVAILABLE
    val pinProtection: PinProtection get() = when (destination) {
        PocketDestination.SETUP -> setupPinProtection
        PocketDestination.SETTINGS -> settings?.selection ?: unlockPinProtection
        else -> unlockPinProtection
    }
    val initialSettingsPinProtection: PinProtection? get() = settings?.initial
    val hasSettingsChanges: Boolean get() = destination == PocketDestination.SETTINGS &&
        settings?.let { it.selection != it.initial } == true

    val willSavePin: Boolean get() = pinProtection == PinProtection.BIOMETRIC &&
        (replaceSavedPin || (savedPin as? SavedPinState.Saved)?.takeIf { it.accountId == account?.accountId } == null)

    val hasBack: Boolean get() = destination in setOf(
        PocketDestination.SETUP,
        PocketDestination.ROOT_SOURCE,
        PocketDestination.QR,
        PocketDestination.SETTINGS,
    ) || destination == PocketDestination.IMPORT ||
        (destination == PocketDestination.METHOD && importMode == ImportMode.REPLACE)

    override fun toString(): String = "PocketUiState(destination=$destination, busy=$busy, secrets=<redacted>)"
}

sealed interface PocketAction {
    data class EditLink(val value: String) : PocketAction

    data class EditPassword(val value: String) : PocketAction

    data class EditPin(val value: String) : PocketAction

    data class SelectImportMethod(val value: ImportMethod) : PocketAction

    data object ContinueImportMethod : PocketAction

    data class SelectRootSource(val value: RootSource) : PocketAction

    data object ContinueRootImport : PocketAction

    data object RefreshRootCheck : PocketAction

    data object RunDfRoot : PocketAction

    data object OpenQr : PocketAction

    data object MigrateAccountStorage : PocketAction

    data class SelectPinProtection(val value: PinProtection) : PocketAction

    data object ContinueImport : PocketAction

    data object CommitImport : PocketAction

    data object SubmitPin : PocketAction

    data object SubmitPinWithoutSaving : PocketAction

    data object UseSavedPin : PocketAction

    data object AutoUnlock : PocketAction

    data object RefreshQr : PocketAction

    data class RecoverError(val error: PocketError) : PocketAction

    data object OpenSettings : PocketAction

    data object ApplySettings : PocketAction

    data class DismissNotice(val notice: PocketNotice) : PocketAction

    data object ReplaceAccount : PocketAction

    data object AskDelete : PocketAction

    data object ConfirmDelete : PocketAction

    data object DismissDelete : PocketAction

    data class ConfirmAuth(val request: AuthRequest) : PocketAction

    data class CancelAuth(val request: AuthRequest) : PocketAction

    data class AuthFailed(
        val request: AuthRequest,
        val reason: AuthenticationFailure,
        val diagnostic: FailureDiagnostic? = null,
    ) : PocketAction

    data class Back(val from: PocketDestination) : PocketAction

    data object EndSession : PocketAction

    data object RetryStartup : PocketAction
}
