package ru.lavafrai.openid.ui.setup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import ru.lavafrai.openid.models.pin.PinProtection
import ru.lavafrai.openid.ui.components.PinProtectionChoices
import ru.lavafrai.openid.ui.components.PrimaryButton
import ru.lavafrai.openid.ui.components.ScreenHeading

@Composable
fun SetupScreen(
    pinProtection: PinProtection,
    busy: Boolean,
    replacing: Boolean,
    onPinProtection: (PinProtection) -> Unit,
    onSave: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(28.dp)) {
        ScreenHeading("Способ входа", "Выберите, как подтверждать доступ к QR с помощью PIN Digital ID.")
        PinProtectionChoices(pinProtection, !busy, onPinProtection)
        if (pinProtection == PinProtection.BIOMETRIC) {
            Text(
                "Сначала проверим ваш PIN. Затем Android попросит биометрию для его сохранения.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (replacing) {
            Text(
                "После замены прежний профиль и сохранённый PIN будут удалены.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        PrimaryButton(if (replacing) "Заменить профиль" else "Сохранить профиль", onSave, enabled = !busy)
    }
}
