package ru.lavafrai.openid.ui.unlock

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.composables.icons.materialsymbols.rounded.R as Symbols
import ru.lavafrai.openid.models.pin.SavedPinState
import ru.lavafrai.openid.ui.ErrorRecovery
import ru.lavafrai.openid.ui.PocketError
import ru.lavafrai.openid.ui.PocketUiState
import ru.lavafrai.openid.ui.components.ErrorCard
import ru.lavafrai.openid.ui.components.InfoCard
import ru.lavafrai.openid.ui.components.PrimaryButton
import ru.lavafrai.openid.ui.components.ScreenHeading

@Composable
fun UnlockScreen(
    state: PocketUiState,
    manualEntry: Boolean,
    onManualEntryChange: (Boolean) -> Unit,
    onRecoverError: (PocketError) -> Unit,
    onPinChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onSavedPin: () -> Unit,
    onMigrate: () -> Unit,
) {
    val saved = (state.savedPin as? SavedPinState.Saved)?.takeIf { it.accountId == state.account?.accountId }
    val canSubmit = !state.busy && state.retrySeconds == 0
    var showPinHelp by remember { mutableStateOf(false) }
    BackHandler(saved != null && manualEntry && !state.busy) {
        onPinChange("")
        onManualEntryChange(false)
    }
    if (manualEntry && !state.accountMigrationRequired) {
        PinEntryContent(
            state = state,
            onRecoverError = onRecoverError,
            onPinChange = onPinChange,
            onSubmit = onSubmit,
            onBiometric = onSavedPin.takeIf { saved != null },
            onHelp = { showPinHelp = true },
        )
    } else {
        Column(verticalArrangement = Arrangement.spacedBy(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            state.error?.let { error -> ErrorCard(error, { onRecoverError(error) }, enabled = canSubmit) }
            if (state.accountMigrationRequired) {
                ScreenHeading(
                    "Обновим защиту",
                    "Подтвердите доступ к старому профилю один раз. После обновления биометрия потребуется только для PIN.",
                )
                PrimaryButton("Обновить хранилище", onMigrate, enabled = !state.busy)
            } else {
                if (state.error == null) {
                    ScreenHeading(
                        "Вход",
                        "PIN сохранён. Подтвердите биометрию, чтобы открыть QR.",
                    )
                }
                if (state.error?.recovery != ErrorRecovery.BIOMETRIC) {
                    PrimaryButton(
                        "Войти по биометрии",
                        onSavedPin,
                        enabled = canSubmit,
                        icon = Symbols.drawable.materialsymbols_ic_fingerprint_rounded,
                    )
                }
                TextButton(onClick = { onManualEntryChange(true) }, enabled = !state.busy) { Text("Ввести PIN") }
                if (state.retrySeconds > 0) {
                    Text("Повторная попытка через ${state.retrySeconds} с", style = MaterialTheme.typography.bodyMedium)
                }
            }
            state.message?.let {
                InfoCard(
                    it,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    icon = Symbols.drawable.materialsymbols_ic_info_rounded,
                )
            }
        }
    }
    if (showPinHelp) {
        AlertDialog(
            onDismissRequest = { showPinHelp = false },
            title = { Text("PIN Digital ID") },
            text = {
                Text("Введите PIN, который вы используете для Digital ID. Код блокировки телефона сюда не подходит.")
            },
            confirmButton = {
                TextButton(onClick = { showPinHelp = false }) { Text("Понятно") }
            },
        )
    }
}
