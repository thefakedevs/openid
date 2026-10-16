package ru.lavafrai.openid.ui

import ru.lavafrai.openid.R
import ru.lavafrai.openid.models.auth.AuthenticationFailure

fun AuthenticationFailure.message(): UiText = when (this) {
    AuthenticationFailure.NO_SCREEN_LOCK ->
        UiText(R.string.auth_no_screen_lock)

    AuthenticationFailure.NO_BIOMETRIC_HARDWARE -> UiText(R.string.auth_no_hardware)

    AuthenticationFailure.NOT_ENROLLED ->
        UiText(R.string.auth_not_enrolled)

    AuthenticationFailure.TEMPORARILY_UNAVAILABLE -> UiText(R.string.auth_unavailable)

    AuthenticationFailure.LOCKED_OUT ->
        UiText(R.string.auth_locked_out)

    AuthenticationFailure.SECURITY_UPDATE_REQUIRED -> UiText(R.string.auth_update_required)

    AuthenticationFailure.UNSUPPORTED -> UiText(R.string.auth_unsupported)

    AuthenticationFailure.KEY_INVALIDATED ->
        UiText(R.string.auth_key_invalidated)

    AuthenticationFailure.NOT_AUTHENTICATED -> UiText(R.string.auth_not_authenticated)

    AuthenticationFailure.FAILED -> UiText(R.string.auth_failed)
}
