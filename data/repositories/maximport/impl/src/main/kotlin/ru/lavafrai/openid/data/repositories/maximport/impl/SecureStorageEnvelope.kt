package ru.lavafrai.openid.data.repositories.maximport.impl

import ru.lavafrai.openid.data.core.StrictJson

/**
 * MAX wraps mini-app SecureStorage values into `{"data":…,"ttl":…,"timestamp":…}` envelopes,
 * exactly what the reference exporter unwraps in the page snapshot; expired values read as null.
 */
internal object SecureStorageEnvelope {
    fun unwrap(
        text: String,
        nowMillis: Long,
    ): Any? = runCatching {
        val value = StrictJson.parse(text.toByteArray(Charsets.UTF_8))
        val envelope = value as? Map<*, *> ?: return@runCatching null
        val data = envelope["data"] ?: return@runCatching null
        val ttl = (envelope["ttl"] as? StrictJson.Number)?.long()
        val timestamp = (envelope["timestamp"] as? StrictJson.Number)?.long()
        if (ttl != null && ttl > 0 && timestamp != null) {
            val elapsed = nowMillis - normalizeEpoch(timestamp)
            if (elapsed > ttl) return@runCatching null
        }
        data
    }.getOrNull()

    private fun normalizeEpoch(value: Long): Long = if (value in 1..999_999_999_999L) value * 1000 else value
}
