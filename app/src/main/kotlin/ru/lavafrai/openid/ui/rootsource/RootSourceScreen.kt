package ru.lavafrai.openid.ui.rootsource

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.os.PersistableBundle
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.composables.icons.materialsymbols.rounded.R as Symbols
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.lavafrai.openid.R
import ru.lavafrai.openid.data.repositories.maximport.api.RootImportPackages
import ru.lavafrai.openid.navigation.RootSource
import ru.lavafrai.openid.ui.RootCheckState
import ru.lavafrai.openid.ui.components.ChoiceCard
import ru.lavafrai.openid.ui.components.InfoCard
import ru.lavafrai.openid.ui.components.PocketIcon
import ru.lavafrai.openid.ui.components.PocketPassHeader
import ru.lavafrai.openid.ui.components.PrimaryButton

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
    dfRootSupported: Boolean,
    busy: Boolean,
    onSelect: (RootSource) -> Unit,
    onRefreshCheck: () -> Unit,
    onRunDfRoot: () -> Unit,
    onNext: () -> Unit,
) {
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { onRefreshCheck() }
    val context = LocalContext.current
    var confirming by rememberSaveable { mutableStateOf(false) }
    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text(stringResource(R.string.root_confirm_title)) },
            text = { Text(stringResource(R.string.root_confirm_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirming = false
                        onNext()
                    },
                ) { Text(stringResource(R.string.root_confirm_accept)) }
            },
            dismissButton = {
                TextButton(onClick = { confirming = false }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
    Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
        PocketPassHeader(title = stringResource(R.string.root_source_title))
        Text(
            stringResource(R.string.root_source_description),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                description = dfRootDescription(suManagers, dfRootSupported),
                selected = selected == RootSource.DFROOT,
                enabled = !busy && dfRootSupported,
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

            selected != null && check == RootCheckState.AVAILABLE -> InfoCard(
                stringResource(R.string.root_source_instruction),
                icon = Symbols.drawable.materialsymbols_ic_info_rounded,
            )

            selected == RootSource.DFROOT && dfRootSupported && check != RootCheckState.AVAILABLE && !busy ->
                DfRootGuidance(suManagers.isNotEmpty(), context, onRunDfRoot)
        }
        PrimaryButton(
            stringResource(R.string.action_continue),
            { confirming = true },
            enabled = !busy && selected != null && check == RootCheckState.AVAILABLE && maxInstalled,
        )
        // Debug builds only: the extraction trace is kept there for diagnosing a flaky import.
        val debuggable = (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        if (debuggable) DebugLogButton()
    }
}

/**
 * Debug-only viewer for the extraction trace ([RootImportPackages.TRACE_LOG_FILE]). The trace is no
 * longer wiped on success in debug builds, so a failed or slow import can be inspected and copied
 * here without a USB cable.
 */
@Composable
private fun DebugLogButton() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var log by remember { mutableStateOf<String?>(null) }
    log?.let { contents ->
        AlertDialog(
            onDismissRequest = { log = null },
            title = { Text(stringResource(R.string.root_debug_log_title)) },
            text = {
                val scroll = rememberScrollState()
                Text(
                    contents.ifBlank { stringResource(R.string.root_debug_log_empty) },
                    modifier = Modifier.heightIn(max = 420.dp).verticalScroll(scroll),
                    style = MaterialTheme.typography.bodySmall,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = { copyToClipboard(context, contents) },
                    enabled = contents.isNotBlank(),
                ) { Text(stringResource(R.string.action_copy)) }
            },
            dismissButton = {
                TextButton(onClick = { log = null }) { Text(stringResource(R.string.action_close)) }
            },
        )
    }
    TextButton(
        onClick = {
            scope.launch {
                log = withContext(Dispatchers.IO) { readTrace(context) }
            }
        },
        modifier = Modifier.fillMaxWidth(),
    ) { Text(stringResource(R.string.root_debug_log)) }
}

private fun readTrace(context: Context): String {
    val file = File(context.cacheDir, RootImportPackages.TRACE_LOG_FILE)
    return runCatching {
        if (!file.exists()) "" else file.readText().takeLast(MAX_TRACE_CHARS)
    }.getOrDefault("")
}

private fun copyToClipboard(
    context: Context,
    text: String,
) {
    runCatching {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("maximport trace", text)
        clip.description.extras = PersistableBundle().apply {
            putBoolean("android.content.extra.IS_SENSITIVE", true)
        }
        clipboard.setPrimaryClip(clip)
    }
}

private const val MAX_TRACE_CHARS = 20_000

@Composable
private fun dfRootDescription(
    suManagers: List<String>,
    supported: Boolean,
): String {
    if (!supported) return stringResource(R.string.root_dfroot_unsupported)
    val base = stringResource(R.string.root_dfroot_description)
    var text = base
    if (suManagers.isEmpty()) text += " " + stringResource(R.string.root_dfroot_no_manager_suffix)
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
    context: Context,
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
    }
}
