package ru.lavafrai.openid.data.repositories.maximport.impl

import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * Scans Chromium WebView levelDB files for mini-app local storage entries. The stored key is
 * `_` + origin + 0x00 + 0x01 + key; the payload follows as a levelDB varint length prefixed by
 * an encoding byte: 0x00 for UTF-8 or 0x01 for UTF-16LE.
 */
internal object LocalStorageCodec {
    private const val MAX_VALUE_LENGTH = 65_536

    fun scan(
        raw: ByteArray,
        keys: Set<String>,
        origin: String = "https://digital-id.max.ru",
    ): Map<String, String> {
        val originPrefix = byteArrayOf(0x5F) + origin.toByteArray(Charsets.US_ASCII) + byteArrayOf(0x00, 0x01)
        val found = mutableMapOf<String, String>()
        for (key in keys) {
            val pattern = originPrefix + key.toByteArray(Charsets.US_ASCII)
            var index = raw.indexOf(pattern, 0)
            while (index >= 0 && !found.containsKey(key)) {
                val value = readValue(raw, index + pattern.size)
                if (value != null) found[key] = value
                index = raw.indexOf(pattern, index + 1)
            }
        }
        return found
    }

    private fun readValue(
        raw: ByteArray,
        start: Int,
    ): String? {
        var position = start
        var length = 0
        var shift = 0
        while (shift < 5) {
            if (position >= raw.size) return null
            val byte = raw[position].toInt() and 0xFF
            position++
            length = length or ((byte and 0x7F) shl (7 * shift))
            if (byte and 0x80 == 0) break
            shift++
        }
        if (shift >= 5 || length <= 0 || length > MAX_VALUE_LENGTH || position + length > raw.size) return null
        val payload = raw.copyOfRange(position, position + length)
        val encoding = payload[0].toInt() and 0xFF
        return when (encoding) {
            0x00 -> decode(payload.copyOfRange(1, payload.size), Charsets.UTF_8)
            0x01 -> if (payload.size % 2 == 1) decode(payload.copyOfRange(1, payload.size), Charsets.UTF_16LE) else null
            else -> null
        }?.takeIf { it.isNotEmpty() }
    }

    private fun decode(
        bytes: ByteArray,
        charset: Charset,
    ): String? = try {
        charset.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (_: Exception) {
        null
    }

    private fun ByteArray.indexOf(
        pattern: ByteArray,
        from: Int,
    ): Int {
        if (pattern.isEmpty() || from < 0) return -1
        var index = from
        while (index <= size - pattern.size) {
            var offset = 0
            while (offset < pattern.size && this[index + offset] == pattern[offset]) offset++
            if (offset == pattern.size) return index
            index++
        }
        return -1
    }
}
