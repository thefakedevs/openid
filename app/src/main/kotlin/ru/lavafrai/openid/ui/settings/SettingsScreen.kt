package ru.lavafrai.openid.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.composables.icons.materialsymbols.rounded.R as Symbols
import ru.lavafrai.openid.R
import ru.lavafrai.openid.models.pin.PinProtection
import ru.lavafrai.openid.models.pin.SavedPinState
import ru.lavafrai.openid.ui.PocketUiState
import ru.lavafrai.openid.ui.components.PinProtectionChoices
import ru.lavafrai.openid.ui.components.PocketIcon
import ru.lavafrai.openid.ui.components.PrimaryButton
import ru.lavafrai.openid.ui.components.ProfileIdentity

@Composable
fun SettingsScreen(
    state: PocketUiState,
    onPinProtection: (PinProtection) -> Unit,
    onApply: () -> Unit,
    onReplace: () -> Unit,
    onDelete: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
        ProfileIdentity(state.account?.accountId)
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.sign_in_method), style = MaterialTheme.typography.titleLarge)
            PinProtectionChoices(state.pinProtection, !state.busy, onPinProtection, state.tokenProfile)
        }
        if (state.hasSettingsChanges) {
            Text(
                when {
                    state.tokenProfile && state.pinProtection == PinProtection.NOT_SAVED ->
                        stringResource(R.string.token_lock_manual_settings)

                    state.tokenProfile ->
                        stringResource(R.string.settings_biometric_notice)

                    state.pinProtection == PinProtection.NOT_SAVED ->
                        stringResource(R.string.settings_manual_notice)

                    state.savedPin is SavedPinState.Saved ->
                        stringResource(R.string.settings_biometric_notice)

                    else -> stringResource(R.string.settings_save_pin_notice)
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        PrimaryButton(
            stringResource(R.string.settings_apply),
            onApply,
            enabled = state.hasSettingsChanges && !state.busy,
            icon = R.drawable.materialsymbols_ic_check_rounded,
        )
        TextButton(onClick = onReplace, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                PocketIcon(R.drawable.materialsymbols_ic_swap_horiz_rounded)
                Text(
                    stringResource(R.string.profile_replace),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium,
                )
                PocketIcon(Symbols.drawable.materialsymbols_ic_arrow_forward_rounded)
            }
        }
        TextButton(onClick = onDelete, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.profile_delete), color = MaterialTheme.colorScheme.error)
        }
    }
}
