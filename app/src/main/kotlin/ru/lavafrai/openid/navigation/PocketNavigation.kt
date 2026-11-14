package ru.lavafrai.openid.navigation

import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LoadingIndicator
import androidx.compose.runtime.Composable
import ru.lavafrai.openid.ui.PocketAction
import ru.lavafrai.openid.ui.PocketUiState
import ru.lavafrai.openid.ui.importing.ImportScreen
import ru.lavafrai.openid.ui.method.MethodScreen
import ru.lavafrai.openid.ui.qr.QrScreen
import ru.lavafrai.openid.ui.rootsource.RootSourceScreen
import ru.lavafrai.openid.ui.settings.SettingsScreen
import ru.lavafrai.openid.ui.setup.SetupScreen
import ru.lavafrai.openid.ui.unlock.UnlockScreen

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun PocketNavigation(
    state: PocketUiState,
    manualPinEntry: Boolean,
    onManualPinEntryChange: (Boolean) -> Unit,
    dispatch: (PocketAction) -> Unit,
) {
    when (state.destination) {
        PocketDestination.START -> {
            if (state.error == null) LoadingIndicator()
        }

        PocketDestination.METHOD -> MethodScreen(
            selected = state.importMethod,
            busy = state.busy,
            onSelect = { dispatch(PocketAction.SelectImportMethod(it)) },
            onNext = { dispatch(PocketAction.ContinueImportMethod) },
        )

        PocketDestination.IMPORT -> ImportScreen(
            form = state.importForm,
            replacing = state.importMode == ImportMode.REPLACE,
            busy = state.busy,
            onLinkChange = { dispatch(PocketAction.EditLink(it)) },
            onPasswordChange = { dispatch(PocketAction.EditPassword(it)) },
            onNext = { dispatch(PocketAction.ContinueImport) },
        )

        PocketDestination.ROOT_SOURCE -> RootSourceScreen(
            selected = state.rootSource,
            check = state.rootCheck,
            maxInstalled = state.maxInstalled,
            dfRootInstalled = state.dfRootInstalled,
            busy = state.busy,
            onSelect = { dispatch(PocketAction.SelectRootSource(it)) },
            onRefreshCheck = { dispatch(PocketAction.RefreshRootCheck) },
            onNext = { dispatch(PocketAction.ContinueRootImport) },
        )

        PocketDestination.SETUP -> SetupScreen(
            pinProtection = state.pinProtection,
            busy = state.busy,
            replacing = state.importMode == ImportMode.REPLACE,
            onPinProtection = { dispatch(PocketAction.SelectPinProtection(it)) },
            onSave = { dispatch(PocketAction.CommitImport) },
        )

        PocketDestination.UNLOCK -> UnlockScreen(
            state = state,
            manualEntry = manualPinEntry,
            onManualEntryChange = onManualPinEntryChange,
            onRecoverError = { dispatch(PocketAction.RecoverError(it)) },
            onPinChange = { dispatch(PocketAction.EditPin(it)) },
            onSubmit = { dispatch(PocketAction.SubmitPin) },
            onSavedPin = { dispatch(PocketAction.UseSavedPin) },
            onMigrate = { dispatch(PocketAction.MigrateAccountStorage) },
        )

        PocketDestination.QR -> QrScreen(
            state = state,
            onRecoverError = { dispatch(PocketAction.RecoverError(it)) },
            onRefresh = { dispatch(PocketAction.RefreshQr) },
            onLock = { dispatch(PocketAction.EndSession) },
        )

        PocketDestination.SETTINGS -> SettingsScreen(
            state = state,
            onPinProtection = { dispatch(PocketAction.SelectPinProtection(it)) },
            onApply = { dispatch(PocketAction.ApplySettings) },
            onReplace = { dispatch(PocketAction.ReplaceAccount) },
            onDelete = { dispatch(PocketAction.AskDelete) },
        )
    }
}
