package ru.lavafrai.openid.ui.importing

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import ru.lavafrai.openid.ui.ImportForm
import ru.lavafrai.openid.ui.components.ActionButton
import ru.lavafrai.openid.ui.components.MockView

@Composable
fun ImportScreen(
    form: ImportForm,
    replacing: Boolean,
    busy: Boolean,
    onLinkChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onNext: () -> Unit,
) {
    MockView(if (replacing) "Заменить импортированный профиль" else "Добавить профиль по ссылке")
    Text("Проверка и сохранение выполнятся после выбора защиты.")
    OutlinedTextField(
        value = form.link,
        onValueChange = onLinkChange,
        modifier = Modifier.fillMaxWidth(),
        label = { Text("Ссылка импорта") },
        enabled = !busy,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
    )
    OutlinedTextField(
        value = form.password,
        onValueChange = onPasswordChange,
        modifier = Modifier.fillMaxWidth(),
        label = { Text("Пароль ссылки") },
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        enabled = !busy,
        singleLine = true,
    )
    ActionButton("Далее", !busy && form.link.isNotBlank() && form.password.isNotEmpty(), onNext)
}
