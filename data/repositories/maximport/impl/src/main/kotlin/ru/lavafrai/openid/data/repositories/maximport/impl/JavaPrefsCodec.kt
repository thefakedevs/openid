package ru.lavafrai.openid.data.repositories.maximport.impl

/**
 * Reads records out of Java Preferences binary files, the format MAX uses under
 * `app_file_prefs`: a MUTF-8 length-prefixed key followed by one typed value.
 * Exact keys are scanned the same way the reference exporter does; string keys only accept
 * text records, numeric keys only accept int/long records. A separate walk-back scan finds
 * records whose key merely contains a fragment, for best-effort Digital ID hints.
 */
internal object JavaPrefsCodec {
    private const val TAG_INT = 0
    private const val TAG_LONG = 2
    private const val TAG_STRING = 3

    private const val MAX_KEY_LENGTH = 512
    private const val MAX_VALUE_LENGTH = 32_768
    private const val MAX_FRAGMENT_KEY_LENGTH = 128

    /** Scans for the exact keys; a string key never matches a numeric record and vice versa. */
    fun scan(
        raw: ByteArray,
        stringKeys: Set<String>,
        numericKeys: Set<String>,
        limit: Int = 4_096,
    ): List<PrefRecord> {
        val records = mutableListOf<PrefRecord>()
        for (key in stringKeys) {
            scanKey(raw, key) { tag ->
                if (tag >= raw.size) return@scanKey null
                if ((raw[tag].toInt() and 0xFF) == TAG_STRING) stringAt(raw, tag + 1) else null
            }?.let { value ->
                records += PrefRecord(key, value)
            }
        }
        for (key in numericKeys) {
            scanKey(raw, key) { tag ->
                if (tag >= raw.size) return@scanKey null
                when (raw[tag].toInt() and 0xFF) {
                    TAG_INT -> intAt(raw, tag + 1)?.toLong()
                    TAG_LONG -> longAt(raw, tag + 1)
                    else -> null
                }
            }?.let { value ->
                records += PrefRecord(key, value)
            }
        }
        return records.take(limit)
    }

    /** The key can sit anywhere in the file; every occurrence must resolve to one value. */
    private fun scanKey(
        raw: ByteArray,
        key: String,
        readValue: (Int) -> Any?,
    ): Any? {
        val prefix = byteArrayOf((key.length shr 8).toByte(), key.length.toByte()) +
            key.toByteArray(Charsets.US_ASCII)
        var value: Any? = null
        var position = 0
        while (position <= raw.size - prefix.size) {
            if (!matchesAt(raw, prefix, position)) {
                position++
                continue
            }
            val valueStart = position + prefix.size
            readValue(valueStart)?.let { candidate ->
                if (value == null) {
                    value = candidate
                } else if (value != candidate) {
                    value = AMBIGUOUS
                }
            }
            position++
        }
        return value.takeIf { it !== AMBIGUOUS }
    }

    private val AMBIGUOUS = Any()

    /** Finds string records whose key contains one of the fragments, used for biometry hints. */
    fun scanFragments(
        raw: ByteArray,
        fragments: Set<String>,
        limit: Int = 128,
    ): List<PrefRecord> {
        val lowered = ByteArray(raw.size) { index ->
            raw[index].toInt().toChar().lowercaseChar().code.toByte()
        }
        val records = mutableListOf<PrefRecord>()
        for (fragment in fragments) {
            val pattern = fragment.lowercase().toByteArray(Charsets.US_ASCII)
            var position = 0
            while (records.size < limit) {
                position = lowered.indexOf(pattern, position)
                if (position < 0) break
                recordEndingAt(lowered, raw, fragment, position, pattern.size)?.let { candidate ->
                    if (records.none { it.key == candidate.key && it.value == candidate.value }) {
                        records += candidate
                    }
                }
                position++
            }
        }
        return records
    }

    /** Walks back from the fragment occurrence to a valid length-prefixed key start. */
    private fun recordEndingAt(
        lowered: ByteArray,
        raw: ByteArray,
        fragment: String,
        fragmentStart: Int,
        fragmentLength: Int,
    ): PrefRecord? {
        val firstStart = maxOf(0, fragmentStart - MAX_FRAGMENT_KEY_LENGTH)
        for (start in fragmentStart downTo firstStart) {
            if (start < 2) break
            val keyLength = ((lowered[start - 2].toInt() and 0xFF) shl 8) or (lowered[start - 1].toInt() and 0xFF)
            if (keyLength !in fragmentLength..MAX_KEY_LENGTH) continue
            val keyEnd = start + keyLength
            if (keyEnd >= raw.size) continue
            if (keyEnd < fragmentStart + fragmentLength) continue
            if (!isPrintableAscii(raw, start, keyLength)) continue
            val key = Cesu8.decode(raw, start, keyLength) ?: continue
            if (key.isEmpty() || key.any { it == '\u0000' }) continue
            if (!key.contains(fragment, ignoreCase = true)) continue
            if ((raw[keyEnd].toInt() and 0xFF) != TAG_STRING) continue
            val value = stringAt(raw, keyEnd + 1) ?: continue
            return PrefRecord(key, value)
        }
        return null
    }

    private fun stringAt(
        raw: ByteArray,
        start: Int,
    ): String? {
        if (start + 2 > raw.size) return null
        val length = ((raw[start].toInt() and 0xFF) shl 8) or (raw[start + 1].toInt() and 0xFF)
        if (length == 0 || length > MAX_VALUE_LENGTH) return null
        if (start + 2 + length > raw.size) return null
        val value = Cesu8.decode(raw, start + 2, length) ?: return null
        if (value.isEmpty() || value.any { it == '\u0000' }) return null
        return value
    }

    private fun intAt(
        raw: ByteArray,
        start: Int,
    ): Int? {
        if (start + 4 > raw.size) return null
        var value = 0
        for (index in 0 until 4) {
            value = (value shl 8) or (raw[start + index].toInt() and 0xFF)
        }
        return value
    }

    private fun longAt(
        raw: ByteArray,
        start: Int,
    ): Long? {
        if (start + 8 > raw.size) return null
        var value = 0L
        for (index in 0 until 8) {
            value = (value shl 8) or (raw[start + index].toLong() and 0xFF)
        }
        return value
    }

    private fun matchesAt(
        raw: ByteArray,
        prefix: ByteArray,
        position: Int,
    ): Boolean {
        for (index in prefix.indices) {
            if (raw[position + index] != prefix[index]) return false
        }
        return true
    }

    private fun isPrintableAscii(
        raw: ByteArray,
        start: Int,
        length: Int,
    ): Boolean {
        if (start + length > raw.size) return false
        for (index in start until start + length) {
            val byte = raw[index].toInt() and 0xFF
            if (byte !in 0x20..0x7E) return false
        }
        return true
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

/** Java modified UTF-8 (CESU-8) with the two-byte NUL escape used by DataOutputStream values. */
internal object Cesu8 {
    fun decode(
        raw: ByteArray,
        start: Int,
        length: Int,
    ): String? {
        val end = start + length
        if (end > raw.size) return null
        val result = StringBuilder(length)
        var index = start
        while (index < end) {
            val first = raw[index].toInt() and 0xFF
            when {
                first < 0x80 -> {
                    result.append(first.toChar())
                    index++
                }

                first == 0xC0 -> {
                    if (index + 1 >= end || (raw[index + 1].toInt() and 0xFF) != 0x80) return null
                    result.append('\u0000')
                    index += 2
                }

                first and 0xE0 == 0xE0 -> {
                    if (index + 2 >= end) return null
                    val second = raw[index + 1].toInt() and 0xFF
                    val third = raw[index + 2].toInt() and 0xFF
                    if (second and 0xC0 != 0x80 || third and 0xC0 != 0x80) return null
                    val codePoint = ((first and 0x0F) shl 12) or ((second and 0x3F) shl 6) or (third and 0x3F)
                    result.append(codePoint.toChar())
                    index += 3
                }

                else -> return null
            }
        }
        return result.toString()
    }
}
