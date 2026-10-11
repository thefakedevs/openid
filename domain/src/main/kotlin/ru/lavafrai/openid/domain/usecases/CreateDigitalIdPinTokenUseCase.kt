package ru.lavafrai.openid.domain.usecases

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import ru.lavafrai.openid.models.digitalid.DigitalIdToken

class CreateDigitalIdPinTokenUseCase {
    /** Does not retain or modify the caller-owned PIN buffer; the caller must clear it after use. */
    operator fun invoke(
        pinSalt: String,
        pin: CharArray,
    ): DigitalIdToken {
        require(pinSalt.isNotEmpty()) { "Digital ID PIN salt is missing" }
        require(pin.size == 4 && pin.all { it in '0'..'9' }) { "Digital ID PIN must contain four ASCII digits" }
        val saltBytes = pinSalt.toByteArray(Charsets.UTF_8)
        val pinBytes = ByteArray(pin.size) { pin[it].code.toByte() }
        try {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(saltBytes, "HmacSHA256"))
            val digest = mac.doFinal(pinBytes)
            try {
                val hex = "0123456789abcdef"
                val value = buildString(6 + digest.size * 2) {
                    append("v.3.1:")
                    for (byte in digest) {
                        val unsigned = byte.toInt() and 0xff
                        append(hex[unsigned ushr 4])
                        append(hex[unsigned and 0xf])
                    }
                }
                return DigitalIdToken(value)
            } finally {
                digest.fill(0)
            }
        } finally {
            saltBytes.fill(0)
            pinBytes.fill(0)
        }
    }
}
