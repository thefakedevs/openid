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
            execute("id", PROBE_TIMEOUT_MILLIS, ignoreExitCode = false)
                ?.toString(Charsets.UTF_8)
                ?.contains("uid=0(") == true
        }.getOrDefault(false)
    }

    suspend fun text(
        command: String,
        timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
        ignoreExitCode: Boolean = false,
    ): String? = withContext(io) {
        binary(command, timeoutMillis, ignoreExitCode)?.toString(Charsets.UTF_8)
    }

    suspend fun binary(
        command: String,
        timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
        ignoreExitCode: Boolean = false,
    ): ByteArray? = withContext(io) {
        runCatching { execute(command, timeoutMillis, ignoreExitCode) }.getOrNull()
    }

    /**
     * Reads [byteCount] bytes of a process memory region page-by-page through dd.
     * Short reads at unmapped holes keep the bytes collected so far and stop the region.
     */
    suspend fun readMemory(
        pid: Int,
        startAddress: Long,
        byteCount: Long,
        onChunk: (ByteArray) -> Unit,
    ): Boolean = withContext(io) {
        val pageSize = 4096L
        val firstPage = startAddress / pageSize
        val pages = byteCount / pageSize
        var offset = 0L
        while (offset < pages) {
            val batch = minOf(pages - offset, READ_PAGES_PER_COMMAND)
            val chunk = runCatching {
                execute(
                    "dd if=/proc/$pid/mem bs=4096 skip=$((firstPage + offset)) count=$batch",
                    READ_TIMEOUT_MILLIS,
                    ignoreExitCode = false,
                )
            }.getOrNull() ?: return@withContext false
            if (chunk.isEmpty()) return@withContext false
            onChunk(chunk)
            offset += batch
        }
        true
    }

    /**
     * KernelSU's kernel sucompat executes app-issued commands directly, without a shell, so
     * commands must never rely on quotes, redirections or `&&`. A non-zero exit still yields
     * partial output when [ignoreExitCode] is set; find and grep use that for partial results.
     */
    private fun execute(
        command: String,
        timeoutMillis: Long,
        ignoreExitCode: Boolean,
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
            val code = process.exitValue()
            if (code != 0 && !ignoreExitCode) return null
            if (code != 0 && output.size() == 0) return null
            return output.toByteArray()
        } finally {
            process.destroyForcibly()
        }
    }

    private companion object {
        const val PROBE_TIMEOUT_MILLIS = 8_000L
        const val DEFAULT_TIMEOUT_MILLIS = 15_000L
        const val READ_TIMEOUT_MILLIS = 30_000L
        const val READ_PAGES_PER_COMMAND = 65_536L // 256 MiB per dd command
    }
}
