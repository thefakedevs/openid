package ru.lavafrai.openid.models.pin

import ru.lavafrai.openid.models.auth.CryptoAuthenticator

sealed interface PinSource {
    /** UI transfers ownership of the returned buffer; the use case clears it on failure or close. */
    class Manual(val requestPin: suspend () -> CharArray) : PinSource

    class Saved(val authenticator: CryptoAuthenticator) : PinSource
}
