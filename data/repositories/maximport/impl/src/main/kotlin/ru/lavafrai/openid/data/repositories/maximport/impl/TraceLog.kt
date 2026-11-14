package ru.lavafrai.openid.data.repositories.maximport.impl

import android.content.Context
import java.io.File

/** Append-only diagnostic trace of the last extraction; lives in the app cache directory. */
internal class TraceLog(private val file: File) {
    constructor(context: Context) : this(File(context.cacheDir, "maximport_trace.log"))

    private val lock = Any()

    fun clear() = synchronized(lock) { file.delete() }

    fun line(message: String) = synchronized(lock) {
        try {
            file.appendText("${System.currentTimeMillis()} $message\n")
        } catch (_: Exception) {
        }
    }
}
