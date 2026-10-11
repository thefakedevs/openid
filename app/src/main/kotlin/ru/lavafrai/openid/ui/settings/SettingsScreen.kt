package ru.lavafrai.openid.ui.settings

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import ru.lavafrai.openid.models.pin.PinProtection
import ru.lavafrai.openid.models.pin.SavedPinState
import ru.lavafrai.openid.ui.PocketUiState
import ru.lavafrai.openid.ui.components.ActionButton
import ru.lavafrai.openid.ui.components.MockView
import ru.lavafrai.openid.ui.components.PinProtectionChoices

@Composable
fun SettingsScreen(
    state: PocketUiState,
    onPinProtection: (PinProtection) -> Unit,
    onApply: () -> Unit,
    onReplace: () -> Unit,
    onDelete: () -> Unit,
) {
    MockView("Настройки профиля #${state.account?.accountId ?: "—"}")
    val saved = state.savedPin as? SavedPinState.Saved
    Text(
        if (saved == null) {
            "PIN пока не сохранён. Если выберете сохранение, " +
                "после применения настройки введите его один раз на экране разблокировки."
        } else {
            "PIN сохранён с биометрией."
        },
    )
    Text("Профиль хранится зашифрованным и открывается автоматически.")
    PinProtectionChoices(state.pinProtection, !state.busy, onPinProtection)
    ActionButton("Применить способ входа", !state.busy, onApply)
    ActionButton("Заменить аккаунт", !state.busy, onReplace)
    ActionButton("Удалить аккаунт и секреты", !state.busy, onDelete)
}
