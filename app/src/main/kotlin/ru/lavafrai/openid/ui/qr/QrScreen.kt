package ru.lavafrai.openid.ui.qr

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import ru.lavafrai.openid.ui.PocketUiState
import ru.lavafrai.openid.ui.components.ActionButton
import ru.lavafrai.openid.ui.components.MockView

@Composable
fun QrScreen(
    state: PocketUiState,
    onSettings: () -> Unit,
    onLock: () -> Unit,
) {
    MockView(if (state.qr != null) "QR-код · макет\nСессия получена" else "QR-код скрыт\nПолучаем обновление…", 240.dp)
    Text("Обновление запрашивается каждые 15 секунд, пока открыта сессия.")
    Text("Рисование самого QR будет подключено позже.")
    ActionButton("Настройки", !state.busy, onSettings)
    ActionButton("Заблокировать", !state.busy, onLock)
}
