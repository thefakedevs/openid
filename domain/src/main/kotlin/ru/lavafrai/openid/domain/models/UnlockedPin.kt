package ru.lavafrai.openid.domain.models

/** An owned plaintext buffer. Copies returned to callers must also be cleared after use. */
class UnlockedPin internal constructor(
    val accountId: Long,
    private val buffer: CharArray,
) : AutoCloseable {
    private var closed = false

    @Synchronized
    fun copyPin(): CharArray {
        check(!closed) { "PIN is closed" }
        return buffer.copyOf()
    }

    @Synchronized
    override fun close() {
        buffer.fill('\u0000')
        closed = true
    }

    override fun toString(): String = "UnlockedPin(accountId=$accountId, pin=<redacted>)"
}

class InvalidPinException : IllegalArgumentException("Digital ID PIN must contain four ASCII digits")

internal fun validatePin(pin: CharArray) {
    if (pin.size != 4 || pin.any { it !in '0'..'9' }) throw InvalidPinException()
}
