package ru.lavafrai.openid.ui.unlock

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import ru.lavafrai.openid.models.pin.PinProtection
import ru.lavafrai.openid.models.pin.SavedPinState
import ru.lavafrai.openid.ui.PocketUiState
import ru.lavafrai.openid.ui.components.ActionButton
import ru.lavafrai.openid.ui.components.MockView

@Composable
fun UnlockScreen(
    state: PocketUiState,
    onPinChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onSavedPin: () -> Unit,
    onDelete: () -> Unit,
    onMigrate: () -> Unit,
    onSubmitWithoutSaving: () -> Unit,
) {
    MockView("Профиль #${state.account?.accountId ?: "—"}")
    if (state.accountMigrationRequired) {
        Text(
            "Нужно один раз подтвердить доступ к старому профилю. " +
                "Обновим его хранение; дальше подтверждение будет нужно только для сохранённого PIN.",
        )
        ActionButton("Обновить хранилище профиля", !state.busy, onMigrate)
        TextButton(onClick = onDelete) { Text("Удалить секреты с устройства") }
        return
    }
    val saved = (state.savedPin as? SavedPinState.Saved)?.takeIf { it.accountId == state.account?.accountId }
    val canSubmit = !state.busy && state.retrySeconds == 0
    if (saved != null) {
        Text("PIN Digital ID сохранён. Вводить его вручную не требуется.")
        ActionButton(
            "Получить QR с биометрией",
            canSubmit,
            onSavedPin,
        )
        Text("Либо введите PIN Digital ID вручную:")
    } else {
        Text(
            when (state.pinProtection) {
                PinProtection.NOT_SAVED -> "PIN Digital ID не сохранён — для входа введите его вручную."

                PinProtection.BIOMETRIC ->
                    "Введите PIN Digital ID один раз. После проверки подтвердите его сохранение биометрией. " +
                        "При следующих входах ручной ввод не понадобится."
            },
        )
    }
    Text("Это PIN Digital ID из четырёх цифр, а не пароль ссылки или телефона.")
    OutlinedTextField(
        value = state.pin,
        onValueChange = onPinChange,
        modifier = Modifier.fillMaxWidth(),
        label = { Text("PIN · 4 цифры") },
        enabled = !state.busy && state.retrySeconds == 0,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
        singleLine = true,
    )
    if (state.retrySeconds > 0) Text("Следующая попытка через ${state.retrySeconds} с")
    ActionButton(
        if (saved == null &&
            state.pinProtection != PinProtection.NOT_SAVED
        ) {
            "Сохранить PIN и получить QR"
        } else {
            "Получить QR с введённым PIN"
        },
        canSubmit && state.pin.length == 4,
        onSubmit,
    )
    if (saved == null && state.pinProtection == PinProtection.BIOMETRIC) {
        ActionButton("Получить QR без сохранения PIN", canSubmit && state.pin.length == 4, onSubmitWithoutSaving)
    }
    TextButton(onClick = onDelete) { Text("Удалить секреты с устройства") }
}
