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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.composables.icons.materialsymbols.rounded.R as Symbols
import ru.lavafrai.openid.R
import ru.lavafrai.openid.models.pin.SavedPinState
import ru.lavafrai.openid.ui.ErrorRecovery
import ru.lavafrai.openid.ui.PocketError
import ru.lavafrai.openid.ui.PocketUiState
import ru.lavafrai.openid.ui.components.ErrorCard
import ru.lavafrai.openid.ui.components.InfoCard
import ru.lavafrai.openid.ui.components.PrimaryButton
import ru.lavafrai.openid.ui.components.ScreenHeading
import ru.lavafrai.openid.ui.resolve

@Composable
fun UnlockScreen(
    state: PocketUiState,
    manualEntry: Boolean,
    onManualEntryChange: (Boolean) -> Unit,
    onRecoverError: (PocketError) -> Unit,
    onPinChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onSavedPin: () -> Unit,
    onOpenTokenQr: () -> Unit,
    onMigrate: () -> Unit,
) {
    val saved = (state.savedPin as? SavedPinState.Saved)?.takeIf { it.accountId == state.account?.accountId }
    val canSubmit = !state.busy && state.retrySeconds == 0
    var showPinHelp by remember { mutableStateOf(false) }
    if (state.tokenProfile && !state.accountMigrationRequired) {
        Column(verticalArrangement = Arrangement.spacedBy(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            state.error?.let { error -> ErrorCard(error, { onRecoverError(error) }, enabled = canSubmit) }
            if (state.error?.stage == ru.lavafrai.openid.domain.models.QrGenerationStage.AUTHORIZING) {
                Text(
                    stringResource(R.string.unlock_token_stale),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (state.error == null) {
                ScreenHeading(
                    stringResource(R.string.unlock_token_title),
                    stringResource(R.string.unlock_token_description),
                )
                PrimaryButton(
                    stringResource(R.string.qr_open),
                    onOpenTokenQr,
                    enabled = canSubmit,
                    icon = Symbols.drawable.materialsymbols_ic_qr_code_2_rounded,
                )
                Text(
                    stringResource(R.string.unlock_token_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            state.message?.let {
                InfoCard(
                    it.resolve(),
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    icon = Symbols.drawable.materialsymbols_ic_info_rounded,
                )
            }
        }
        return
    }
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
                    stringResource(R.string.migration_title),
                    stringResource(R.string.migration_description),
                )
                PrimaryButton(stringResource(R.string.migration_action), onMigrate, enabled = !state.busy)
            } else {
                if (state.error == null) {
                    ScreenHeading(
                        stringResource(R.string.unlock_title),
                        stringResource(R.string.unlock_description),
                    )
                }
                if (state.error?.recovery != ErrorRecovery.BIOMETRIC) {
                    PrimaryButton(
                        stringResource(R.string.biometric_sign_in),
                        onSavedPin,
                        enabled = canSubmit,
                        icon = Symbols.drawable.materialsymbols_ic_fingerprint_rounded,
                    )
                }
                TextButton(onClick = {
                    onManualEntryChange(true)
                }, enabled = !state.busy) { Text(stringResource(R.string.pin_enter_action)) }
                if (state.retrySeconds > 0) {
                    Text(
                        stringResource(R.string.pin_retry_countdown, state.retrySeconds),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            state.message?.let {
                InfoCard(
                    it.resolve(),
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    icon = Symbols.drawable.materialsymbols_ic_info_rounded,
                )
            }
        }
    }
    if (showPinHelp) {
        AlertDialog(
            onDismissRequest = { showPinHelp = false },
            title = { Text(stringResource(R.string.pin_title)) },
            text = {
                Text(stringResource(R.string.pin_help_description))
            },
            confirmButton = {
                TextButton(onClick = { showPinHelp = false }) { Text(stringResource(R.string.action_understood)) }
            },
        )
    }
}
