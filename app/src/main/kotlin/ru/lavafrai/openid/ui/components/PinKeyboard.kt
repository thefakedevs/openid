package ru.lavafrai.openid.ui.components

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.password
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.composables.icons.materialsymbols.rounded.R as Symbols

@Composable
fun PinDots(length: Int) {
    Row(
        modifier = Modifier.fillMaxWidth().height(44.dp).clearAndSetSemantics {
            contentDescription = "PIN Digital ID"
            stateDescription = "Введено $length из 4 цифр"
            password()
        },
        horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(4) { index ->
            val size by animateDpAsState(
                if (index < length) 22.dp else 16.dp,
                animationSpec = MaterialTheme.motionScheme.fastSpatialSpec(),
                label = "pinDotSize",
            )
            Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
                Box(
                    Modifier.size(size).background(
                        if (index <
                            length
                        ) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.surfaceContainerHighest
                        },
                        CircleShape,
                    ),
                )
            }
        }
    }
}

@Composable
fun PinKeyboard(
    value: String,
    onValueChange: (String) -> Unit,
    enabled: Boolean,
    onBiometric: (() -> Unit)? = null,
    keyMinHeight: Dp = 64.dp,
) {
    val haptics = LocalHapticFeedback.current
    fun enter(digit: Int) {
        if (enabled && value.length < 4) {
            haptics.performHapticFeedback(HapticFeedbackType.KeyboardTap)
            onValueChange(value + digit)
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (row in 0..2) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (column in 1..3) {
                    val digit = row * 3 + column
                    PinKey(Modifier.weight(1f).heightIn(min = keyMinHeight), enabled && value.length < 4, {
                        enter(digit)
                    }) {
                        Text(digit.toString(), style = MaterialTheme.typography.headlineLarge)
                    }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (onBiometric != null) {
                PinKey(Modifier.weight(1f).heightIn(min = keyMinHeight), enabled, onBiometric) {
                    PocketIcon(
                        Symbols.drawable.materialsymbols_ic_fingerprint_rounded,
                        description = "Войти по биометрии",
                        tint = LocalContentColor.current,
                    )
                }
            } else {
                Spacer(Modifier.weight(1f))
            }
            PinKey(Modifier.weight(1f).heightIn(min = keyMinHeight), enabled && value.length < 4, { enter(0) }) {
                Text("0", style = MaterialTheme.typography.headlineLarge)
            }
            PinKey(Modifier.weight(1f).heightIn(min = keyMinHeight), enabled && value.isNotEmpty(), {
                haptics.performHapticFeedback(HapticFeedbackType.KeyboardTap)
                onValueChange(value.dropLast(1))
            }) {
                PocketIcon(
                    Symbols.drawable.materialsymbols_ic_backspace_rounded,
                    description = "Удалить последнюю цифру",
                    tint = LocalContentColor.current,
                )
            }
        }
    }
}

@Composable
private fun PinKey(
    modifier: Modifier,
    enabled: Boolean,
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    FilledTonalButton(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        shapes = ButtonDefaults.shapes(shape = RoundedCornerShape(32.dp), pressedShape = RoundedCornerShape(18.dp)),
        colors = ButtonDefaults.filledTonalButtonColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
        contentPadding = PaddingValues(0.dp),
    ) { content() }
}
