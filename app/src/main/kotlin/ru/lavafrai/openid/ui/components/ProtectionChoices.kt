package ru.lavafrai.openid.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import ru.lavafrai.openid.data.core.VaultAuthentication
import ru.lavafrai.openid.data.core.VaultProtection
import ru.lavafrai.openid.models.auth.AuthenticationFailure
import ru.lavafrai.openid.models.pin.PinProtection
import ru.lavafrai.openid.ui.message

fun PinProtection.label(): String = when (this) {
    PinProtection.NOT_SAVED -> "Вводить PIN каждый раз"
    PinProtection.BIOMETRIC -> "Сохранить PIN с биометрией"
}

@Composable
fun PinProtectionChoices(
    selected: PinProtection,
    enabled: Boolean,
    onSelect: (PinProtection) -> Unit,
) {
    Text("Хранение PIN")
    val unavailable = unavailable(VaultProtection.BIOMETRIC)
    Column(Modifier.selectableGroup()) {
        PinProtection.entries.forEach { value ->
            val reason = if (value == PinProtection.BIOMETRIC) unavailable else null
            val description = when (value) {
                PinProtection.NOT_SAVED -> "PIN Digital ID не сохраняется на телефоне."

                PinProtection.BIOMETRIC ->
                    "Сохраняем проверенный PIN зашифрованным. " +
                        "Для доступа нужна поддерживаемая биометрия."
            }
            Choice(value.label(), reason?.message() ?: description, value == selected, enabled && reason == null) {
                onSelect(value)
            }
        }
    }
}

@Composable
private fun Choice(
    label: String,
    description: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null, enabled = enabled)
        Column {
            Text(label)
            Text(description, style = MaterialTheme.typography.bodySmall)
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
