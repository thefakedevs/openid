package ru.lavafrai.openid.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.composables.icons.materialsymbols.rounded.R as Symbols
import ru.lavafrai.openid.R
import ru.lavafrai.openid.data.core.VaultAuthentication
import ru.lavafrai.openid.data.core.VaultProtection
import ru.lavafrai.openid.models.auth.AuthenticationFailure
import ru.lavafrai.openid.models.pin.PinProtection
import ru.lavafrai.openid.ui.message
import ru.lavafrai.openid.ui.resolve

@Composable
fun PinProtection.label(): String = when (this) {
    PinProtection.NOT_SAVED -> stringResource(R.string.pin_manual_label)
    PinProtection.BIOMETRIC -> stringResource(R.string.pin_biometric_label)
}

@Composable
fun PinProtectionChoices(
    selected: PinProtection,
    enabled: Boolean,
    onSelect: (PinProtection) -> Unit,
) {
    val unavailable = unavailable(VaultProtection.BIOMETRIC)
    Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(PinProtection.BIOMETRIC, PinProtection.NOT_SAVED).forEach { value ->
            val reason = if (value == PinProtection.BIOMETRIC) unavailable else null
            val description = when (value) {
                PinProtection.NOT_SAVED -> stringResource(R.string.pin_manual_description)

                PinProtection.BIOMETRIC ->
                    stringResource(R.string.pin_biometric_description)
            }
            Choice(value, reason?.message()?.resolve() ?: description, value == selected, enabled && reason == null) {
                onSelect(value)
            }
        }
    }
}

@Composable
private fun Choice(
    protection: PinProtection,
    description: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val color by animateColorAsState(
        if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer,
        animationSpec = MaterialTheme.motionScheme.fastEffectsSpec(),
        label = "protectionColor",
    )
    Surface(shape = RoundedCornerShape(28.dp), color = color) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 112.dp)
                .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
                .padding(20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            PocketIcon(
                if (protection == PinProtection.BIOMETRIC) {
                    Symbols.drawable.materialsymbols_ic_fingerprint_rounded
                } else {
                    Symbols.drawable.materialsymbols_ic_dialpad_rounded
                },
                tint = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(protection.label(), style = MaterialTheme.typography.titleMedium)
                Text(
                    description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            RadioButton(selected = selected, onClick = null, enabled = enabled)
        }
    }
}

@Composable
private fun unavailable(mode: VaultProtection): AuthenticationFailure? {
    val context = LocalContext.current
    var reason by remember(context, mode) { mutableStateOf(VaultAuthentication.unavailable(context, mode)) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        reason = VaultAuthentication.unavailable(context, mode)
    }
    return reason
}
