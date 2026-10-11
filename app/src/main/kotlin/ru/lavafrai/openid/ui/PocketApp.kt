package ru.lavafrai.openid.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ru.lavafrai.openid.domain.models.QrGenerationStage
import ru.lavafrai.openid.navigation.PocketNavigation

@Composable
fun PocketApp(viewModel: PocketViewModel) {
    val controller = viewModel.controller
    val state by controller.state.collectAsStateWithLifecycle()
    val owner = LocalLifecycleOwner.current
    val scroll = rememberScrollState()
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(state.destination) {
        focus.clearFocus()
        keyboard?.hide()
        scroll.scrollTo(0)
    }
    LaunchedEffect(state.busy) {
        if (state.busy) {
            focus.clearFocus()
            keyboard?.hide()
        }
    }
    DisposableEffect(owner, controller) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP && controller.state.value.authPrompt == null) controller.onBackground()
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    BackHandler(state.hasBack || state.busy) { if (!state.busy) controller.dispatch(PocketAction.Back) }
    Scaffold(Modifier.fillMaxSize()) { insets ->
        Column(
            Modifier.fillMaxSize().padding(insets).imePadding().verticalScroll(scroll).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                if (state.hasBack) {
                    TextButton(
                        onClick = { controller.dispatch(PocketAction.Back) },
                        enabled = !state.busy,
                    ) { Text("Назад") }
                }
                Text(state.destination.title, style = MaterialTheme.typography.headlineSmall)
            }
            state.message?.let { Text(it) }
            if (state.busy || state.qrStage != null) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(state.qrStage?.label() ?: "Выполняем действие…")
            }
            PocketNavigation(state, controller::dispatch)
        }
    }
    if (state.confirmDelete) {
        AlertDialog(
            onDismissRequest = { controller.dispatch(PocketAction.DismissDelete) },
            title = { Text("Удалить секреты?") },
            text = {
                Text(
                    "С устройства будут удалены credentials аккаунта, сохранённый PIN и связанные ключи. Для входа понадобится новый импорт.",
                )
            },
            confirmButton = {
                TextButton(onClick = { controller.dispatch(PocketAction.ConfirmDelete) }) { Text("Удалить") }
            },
            dismissButton = {
                TextButton(onClick = { controller.dispatch(PocketAction.DismissDelete) }) { Text("Отмена") }
            },
        )
    } else {
        state.authPrompt?.let { request -> SystemAuthentication(request, controller::dispatch) }
    }
}

private fun QrGenerationStage.label(): String = when (this) {
    QrGenerationStage.UNLOCKING_ACCOUNT -> "Разблокируем профиль…"
    QrGenerationStage.CONNECTING_MAX -> "Подключаемся…"
    QrGenerationStage.LOGGING_IN -> "Входим в аккаунт…"
    QrGenerationStage.RESOLVING_BOT -> "Находим Digital ID…"
    QrGenerationStage.GETTING_LAUNCH -> "Открываем Digital ID…"
    QrGenerationStage.CHECKING_SECURITY -> "Проверяем доступность авторизации…"
    QrGenerationStage.GETTING_PIN -> "Получаем PIN…"
    QrGenerationStage.AUTHORIZING -> "Проверяем PIN…"
    QrGenerationStage.GENERATING_QR -> "Получаем QR…"
    QrGenerationStage.REFRESHING_QR -> "Обновляем QR…"
}
