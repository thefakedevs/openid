package ru.lavafrai.openid.ui.unlock

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import com.composables.icons.materialsymbols.rounded.R as Symbols
import ru.lavafrai.openid.R
import ru.lavafrai.openid.ui.PocketUiState
import ru.lavafrai.openid.ui.components.PocketIcon

@Composable
internal fun UnlockMenu(
    state: PocketUiState,
    manualEntry: Boolean,
    onSubmitWithoutSaving: () -> Unit,
    onDelete: () -> Unit,
) {
    var expanded by remember(state.busy, state.account?.accountId, manualEntry, state.accountMigrationRequired) {
        mutableStateOf(false)
    }
    Box {
        IconButton(onClick = { expanded = true }, enabled = !state.busy) {
            PocketIcon(
                Symbols.drawable.materialsymbols_ic_more_vert_rounded,
                description = stringResource(R.string.action_more),
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            if (manualEntry && state.willSavePin && !state.accountMigrationRequired) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.pin_skip_saving)) },
                    enabled = !state.busy && state.retrySeconds == 0 && state.pin.length == 4,
                    onClick = {
                        expanded = false
                        onSubmitWithoutSaving()
                    },
                )
            }
            DropdownMenuItem(
                text = { Text(stringResource(R.string.profile_delete)) },
                colors = MenuDefaults.itemColors(textColor = MaterialTheme.colorScheme.error),
                enabled = !state.busy,
                onClick = {
                    expanded = false
                    onDelete()
                },
            )
        }
    }
}
