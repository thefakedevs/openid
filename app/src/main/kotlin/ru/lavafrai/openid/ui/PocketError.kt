package ru.lavafrai.openid.ui

import ru.lavafrai.openid.domain.models.QrGenerationFailure
import ru.lavafrai.openid.domain.models.QrGenerationStage
import ru.lavafrai.openid.domain.models.QrGenerationState
import ru.lavafrai.openid.domain.usecases.DigitalIdPreflightFailure
import ru.lavafrai.openid.models.auth.AuthenticationException
import ru.lavafrai.openid.models.errors.FailureDiagnostic
import ru.lavafrai.openid.models.errors.FailureKind
import ru.lavafrai.openid.models.errors.FailureReason

enum class ErrorOperation(val label: String) {
    STARTUP("Открытие хранилища"),
    IMPORT("Импорт профиля"),
    UNLOCK("Вход в Digital ID"),
    QR("Получение QR"),
    SETTINGS("Сохранение настроек"),
    DELETE("Удаление профиля"),
    MIGRATION("Обновление хранилища"),
    RENDER_QR("Отображение QR"),
}

enum class ErrorRecovery(val label: String) {
    STARTUP("Повторить"),
    EDIT_IMPORT("Исправить данные"),
    IMPORT("Повторить импорт"),
    BIOMETRIC("Повторить вход"),
    UNLOCK("Войти снова"),
    QR("Повторить запрос"),
    SETTINGS("Повторить сохранение"),
    DELETE("Повторить удаление"),
    MIGRATION("Повторить обновление"),
    REPLACE("Заменить профиль"),
}

data class PocketError(
    val title: String,
    val description: String,
    val diagnostic: FailureDiagnostic,
    val operation: ErrorOperation,
    val recovery: ErrorRecovery? = null,
    val stage: QrGenerationStage? = null,
) {
    val report: String get() = buildString {
        appendLine("Pocket ID · диагностика")
        appendLine("Операция: ${operation.label}")
        stage?.let { appendLine("Этап: ${it.name}") }
        appendLine("Код: ${diagnostic.code}")
        appendLine("Категория: ${diagnostic.kind}")
        appendLine("Сообщения исключений и данные запросов исключены из отчёта.")
        appendLine()
        append(diagnostic.trace)
    }
}

fun Throwable.toPocketError(
    operation: ErrorOperation,
    recovery: ErrorRecovery? = null,
): PocketError {
    val error = FailureDiagnostic.from(this).toPocketError(operation, recovery)
    return if (this is AuthenticationException) {
        error.copy(title = "Не удалось подтвердить доступ", description = reason.message())
    } else {
        error
    }
}

fun FailureDiagnostic.toPocketError(
    operation: ErrorOperation,
    recovery: ErrorRecovery? = null,
): PocketError {
    val (title, description) = when {
        reason == FailureReason.IMPORT_DECRYPTION_FAILED ->
            "Не удалось расшифровать ссылку" to "Проверьте пароль и убедитесь, что ссылка скопирована целиком."

        reason == FailureReason.INVALID_IMPORT_PASSWORD ->
            "Неподходящий пароль ссылки" to "Нужно от 12 до 256 латинских букв, цифр или печатных ASCII-символов."

        reason == FailureReason.INVALID_IMPORT_LINK ->
            "Неверная ссылка импорта" to "Скопируйте полную ссылку didpocket://import/v1 вместе с данными после #."

        reason == FailureReason.STORAGE_KEY_MISSING ->
            "Ключ хранилища недоступен" to
                "Android больше не может открыть эти секреты. Понадобится повторный импорт профиля."

        kind == FailureKind.NETWORK ->
            "Нет связи с сервисом" to "Проверьте интернет или VPN и повторите попытку."

        kind == FailureKind.TIMEOUT ->
            "Сервис не ответил вовремя" to "Время ожидания истекло. Попробуйте ещё раз."

        kind == FailureKind.TLS ->
            "Не удалось защитить соединение" to "Проверьте дату и время на телефоне или попробуйте другую сеть."

        kind == FailureKind.RATE_LIMIT ->
            "Слишком много запросов" to "Сервис просит подождать. Повторите попытку позже."

        kind == FailureKind.ACCESS_DENIED ->
            "Сервис отклонил доступ" to "Войдите снова. Если ошибка повторится, обновите импортированный профиль."

        kind == FailureKind.SERVER ->
            "Сервис не выполнил запрос" to "Попробуйте позже. Если ошибка повторяется, откройте подробности."

        kind == FailureKind.STORAGE || operation == ErrorOperation.STARTUP ->
            "Хранилище недоступно" to
                "Не удалось прочитать или записать секреты. Проверьте свободное место и повторите попытку."

        operation == ErrorOperation.RENDER_QR ->
            "Не удалось показать QR" to "Полученный код не удалось отобразить. Запросите новый."

        kind == FailureKind.INVALID_DATA && operation == ErrorOperation.IMPORT ->
            "Данные импорта не подходят" to
                "Ссылка повреждена или содержит неподдерживаемые данные. Получите новую ссылку."

        kind == FailureKind.INVALID_DATA ->
            "Неожиданный ответ сервиса" to
                "Полученные данные не удалось обработать. Повторите попытку; при повторении скопируйте отчёт."

        else ->
            when (operation) {
                ErrorOperation.IMPORT -> "Не удалось импортировать профиль"
                ErrorOperation.SETTINGS -> "Не удалось сохранить настройки"
                ErrorOperation.DELETE -> "Не удалось удалить профиль"
                ErrorOperation.MIGRATION -> "Не удалось обновить хранилище"
                ErrorOperation.UNLOCK -> "Не удалось войти"
                ErrorOperation.QR, ErrorOperation.RENDER_QR -> "Не удалось получить QR"
                ErrorOperation.STARTUP -> "Не удалось открыть хранилище"
            } to
                "Попробуйте ещё раз. Если ошибка повторится, скопируйте отчёт из подробностей."
    }
    val action = if (operation == ErrorOperation.IMPORT && kind == FailureKind.INVALID_DATA) {
        ErrorRecovery.EDIT_IMPORT
    } else {
        recovery
    }
    return PocketError(title, description, this, operation, action)
}

fun QrGenerationState.Failed.toPocketError(recovery: ErrorRecovery?): PocketError {
    val operation = if (stage in setOf(QrGenerationStage.GENERATING_QR, QrGenerationStage.REFRESHING_QR)) {
        ErrorOperation.QR
    } else {
        ErrorOperation.UNLOCK
    }
    val base = diagnostic.toPocketError(operation, recovery).copy(stage = stage)
    if (stage == QrGenerationStage.UNLOCKING_ACCOUNT && diagnostic.reason == FailureReason.STORAGE_KEY_MISSING) {
        return base.copy(recovery = ErrorRecovery.REPLACE)
    }
    if (stage == QrGenerationStage.GETTING_PIN &&
        diagnostic.reason in setOf(FailureReason.BIOMETRIC_KEY_INVALIDATED, FailureReason.STORAGE_KEY_MISSING)
    ) {
        return base.copy(
            title = "Сохранённый PIN недоступен",
            description = "Android сбросил его защиту. Введите PIN вручную, чтобы сохранить его заново.",
            recovery = null,
        )
    }
    if (stage == QrGenerationStage.SAVING_PIN) {
        val detail = (reason as? QrGenerationFailure.Authentication)?.reason?.message() ?: base.description
        return base.copy(title = "Не удалось сохранить PIN", description = "$detail Можно войти без сохранения PIN.")
    }
    return when (val failure = reason) {
        is QrGenerationFailure.Authentication -> base.copy(
            title = "Биометрия недоступна",
            description = failure.reason.message(),
        )

        QrGenerationFailure.AuthorizationRejected -> base.copy(
            title = "PIN не принят",
            description = "Проверьте PIN Digital ID. Следующая попытка будет доступна после паузы.",
            recovery = null,
        )

        QrGenerationFailure.InvalidPin -> base.copy(
            title = "Проверьте PIN",
            description = "PIN Digital ID должен содержать четыре цифры.",
            recovery = null,
        )

        QrGenerationFailure.AlreadyRunning -> base.copy(
            title = "Вход уже выполняется",
            description = "Дождитесь завершения текущего запроса.",
            recovery = null,
        )

        QrGenerationFailure.OperationFailed -> base

        is QrGenerationFailure.Preflight -> when (failure.reason) {
            DigitalIdPreflightFailure.ATTEMPTS_EXHAUSTED -> base.copy(
                title = "Попытки PIN исчерпаны",
                description = "Digital ID не разрешает новые попытки. Проверьте доступ в приложении MAX.",
                recovery = null,
            )

            DigitalIdPreflightFailure.COOLDOWN -> base.copy(
                title = "Нужно подождать",
                description = "Digital ID временно ограничил ввод PIN. Попробуйте позже.",
            )

            DigitalIdPreflightFailure.DEVICE_MISMATCH, DigitalIdPreflightFailure.TIMESTAMP_MISMATCH -> base.copy(
                title = "Профиль изменился",
                description = "Импортируйте свежую ссылку из Digital ID.",
                recovery = ErrorRecovery.REPLACE,
            )

            DigitalIdPreflightFailure.PIN_SALT_MISSING, DigitalIdPreflightFailure.UNSUPPORTED_PROFILE -> base.copy(
                title = "Профиль не поддерживает вход",
                description = "В импорте нет подходящих данных Digital ID. Получите свежую ссылку.",
                recovery = ErrorRecovery.REPLACE,
            )
        }
    }
}
