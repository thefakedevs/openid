package ru.lavafrai.openid.models.digitalid

import java.math.BigDecimal

class DigitalIdToken(val value: String) {
    override fun toString(): String = "DigitalIdToken(<redacted>)"
}

/** The wire format permits a string, an integer or a finite decimal; no time unit is assumed. */
sealed interface DigitalIdTimestamp {
    /** Numeric values compare across wire types; text only matches identical text. */
    fun matches(other: DigitalIdTimestamp): Boolean = when (this) {
        is Text -> other is Text && value == other.value

        is Integer -> when (other) {
            is Text -> false
            is Integer -> value == other.value
            is Decimal -> BigDecimal.valueOf(value).compareTo(BigDecimal(other.value)) == 0
        }

        is Decimal -> when (other) {
            is Text -> false
            is Integer -> BigDecimal(value).compareTo(BigDecimal.valueOf(other.value)) == 0
            is Decimal -> value == other.value
        }
    }

    data class Text(val value: String) : DigitalIdTimestamp

    data class Integer(val value: Long) : DigitalIdTimestamp

    data class Decimal(val value: Double) : DigitalIdTimestamp {
        init {
            require(value.isFinite()) { "Digital ID timestamp must be finite" }
        }
    }
}

data class SecurityStatus(
    val tokenLevel: String,
    val deviceId: String,
    val timestamp: DigitalIdTimestamp,
    /** Null when the service omits it, e.g. for a captured-token profile that carries no PIN. */
    val attemptsLeft: Int?,
    val cooldown: Double? = null,
)

data class AuthorizationResult(val authorizationStatus: Boolean)

/** The session ID is the universal QR payload. The reference API does not supply its TTL. */
class QrSession(val sessionId: String) {
    override fun toString(): String = "QrSession(<redacted>)"
}
