package ru.lavafrai.openid.ui.rootsource

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.composables.icons.materialsymbols.rounded.R as Symbols
import ru.lavafrai.openid.R
import ru.lavafrai.openid.data.repositories.maximport.api.RootImportPackages
import ru.lavafrai.openid.navigation.RootSource
import ru.lavafrai.openid.ui.RootCheckState
import ru.lavafrai.openid.ui.components.ChoiceCard
import ru.lavafrai.openid.ui.components.InfoCard
import ru.lavafrai.openid.ui.components.PocketIcon
import ru.lavafrai.openid.ui.components.PrimaryButton
import ru.lavafrai.openid.ui.components.ScreenHeading

/**
 * The second automatic-import step: root is either already confirmed by a manager or obtained
 * through the embedded DFRoot exploit. The ready-root option stays disabled until `su` answers.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun RootSourceScreen(
    selected: RootSource?,
    check: RootCheckState,
    maxInstalled: Boolean,
    suManagers: List<String>,
    dfRootLog: String?,
    busy: Boolean,
    onSelect: (RootSource) -> Unit,
    onRefreshCheck: () -> Unit,
    onRunDfRoot: () -> Unit,
    onNext: () -> Unit,
) {
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { onRefreshCheck() }
    val context = LocalContext.current
    Column(verticalArrangement = Arrangement.spacedBy(28.dp)) {
        ScreenHeading(
            stringResource(R.string.root_source_title),
            stringResource(R.string.root_source_description),
        )
        Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ChoiceCard(
                title = stringResource(R.string.root_ready_label),
                description = stringResource(
                    if (check == RootCheckState.AVAILABLE) {
                        R.string.root_ready_description
                    } else {
                        R.string.root_ready_unavailable_description
                    },
                ),
                selected = selected == RootSource.READY_ROOT,
                enabled = !busy && check == RootCheckState.AVAILABLE,
                onClick = { onSelect(RootSource.READY_ROOT) },
                icon = Symbols.drawable.materialsymbols_ic_verified_user_rounded,
            )
            ChoiceCard(
                title = stringResource(R.string.root_dfroot_label),
                description = dfRootDescription(suManagers, android.os.Build.VERSION.SDK_INT >= 28),
                selected = selected == RootSource.DFROOT,
                enabled = !busy && android.os.Build.VERSION.SDK_INT >= 28,
                onClick = { onSelect(RootSource.DFROOT) },
                icon = Symbols.drawable.materialsymbols_ic_electric_bolt_rounded,
            )
        }
        when {
            check == RootCheckState.CHECKING -> CheckingRow()

            !maxInstalled -> InfoCard(
                stringResource(R.string.root_max_missing_notice),
                icon = Symbols.drawable.materialsymbols_ic_info_rounded,
            )

            selected == RootSource.DFROOT && check != RootCheckState.AVAILABLE && !busy ->
                DfRootGuidance(suManagers.isNotEmpty(), dfRootLog, context, onRunDfRoot)
        }
        PrimaryButton(
            stringResource(R.string.action_continue),
            onNext,
            enabled = !busy && selected != null && check == RootCheckState.AVAILABLE && maxInstalled,
        )
    }
}

@Composable
private fun dfRootDescription(
    suManagers: List<String>,
    sdkSupported: Boolean,
): String {
    val base = stringResource(R.string.root_dfroot_description)
    var text = base
    if (suManagers.isEmpty()) text += " " + stringResource(R.string.root_dfroot_no_manager_suffix)
    if (!sdkSupported) text += " " + stringResource(R.string.root_dfroot_sdk_suffix)
    return text
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun CheckingRow() {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        LoadingIndicator(Modifier.size(40.dp))
        Text(
            stringResource(R.string.root_checking),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun DfRootGuidance(
    managerInstalled: Boolean,
    dfRootLog: String?,
    context: android.content.Context,
    onRunDfRoot: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        InfoCard(
            stringResource(
                if (managerInstalled) R.string.root_dfroot_run_notice else R.string.root_dfroot_install_notice,
            ),
            icon = Symbols.drawable.materialsymbols_ic_info_rounded,
        )
        if (managerInstalled) {
            OutlinedButton(
                onClick = onRunDfRoot,
                shapes = ButtonDefaults.shapes(),
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) {
                Text(
                    stringResource(R.string.root_dfroot_run),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(Modifier.width(12.dp))
                PocketIcon(
                    Symbols.drawable.materialsymbols_ic_electric_bolt_rounded,
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        } else {
            OutlinedButton(
                onClick = {
                    runCatching {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse(RootImportPackages.KSU_RELEASES_URL)),
                        )
                    }
                },
                shapes = ButtonDefaults.shapes(),
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) {
                Text(
                    stringResource(R.string.root_dfroot_install),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(Modifier.width(12.dp))
                PocketIcon(
                    Symbols.drawable.materialsymbols_ic_open_in_new_rounded,
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }
        dfRootLog?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
