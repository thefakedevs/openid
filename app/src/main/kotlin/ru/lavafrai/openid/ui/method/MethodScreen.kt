package ru.lavafrai.openid.ui.method

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.composables.icons.materialsymbols.rounded.R as Symbols
import ru.lavafrai.openid.BuildConfig
import ru.lavafrai.openid.R
import ru.lavafrai.openid.navigation.ImportMethod
import ru.lavafrai.openid.ui.components.ChoiceCard
import ru.lavafrai.openid.ui.components.PrimaryButton
import ru.lavafrai.openid.ui.components.ScreenHeading

/** The first import step: automatic extraction from MAX on this phone, or the legacy link. */
@Composable
fun MethodScreen(
    selected: ImportMethod?,
    busy: Boolean,
    onSelect: (ImportMethod) -> Unit,
    onNext: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(28.dp)) {
        ScreenHeading(
            stringResource(R.string.method_title),
            stringResource(R.string.method_description),
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
        PrimaryButton(
            stringResource(R.string.action_continue),
            onNext,
            enabled = !busy && selected != null,
        )
    }
}
