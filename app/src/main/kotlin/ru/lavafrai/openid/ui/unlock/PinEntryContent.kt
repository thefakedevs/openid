package ru.lavafrai.openid.ui.unlock

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.composables.icons.materialsymbols.rounded.R as Symbols
import ru.lavafrai.openid.R
import ru.lavafrai.openid.ui.PocketError
import ru.lavafrai.openid.ui.PocketUiState
import ru.lavafrai.openid.ui.components.InfoCard
import ru.lavafrai.openid.ui.components.LoadingStatus
import ru.lavafrai.openid.ui.components.PinDots
import ru.lavafrai.openid.ui.components.PinErrorCard
import ru.lavafrai.openid.ui.components.PinKeyboard
import ru.lavafrai.openid.ui.components.PrimaryButton
import ru.lavafrai.openid.ui.components.ScreenHeading
import ru.lavafrai.openid.ui.resolve

@Composable
internal fun PinEntryContent(
    state: PocketUiState,
    onRecoverError: (PocketError) -> Unit,
    onPinChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onBiometric: (() -> Unit)?,
    onHelp: () -> Unit,
) {
    val canSubmit = !state.busy && state.retrySeconds == 0
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val compact = maxHeight < 640.dp
        val gap = if (compact) 8.dp else 20.dp
        val scrollWholeScreen = LocalDensity.current.fontScale > 1.3f || maxHeight < 480.dp
        val status: @Composable () -> Unit = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (state.retrySeconds > 0) {
                    Text(
                        stringResource(R.string.pin_retry_countdown, state.retrySeconds),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (state.busy) LoadingStatus(state)
                val error = state.error
                if (error != null) {
                    PinErrorCard(error, { onRecoverError(error) }, enabled = canSubmit)
                } else {
                    ScreenHeading(
                        stringResource(R.string.pin_enter_title),
                        stringResource(R.string.pin_save_description).takeIf { state.willSavePin },
                    )
                }
                state.message?.let { message ->
                    InfoCard(
                        message.resolve(),
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                        icon = Symbols.drawable.materialsymbols_ic_info_rounded,
                    )
                }
            }
        }
        val controls: @Composable () -> Unit = {
            Column(
                Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(gap),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                PinDots(state.pin.length)
                PinKeyboard(
                    state.pin,
                    onPinChange,
                    canSubmit,
                    onBiometric,
                    keyMinHeight = if (compact) 56.dp else 64.dp,
                )
                PrimaryButton(
                    stringResource(if (state.willSavePin) R.string.pin_save_and_open else R.string.qr_open),
                    onSubmit,
                    enabled = canSubmit && state.pin.length == 4,
                    icon = Symbols.drawable.materialsymbols_ic_qr_code_2_rounded,
                )
                TextButton(onClick = onHelp, enabled = !state.busy) { Text(stringResource(R.string.pin_help_action)) }
            }
        }
        if (scrollWholeScreen) {
            // At large text sizes, use one continuous page instead of clipping the keypad footer.
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(gap),
            ) {
                status()
                controls()
            }
        } else {
            val statusScroll = rememberScrollState()
            LaunchedEffect(state.error, state.message, state.busy, state.retrySeconds > 0) {
                statusScroll.scrollTo(0)
            }
            Column(Modifier.fillMaxSize().padding(bottom = if (compact) 0.dp else 28.dp)) {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.BottomCenter) {
                    Column(Modifier.fillMaxWidth().verticalScroll(statusScroll)) { status() }
                }
                Spacer(Modifier.height(gap))
                controls()
            }
        }
    }
}
