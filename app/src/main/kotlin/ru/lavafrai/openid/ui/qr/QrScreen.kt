package ru.lavafrai.openid.ui.qr

import android.view.WindowManager
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.keepScreenOn
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.composables.icons.materialsymbols.rounded.R as Symbols
import io.github.alexzhirkevich.qrose.QrCodePainter
import io.github.alexzhirkevich.qrose.options.QrErrorCorrectionLevel
import io.github.alexzhirkevich.qrose.options.QrOptions
import kotlinx.coroutines.delay
import ru.lavafrai.openid.R
import ru.lavafrai.openid.ui.ErrorOperation
import ru.lavafrai.openid.ui.ErrorRecovery
import ru.lavafrai.openid.ui.PocketError
import ru.lavafrai.openid.ui.PocketUiState
import ru.lavafrai.openid.ui.components.ErrorCard
import ru.lavafrai.openid.ui.components.PocketIcon
import ru.lavafrai.openid.ui.components.ScreenHeading
import ru.lavafrai.openid.ui.toPocketError

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun QrScreen(
    state: PocketUiState,
    onRecoverError: (PocketError) -> Unit,
    onRefresh: () -> Unit,
    onLock: () -> Unit,
) {
    val qr = state.qr
    val rendered = remember(qr?.sessionId) {
        qr?.let {
            try {
                QrRenderResult(
                    painter = QrCodePainter(
                        data = it.sessionId,
                        options = QrOptions(
                            errorCorrectionLevel = QrErrorCorrectionLevel.High,
                            // Four clear modules even for the smallest (21-module) QR symbol.
                            scale = 0.72f,
                        ),
                    ),
                )
            } catch (failure: Exception) {
                // An unencodable server payload must not crash the screen or retain the previous QR.
                QrRenderResult(error = failure.toPocketError(ErrorOperation.RENDER_QR, ErrorRecovery.QR))
            }
        }
    }
    val painter = rendered?.painter
    val error = state.error ?: rendered?.error
    if (error == null) MaximizeQrBrightness()
    Column(
        modifier = if (error == null) Modifier.keepScreenOn() else Modifier,
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        ScreenHeading("Ваш QR")
        if (error != null) {
            ErrorCard(error, { if (state.error != null) onRecoverError(error) else onRefresh() }, enabled = !state.busy)
        } else {
            Surface(shape = RoundedCornerShape(36.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        PocketIcon(
                            R.drawable.materialsymbols_ic_verified_user_rounded,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                        Text("DIGITAL ID", style = MaterialTheme.typography.labelLarge)
                    }
                    Surface(
                        shape = RoundedCornerShape(24.dp),
                        color = if (painter != null) Color.White else MaterialTheme.colorScheme.surfaceContainerHigh,
                    ) {
                        Box(Modifier.fillMaxWidth().aspectRatio(1f), contentAlignment = Alignment.Center) {
                            if (painter != null) {
                                Image(
                                    painter = painter,
                                    contentDescription = "QR Digital ID для сканирования",
                                    modifier = Modifier.fillMaxWidth().aspectRatio(1f),
                                )
                            } else {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(12.dp),
                                ) {
                                    if (qr == null) {
                                        LoadingIndicator(
                                            Modifier.size(64.dp),
                                            color = MaterialTheme.colorScheme.primary,
                                        )
                                    } else {
                                        PocketIcon(
                                            Symbols.drawable.materialsymbols_ic_info_rounded,
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    if (qr != null) {
                                        Text(
                                            "Не удалось показать QR",
                                            color = MaterialTheme.colorScheme.onSurface,
                                            style = MaterialTheme.typography.bodyMedium,
                                        )
                                    }
                                }
                            }
                        }
                    }
                    QrRefreshCountdown(state.qrRefreshAtMillis, state.qrRefreshIntervalMillis)
                }
            }
        }
        if (error == null) {
            FilledTonalButton(
                onClick = onRefresh,
                shapes = ButtonDefaults.shapes(),
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                enabled = !state.busy && qr != null,
            ) {
                PocketIcon(Symbols.drawable.materialsymbols_ic_refresh_rounded, tint = LocalContentColor.current)
                Text(
                    "Обновить QR",
                    modifier = Modifier.padding(start = 12.dp),
                    style = MaterialTheme.typography.titleMedium,
                )
            }
        }
        TextButton(onClick = onLock, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
            PocketIcon(Symbols.drawable.materialsymbols_ic_lock_rounded, tint = LocalContentColor.current)
            Text("Завершить сеанс", modifier = Modifier.padding(start = 8.dp))
        }
    }
}

@Composable
private fun MaximizeQrBrightness() {
    val window = LocalActivity.current?.window ?: return
    LifecycleResumeEffect(window) {
        val previousBrightness = window.attributes.screenBrightness
        window.attributes = window.attributes.apply {
            screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_FULL
        }
        onPauseOrDispose {
            window.attributes = window.attributes.apply {
                screenBrightness = previousBrightness
            }
        }
    }
}

@Composable
private fun QrRefreshCountdown(
    deadlineMillis: Long?,
    intervalMillis: Long,
) {
    fun remainingMillis(): Long = deadlineMillis?.let { (it - System.nanoTime() / 1_000_000).coerceAtLeast(0) } ?: 0
    val remaining by produceState(remainingMillis(), deadlineMillis) {
        do {
            value = remainingMillis()
            if (value > 0) delay(50)
        } while (value > 0)
    }
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        LinearProgressIndicator(
            progress = { if (intervalMillis > 0) (remaining.toFloat() / intervalMillis).coerceIn(0f, 1f) else 0f },
            modifier = Modifier.fillMaxWidth().height(4.dp).semantics { contentDescription = "До обновления QR" },
            drawStopIndicator = {},
        )
        Text(
            if (remaining > 0) "Обновление через ${(remaining + 999) / 1000} с" else "Обновляем QR…",
            style = MaterialTheme.typography.labelMedium,
        )
    }
}

private data class QrRenderResult(
    val painter: QrCodePainter? = null,
    val error: PocketError? = null,
)
