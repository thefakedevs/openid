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
    private val trace: TraceLog,
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
        trace.line("scan pid=$pid")
        if (pid == null) return Capture.NotFound
        val maps = shell.text("cat /proc/$pid/maps")
        trace.line("maps chars=${maps?.length}")
        if (maps == null) return Capture.NotFound
        val regions = regions(maps)
        trace.line("regions preferred=${regions.size} fallbackBytes=${regions.sumOf { it.second }}")
        val found = linkedSetOf<String>()
        var scanned = 0L
        var failures = 0
        for ((index, region) in regions.withIndex()) {
            val (start, size) = region
            var carry = ByteArray(0)
            val readable = shell.readMemory(pid, start, size) { chunk ->
                val window = carry + chunk
                TokenScan.collect(window, found)
                carry = TokenScan.carryOver(window)
                scanned += chunk.size
            }
            if (!readable) {
                failures++
                trace.line("region $index unreadable start=$start size=$size")
            }
            if (found.size > 1) break
        }
        trace.line("scan done scanned=$scanned distinct=${found.size} failures=$failures")
        return when (found.size) {
            1 -> Capture.Token(found.first())
            0 -> Capture.NotFound
            else -> Capture.Ambiguous
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

    private companion object {
        const val TOKEN_PREFIX = "v.3.1:"
        const val TOKEN_HEX_LENGTH = 64
        const val TOKEN_LENGTH = TOKEN_PREFIX.length + TOKEN_HEX_LENGTH
        const val CARRY_BYTES = TOKEN_LENGTH
        const val MAX_REGION_BYTES = 8L * 1024 * 1024 * 1024
    }
}
