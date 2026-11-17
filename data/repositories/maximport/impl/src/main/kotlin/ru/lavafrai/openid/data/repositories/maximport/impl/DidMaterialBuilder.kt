package ru.lavafrai.openid.data.repositories.maximport.impl

import ru.lavafrai.openid.data.core.StrictJson
import ru.lavafrai.openid.models.digitalid.DigitalIdTimestamp

/**
 * Accumulates Digital ID candidates across scan sources. A field resolves only when every
 * source agreed on one value; disagreement keeps the field unresolved and the import fails
 * rather than guessing.
 */
internal class DidMaterialBuilder {
    private val pinSalts = linkedSetOf<String>()
    private val timestamps = linkedSetOf<DigitalIdTimestamp>()
    private val biometryIds = linkedSetOf<String>()

    val pinSalt: String? get() = pinSalts.singleOrNull()
    val creationTimestamp: DigitalIdTimestamp? get() = timestamps.singleOrNull()
    val biometricDeviceId: String? get() = biometryIds.singleOrNull()

    val isComplete: Boolean get() = pinSalt != null && creationTimestamp != null && biometricDeviceId != null

    /** Values may arrive as raw SecureStorage envelopes; unwrapping follows the exporter. */
    fun addPinSaltEnvelope(
        text: String?,
        nowMillis: Long,
    ) {
        val data = text?.let { SecureStorageEnvelope.unwrap(it, nowMillis) } as? String ?: return
        addPinSalt(data)
    }

    fun addTimestampEnvelope(
        text: String?,
        nowMillis: Long,
    ) {
        val data = text?.let { SecureStorageEnvelope.unwrap(it, nowMillis) } ?: return
        addTimestamp(data)
    }

    fun addPinSalt(value: String?) {
        pinSalt(value)?.let(pinSalts::add)
    }

    fun addTimestamp(value: Any?) {
        timestamp(value)?.let(timestamps::add)
    }

    fun addBiometryId(value: String?) {
        biometryId(value)?.let(biometryIds::add)
    }

    private fun pinSalt(value: String?): String? =
        value?.takeIf { it.length in 1..1024 && it.none { character -> character.code < 32 } }

    private fun biometryId(value: String?): String? =
        value?.takeIf { it.length in 1..256 && it.none { character -> character.code < 32 } }

    private fun timestamp(value: Any?): DigitalIdTimestamp? = when (value) {
        is String -> value.takeIf { it.length in 1..128 }?.let { DigitalIdTimestamp.Text(it) }

        is StrictJson.Number ->
            if (value.integral) {
                value.long()?.takeIf { it > 0 }?.let { DigitalIdTimestamp.Integer(it) }
            } else {
                value.double().takeIf { it > 0 }?.let { DigitalIdTimestamp.Decimal(it) }
            }

        is Long -> value.takeIf { it > 0 }?.let { DigitalIdTimestamp.Integer(it) }

        else -> null
    }
}
