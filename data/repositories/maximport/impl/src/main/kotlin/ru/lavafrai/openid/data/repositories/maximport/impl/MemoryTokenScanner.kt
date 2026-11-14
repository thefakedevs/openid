package ru.lavafrai.openid.data.repositories.maximport.impl

/**
 * Captures the last Digital ID authorization token from the MAX process memory. The token is a
 * `v.3.1:` string the mini-app sends to the host through the JS bridge; the bridge log ring keeps
 * it in the dalvik heap, and the server compares token strings verbatim, so a captured token
 * authorizes without the PIN salt.
 */
internal class MemoryTokenScanner(
    private val shell: RootShell,
    private val packageName: String,
) {
    private val prefix = TOKEN_PREFIX.toByteArray(Charsets.US_ASCII)

    sealed interface Capture {
        /** One distinct token value found across the scanned regions. */
        data class Token(val value: String) : Capture

        /** Several different values: the PIN changed recently and the ring holds both. */
        data object Ambiguous : Capture

        /** Nothing found: Digital ID was never unlocked since the MAX process started. */
        data object NotFound : Capture
    }

    suspend fun capture(): Capture {
        val pid = shell.text("pidof $packageName")?.trim()?.lineSequence()?.firstOrNull()?.trim()?.toIntOrNull()
            ?: return Capture.NotFound
        val maps = shell.text("cat /proc/$pid/maps") ?: return Capture.NotFound
        val found = linkedSetOf<String>()
        for ((start, size) in regions(maps)) {
            var carry = ByteArray(0)
            val readable = shell.readMemory(pid, start, size) { chunk ->
                val window = carry + chunk
                collectTokens(window, found)
                carry = window.copyOfRange(maxOf(0, window.size - CARRY_BYTES), window.size)
            }
            if (!readable) continue
        }
        return when (found.size) {
            1 -> Capture.Token(found.first())
            0 -> Capture.NotFound
            else -> Capture.Ambiguous
        }
    }

    /** Scans ascending; identical duplicates collapse, different values make the capture ambiguous. */
    private fun collectTokens(
        bytes: ByteArray,
        found: MutableCollection<String>,
    ) {
        val first = prefix[0]
        var index = 0
        while (index <= bytes.size - TOKEN_LENGTH) {
            if (bytes[index] != first) {
                index++
                continue
            }
            var offset = 1
            while (offset < prefix.size && bytes[index + offset] == prefix[offset]) offset++
            if (offset == prefix.size) {
                bytes.decodeAscii(index + prefix.size, TOKEN_HEX_LENGTH)?.let { found += it }
                index += prefix.size
            } else {
                index++
            }
        }
    }

    private fun regions(maps: String): List<Pair<Long, Long>> {
        val preferred = mutableListOf<Pair<Long, Long>>()
        val fallback = mutableListOf<Pair<Long, Long>>()
        for (line in maps.lineSequence()) {
            if (!line.contains("rw-p") && !line.contains("rw-s")) continue
            val range = line.substringBefore(' ')
            val dash = range.indexOf('-')
            if (dash <= 0) continue
            val start = range.substring(0, dash).toLongOrNull(16) ?: continue
            val end = range.substring(dash + 1).toLongOrNull(16) ?: continue
            val size = end - start
            if (size <= 0 || size > MAX_REGION_BYTES) continue
            val name = line.substringAfterLast(' ', "")
            val anonymous = name.isEmpty() || name.startsWith("[anon:") || name == "[heap]"
            if (!anonymous) continue
            if (name.startsWith("[anon:dalvik")) {
                preferred += Pair(start, size)
            } else {
                fallback += Pair(start, size)
            }
        }
        // The bridge log ring lives in the dalvik heap; scan it before anything else.
        return preferred + fallback
    }

    private fun ByteArray.decodeAscii(
        start: Int,
        length: Int,
    ): String? {
        val chars = CharArray(length)
        for (index in 0 until length) {
            val byte = this[start + index].toInt() and 0xFF
            val isHex = byte in 0x30..0x39 || byte in 0x61..0x66
            if (!isHex) return null
            chars[index] = byte.toChar()
        }
        return String(chars)
    }

    private companion object {
        const val TOKEN_PREFIX = "v.3.1:"
        const val TOKEN_HEX_LENGTH = 64
        const val TOKEN_LENGTH = TOKEN_PREFIX.length + TOKEN_HEX_LENGTH
        const val CARRY_BYTES = TOKEN_LENGTH
        const val MAX_REGION_BYTES = 8L * 1024 * 1024 * 1024
    }
}
