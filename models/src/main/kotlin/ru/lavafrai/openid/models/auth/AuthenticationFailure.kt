package ru.lavafrai.openid.models.auth

/** Safe failure codes shared by storage, the domain flow and the authentication UI. */
enum class AuthenticationFailure {
    NO_SCREEN_LOCK,
    NO_BIOMETRIC_HARDWARE,
    NOT_ENROLLED,
    TEMPORARILY_UNAVAILABLE,
    LOCKED_OUT,
    SECURITY_UPDATE_REQUIRED,
    UNSUPPORTED,
    KEY_INVALIDATED,
    NOT_AUTHENTICATED,
    FAILED,
}

class AuthenticationException(val reason: AuthenticationFailure) :
    IllegalStateException("Authentication failed: $reason")
