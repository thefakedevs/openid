package ru.lavafrai.openid.ui.setup

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import ru.lavafrai.openid.models.pin.PinProtection
import ru.lavafrai.openid.ui.components.ActionButton
import ru.lavafrai.openid.ui.components.MockView
import ru.lavafrai.openid.ui.components.PinProtectionChoices

@Composable
fun SetupScreen(
    pinProtection: PinProtection,
    busy: Boolean,
    replacing: Boolean,
    onPinProtection: (PinProtection) -> Unit,
    onSave: () -> Unit,
) {
    MockView("Выберите способ входа")
    Text("Профиль хранится зашифрованным на этом телефоне и открывается автоматически.")
    PinProtectionChoices(pinProtection, !busy, onPinProtection)
    Text("Если выбрана биометрия, PIN сохранится после первого успешного ввода и биометрического подтверждения.")
    if (replacing) Text("После замены прежний сохранённый PIN будет удалён.")
    ActionButton(if (replacing) "Заменить профиль" else "Сохранить профиль", !busy, onSave)
}
