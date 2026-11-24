package ru.lavafrai.openid.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
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
    tokenProfile: Boolean = false,
) {
    val unavailable = unavailable(VaultProtection.BIOMETRIC)
    Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(PinProtection.BIOMETRIC, PinProtection.NOT_SAVED).forEach { value ->
            val reason = if (value == PinProtection.BIOMETRIC) unavailable else null
            val description = stringResource(
                when {
                    tokenProfile && value == PinProtection.NOT_SAVED -> R.string.token_lock_manual_description
                    tokenProfile -> R.string.token_lock_biometric_description
                    value == PinProtection.NOT_SAVED -> R.string.pin_manual_description
                    else -> R.string.pin_biometric_description
                },
            )
            ChoiceCard(
                title = value.label(),
                description = reason?.message()?.resolve() ?: description,
                selected = value == selected,
                enabled = enabled && reason == null,
                onClick = { onSelect(value) },
                icon = if (value == PinProtection.BIOMETRIC) {
                    Symbols.drawable.materialsymbols_ic_fingerprint_rounded
                } else {
                    Symbols.drawable.materialsymbols_ic_dialpad_rounded
                },
            )
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
