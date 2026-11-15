package ru.lavafrai.openid.data.repositories.maximport.impl

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/**
 * Best-effort reads of opaque files that contain the SecureStorage key names: extracts the JSON
 * envelope following a key occurrence.
 */
internal object RawFileScanner {
    private const val MAX_WINDOW = 4_096

    fun envelopes(
        raw: ByteArray,
        key: String,
        limit: Int = 8,
    ): List<String> {
        val keyBytes = key.toByteArray(Charsets.UTF_8)
        val found = mutableListOf<String>()
        var index = raw.indexOf(keyBytes, 0)
        while (index >= 0 && found.size < limit) {
            jsonEnvelope(raw, index + keyBytes.size)?.let(found::add)
            index = raw.indexOf(keyBytes, index + 1)
        }
        return found
    }

    private fun jsonEnvelope(
        raw: ByteArray,
        from: Int,
    ): String? {
        var start = -1
        val limit = minOf(raw.size, from + MAX_WINDOW)
        var index = from
        while (index < limit) {
            if (raw[index] == '{'.code.toByte()) {
                start = index
                break
            }
            index++
        }
        if (start < 0) return null
        val end = balancedEnd(raw, start, limit) ?: return null
        return try {
            Charsets.UTF_8
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(raw, start, end - start))
                .toString()
        } catch (_: Exception) {
            null
        }
    }

    private fun balancedEnd(
        raw: ByteArray,
        start: Int,
        limit: Int,
    ): Int? {
        var depth = 0
        var inString = false
        var escaped = false
        for (index in start until limit) {
            val byte = raw[index].toInt() and 0xFF
            if (inString) {
                when {
                    escaped -> escaped = false
                    byte == '\\'.code -> escaped = true
                    byte == '"'.code -> inString = false
                }
                continue
            }
            when (byte) {
                '"'.code -> inString = true

                '{'.code -> depth++

                '}'.code -> {
                    depth--
                    if (depth == 0) return index + 1
                }
            }
        }
        return null
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
