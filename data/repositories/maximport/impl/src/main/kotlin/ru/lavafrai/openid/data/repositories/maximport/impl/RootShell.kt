package ru.lavafrai.openid.data.repositories.maximport.impl

import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Runs single commands as root through `su -c`, mirroring the exporter's command surface
 * (`find` listings, `head -c` reads, `id` for the root probe). Output is drained concurrently
 * to avoid pipe deadlocks; every command is bounded by a timeout that kills the process.
 */
internal class RootShell(private val io: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.IO) {
    suspend fun available(): Boolean = withContext(io) {
        runCatching {
            execute("id", PROBE_TIMEOUT_MILLIS)?.toString(Charsets.UTF_8)?.contains("uid=0(") == true
        }.getOrDefault(false)
    }

    suspend fun text(
        command: String,
        timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
    ): String? = withContext(io) {
        binary(command, timeoutMillis)?.toString(Charsets.UTF_8)
    }

    suspend fun binary(
        command: String,
        timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
    ): ByteArray? = withContext(io) {
        runCatching { execute(command, timeoutMillis) }.getOrNull()
    }

    /** Null on timeout, denied access, a non-zero exit code or any launch failure. */
    private fun execute(
        command: String,
        timeoutMillis: Long,
    ): ByteArray? {
        val process = ProcessBuilder("su", "-c", command).start()
        val output = ByteArrayOutputStream()
        val reader = thread(start = true, isDaemon = true) { process.inputStream.copyTo(output) }
        val errorDrainer = thread(start = true, isDaemon = true) { process.errorStream.copyTo(ByteArrayOutputStream()) }
        try {
            if (!process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly()
                process.waitFor(1, TimeUnit.SECONDS)
                return null
            }
            reader.join(TimeUnit.SECONDS.toMillis(2))
            errorDrainer.join(TimeUnit.SECONDS.toMillis(1))
            if (process.exitValue() != 0) return null
            return output.toByteArray()
        } finally {
            process.destroyForcibly()
        }
    }

    private companion object {
        const val PROBE_TIMEOUT_MILLIS = 8_000L
        const val DEFAULT_TIMEOUT_MILLIS = 15_000L
    }
}
