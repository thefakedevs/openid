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
            Text("Способ входа", style = MaterialTheme.typography.titleLarge)
            PinProtectionChoices(state.pinProtection, !state.busy, onPinProtection)
        }
        if (state.hasSettingsChanges) {
            Text(
                when {
                    state.pinProtection == PinProtection.NOT_SAVED ->
                        "Сохранённый PIN будет удалён. Сеанс завершится."

                    state.savedPin is SavedPinState.Saved ->
                        "Сеанс завершится. Для нового входа подтвердите биометрию."

                    else -> "После применения введите PIN один раз, чтобы сохранить его с биометрией."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        PrimaryButton(
            "Применить и выйти",
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
                    "Заменить профиль",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium,
                )
                PocketIcon(Symbols.drawable.materialsymbols_ic_arrow_forward_rounded)
            }
        }
        TextButton(onClick = onDelete, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
            Text("Удалить профиль", color = MaterialTheme.colorScheme.error)
        }
    }
}
