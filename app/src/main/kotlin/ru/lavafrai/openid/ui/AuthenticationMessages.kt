package ru.lavafrai.openid.ui

import ru.lavafrai.openid.models.auth.AuthenticationFailure

fun AuthenticationFailure.message(): String = when (this) {
    AuthenticationFailure.NO_SCREEN_LOCK ->
        "Настройте PIN, пароль или графический ключ экрана блокировки в настройках Android."

    AuthenticationFailure.NO_BIOMETRIC_HARDWARE -> "На этом телефоне нет биометрии, подходящей для защиты секретов."

    AuthenticationFailure.NOT_ENROLLED ->
        "Добавьте отпечаток пальца или другую поддерживаемую биометрию в настройках Android."

    AuthenticationFailure.TEMPORARILY_UNAVAILABLE -> "Биометрия временно недоступна. Попробуйте ещё раз."

    AuthenticationFailure.LOCKED_OUT ->
        "Биометрия заблокирована после неудачных попыток. " +
            "Разблокируйте телефон его PIN или паролем и повторите попытку."

    AuthenticationFailure.SECURITY_UPDATE_REQUIRED -> "Для защищённой биометрии требуется обновление Android."

    AuthenticationFailure.UNSUPPORTED -> "Этот способ подтверждения недоступен на вашей версии Android."

    AuthenticationFailure.KEY_INVALIDATED ->
        "Android сбросил защиту секретов после изменения блокировки или биометрии. " +
            "Удалите секреты и импортируйте профиль заново."

    AuthenticationFailure.NOT_AUTHENTICATED -> "Android не подтвердил доступ к хранилищу. Повторите разблокировку."

    AuthenticationFailure.FAILED -> "Не удалось подтвердить доступ к хранилищу. Повторите попытку."
}
