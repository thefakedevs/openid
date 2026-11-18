package ru.lavafrai.openid.data.repositories.maximport.impl

import android.content.Context
import java.io.File
import ru.lavafrai.openid.data.repositories.maximport.api.RootImportPackages

/**
 * Append-only diagnostic trace of the extraction, kept in the app cache directory. Only ever
 * backed by a real file in debug builds (see [TraceLog.NOOP]); there it is preserved across runs
 * so a failed import can still be inspected afterwards. [startRun] marks a fresh run with a
 * separator instead of wiping the file, and the log is capped so it cannot grow without bound.
 */
internal open class TraceLog(private val file: File) {
    constructor(context: Context) : this(File(context.cacheDir, RootImportPackages.TRACE_LOG_FILE))

    private val lock = Any()

    /** Marks the beginning of a new extraction without discarding earlier runs. */
    open fun startRun() = synchronized(lock) {
        try {
            if (file.exists() && file.length() > MAX_BYTES) file.delete()
            file.appendText("\n==== run ${System.currentTimeMillis()} ====\n")
        } catch (_: Exception) {
        }
    }

    open fun clear(): Unit = synchronized(lock) { file.delete() }

    open fun line(message: String) = synchronized(lock) {
        try {
            if (file.length() > MAX_BYTES) file.delete()
            file.appendText("${System.currentTimeMillis()} $message\n")
        } catch (_: Exception) {
        }
    }

    companion object {
        private const val MAX_BYTES = 512_000L

        val NOOP = object : TraceLog(File("/dev/null")) {
            override fun startRun() {}
            override fun clear() {}
            override fun line(message: String) {}
        }
    }
}
