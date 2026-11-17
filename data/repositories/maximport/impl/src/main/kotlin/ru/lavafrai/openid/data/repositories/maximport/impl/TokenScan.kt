package ru.lavafrai.openid.data.repositories.maximport.impl

/**
 * Byte scanner for Digital ID authorization tokens (`v.3.1:` + 64 hex characters). Used against
 * MAX's own log files and, as a fallback, against the MAX process heap; the server compares
 * token strings verbatim, so a captured token authorizes without the PIN salt.
 */
internal object TokenScan {
    private val prefix = "v.3.1:".toByteArray(Charsets.US_ASCII)
    private const val TOKEN_LENGTH = 6 + 64
    private const val CARRY_BYTES = TOKEN_LENGTH

    /** Scans ascending; identical duplicates collapse, different values make the capture ambiguous. */
    fun collect(
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
                decodeHexToken(bytes, index + prefix.size)?.let(found::add)
                index += prefix.size
            } else {
                index++
            }
        }
    }

    /** Carry tail between chunked reads so a token split across chunks is still found. */
    fun carryOver(window: ByteArray): ByteArray = window.copyOfRange(maxOf(0, window.size - CARRY_BYTES), window.size)

    private val PREFIX_STRING = "v.3.1:"

    private fun decodeHexToken(
        bytes: ByteArray,
        start: Int,
    ): String? {
        val chars = CharArray(64)
        for (index in 0 until 64) {
            val byte = bytes[start + index].toInt() and 0xFF
            val isHex = byte in 0x30..0x39 || byte in 0x61..0x66
            if (!isHex) return null
            chars[index] = byte.toChar()
        }
        return PREFIX_STRING + String(chars)
    }
}
