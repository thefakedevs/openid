package ru.lavafrai.openid.ui.method

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.composables.icons.materialsymbols.rounded.R as Symbols
import ru.lavafrai.openid.BuildConfig
import ru.lavafrai.openid.R
import ru.lavafrai.openid.navigation.ImportMethod
import ru.lavafrai.openid.ui.components.ChoiceCard
import ru.lavafrai.openid.ui.components.InfoCard
import ru.lavafrai.openid.ui.components.PocketPassHeader
import ru.lavafrai.openid.ui.components.PrimaryButton

/** The first import step: automatic extraction from MAX on this phone, or the legacy link. */
@Composable
fun MethodScreen(
    selected: ImportMethod?,
    busy: Boolean,
    onSelect: (ImportMethod) -> Unit,
    onNext: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
        PocketPassHeader(title = stringResource(R.string.method_title))
        Text(
            stringResource(R.string.method_description),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (BuildConfig.DEBUG) {
                ChoiceCard(
                    title = stringResource(R.string.method_auto_label),
                    description = stringResource(R.string.method_auto_description),
                    selected = selected == ImportMethod.AUTO,
                    enabled = !busy,
                    onClick = { onSelect(ImportMethod.AUTO) },
                    icon = Symbols.drawable.materialsymbols_ic_auto_fix_high_rounded,
                )
            }
            ChoiceCard(
                title = stringResource(R.string.method_manual_label),
                description = stringResource(R.string.method_manual_description),
                selected = selected == ImportMethod.MANUAL,
                enabled = !busy,
                onClick = { onSelect(ImportMethod.MANUAL) },
                icon = Symbols.drawable.materialsymbols_ic_link_rounded,
            )
        }
        // The full "open MAX → unlock Digital ID → we return" walkthrough is shown up front, as soon
        // as the automatic method is picked, instead of only in the final confirmation dialog.
        AnimatedVisibility(visible = BuildConfig.DEBUG && selected == ImportMethod.AUTO) {
            InfoCard(
                stringResource(R.string.method_auto_steps),
                icon = Symbols.drawable.materialsymbols_ic_info_rounded,
            )
        }
        PrimaryButton(
            stringResource(R.string.action_continue),
            onNext,
            enabled = !busy && selected != null,
        )
    }
}
