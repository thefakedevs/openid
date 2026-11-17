package ru.lavafrai.openid.data.core

/**
 * Debug-build diagnostic sink used to capture server rejection bodies during field testing.
 * Wired by the repository to its trace file; writes are best-effort and swallowed on failure.
 */
object DebugTrace {
    var sink: ((String) -> Unit)? = null

    fun line(message: String) {
        try {
            sink?.invoke(message)
        } catch (_: Exception) {
        }
    }
}
