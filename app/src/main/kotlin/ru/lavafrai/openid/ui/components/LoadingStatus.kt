package ru.lavafrai.openid.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import ru.lavafrai.openid.domain.models.QrGenerationStage
import ru.lavafrai.openid.navigation.PocketDestination
import ru.lavafrai.openid.ui.PocketUiState

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun LoadingStatus(state: PocketUiState) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        LoadingIndicator(Modifier.size(40.dp))
        Text(
            when (state.destination) {
                PocketDestination.IMPORT -> "Проверяем ссылку…"
                PocketDestination.SETUP -> "Импортируем профиль…"
                PocketDestination.SETTINGS -> "Применяем настройки…"
                else -> state.qrStage?.label() ?: "Подготавливаем вход…"
            },
            style = MaterialTheme.typography.bodyMedium,
        )
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
    QrGenerationStage.SAVING_PIN -> "Сохраняем PIN…"
    QrGenerationStage.GENERATING_QR -> "Получаем QR…"
    QrGenerationStage.REFRESHING_QR -> "Обновляем QR…"
}
