package ru.lavafrai.openid.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.composables.icons.materialsymbols.rounded.R as Symbols
import ru.lavafrai.openid.ui.PocketError

@Composable
fun ErrorCard(
    error: PocketError,
    onRecover: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    var details by remember(error) { mutableStateOf(false) }
    Surface(
        modifier = modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.3f)),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                PocketIcon(Symbols.drawable.materialsymbols_ic_error_rounded, tint = MaterialTheme.colorScheme.error)
                Text(
                    error.title,
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.semantics {
                        heading()
                    },
                )
            }
            Text(error.description, style = MaterialTheme.typography.bodyMedium)
            error.recovery?.let {
                Button(onClick = onRecover, enabled = enabled, modifier = Modifier.fillMaxWidth()) { Text(it.label) }
            }
            TextButton(onClick = { details = true }, modifier = Modifier.align(Alignment.End)) {
                Text("Подробности", color = MaterialTheme.colorScheme.onErrorContainer)
            }
        }
    }
    if (details) ErrorDetails(error, onDismiss = { details = false })
}

/** Keeps the PIN keypad stationary; recovery and diagnostics open at a readable size. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PinErrorCard(
    error: PocketError,
    onRecover: () -> Unit,
    enabled: Boolean,
) {
    var expanded by remember(error) { mutableStateOf(true) }
    var details by remember(error) { mutableStateOf(false) }
    Surface(
        onClick = { expanded = true },
        modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
    ) {
        Row(
            Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            PocketIcon(Symbols.drawable.materialsymbols_ic_error_rounded, tint = MaterialTheme.colorScheme.error)
            Text(
                error.title,
                Modifier.weight(1f),
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            PocketIcon(Symbols.drawable.materialsymbols_ic_expand_more_rounded, description = "Открыть ошибку")
        }
    }
    if (expanded) {
        ModalBottomSheet(
            onDismissRequest = { expanded = false },
            sheetState = rememberBottomSheetState(
                initialValue = SheetValue.Hidden,
                enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
            ),
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        ) {
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 24.dp).navigationBarsPadding().padding(bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Column(
                    Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        error.title,
                        style = MaterialTheme.typography.headlineSmall,
                        modifier = Modifier.semantics {
                            heading()
                        },
                    )
                    Text(error.description, style = MaterialTheme.typography.bodyLarge)
                }
                error.recovery?.let { recovery ->
                    Button(
                        onClick = {
                            expanded = false
                            onRecover()
                        },
                        enabled = enabled,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(recovery.label) }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(onClick = {
                        expanded = false
                        details = true
                    }) { Text("Подробности") }
                    TextButton(onClick = { expanded = false }) { Text("Закрыть") }
                }
            }
        }
    }
    if (details) ErrorDetails(error, onDismiss = { details = false })
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ErrorDetails(
    error: PocketError,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var copyStatus by remember(error) { mutableStateOf<String?>(null) }
    val report = remember(error, context) {
        val version = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }
            .getOrNull() ?: "unknown"
        "Pocket ID $version · Android API ${Build.VERSION.SDK_INT}\n\n${error.report}"
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberBottomSheetState(
            initialValue = SheetValue.Hidden,
            enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
        ),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 24.dp).navigationBarsPadding().padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Column(
                Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text("Диагностика", style = MaterialTheme.typography.headlineSmall)
                Text("Отчёт без PIN, паролей, токенов и ссылок.", style = MaterialTheme.typography.bodyMedium)
                Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                    SelectionContainer {
                        Text(
                            report,
                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        )
                    }
                }
            }
            copyStatus?.let { Text(it, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
            Button(
                onClick = {
                    copyStatus = try {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("Pocket ID diagnostic", report))
                        "Отчёт скопирован"
                    } catch (_: Exception) {
                        "Не удалось скопировать. Можно выделить текст отчёта вручную."
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Скопировать отчёт")
            }
        }
    }
}
