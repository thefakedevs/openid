package ru.lavafrai.openid.navigation

import androidx.compose.runtime.Composable
import ru.lavafrai.openid.ui.PocketAction
import ru.lavafrai.openid.ui.PocketUiState
import ru.lavafrai.openid.ui.components.ActionButton
import ru.lavafrai.openid.ui.components.MockView
import ru.lavafrai.openid.ui.importing.ImportScreen
import ru.lavafrai.openid.ui.qr.QrScreen
import ru.lavafrai.openid.ui.settings.SettingsScreen
import ru.lavafrai.openid.ui.setup.SetupScreen
import ru.lavafrai.openid.ui.unlock.UnlockScreen

@Composable
fun PocketNavigation(
    state: PocketUiState,
    dispatch: (PocketAction) -> Unit,
) {
    when (state.destination) {
        PocketDestination.START -> {
            MockView("Проверяем наличие аккаунта…")
            if (state.message != null) ActionButton("Повторить", onClick = { dispatch(PocketAction.RetryStartup) })
        }

        PocketDestination.IMPORT -> ImportScreen(
            form = state.importForm,
            replacing = state.importMode == ImportMode.REPLACE,
            busy = state.busy,
            onLinkChange = { dispatch(PocketAction.EditLink(it)) },
            onPasswordChange = { dispatch(PocketAction.EditPassword(it)) },
            onNext = { dispatch(PocketAction.ContinueImport) },
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
            onPinChange = { dispatch(PocketAction.EditPin(it)) },
            onSubmit = { dispatch(PocketAction.SubmitPin) },
            onSavedPin = { dispatch(PocketAction.UseSavedPin) },
            onDelete = { dispatch(PocketAction.AskDelete) },
            onMigrate = { dispatch(PocketAction.MigrateAccountStorage) },
            onSubmitWithoutSaving = { dispatch(PocketAction.SubmitPinWithoutSaving) },
        )

        PocketDestination.QR -> QrScreen(
            state = state,
            onSettings = { dispatch(PocketAction.OpenSettings) },
            onLock = { dispatch(PocketAction.Back) },
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
