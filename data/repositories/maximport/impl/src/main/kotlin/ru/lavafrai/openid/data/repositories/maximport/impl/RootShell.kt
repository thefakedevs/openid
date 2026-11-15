package ru.lavafrai.openid.data.repositories.maximport.impl

import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible

/**
 * Runs single commands as root through `su -c`, mirroring the exporter's command surface
 * (`find` listings, `head -c` reads, `id` for the root probe). Output is drained concurrently
 * to avoid pipe deadlocks; every command is bounded by a timeout that kills the process.
 *
 * [execute] blocks on [Process.waitFor], which only reacts to thread interruption, not to a
 * cancelled [kotlinx.coroutines.Job]. Every call site therefore goes through [runInterruptible]
 * so cancelling the surrounding coroutine (e.g. once a frida capture result already arrived)
 * interrupts the wait immediately instead of leaving the `su` process to block for its own
 * timeout — otherwise a caller that cancels early still stalls for up to that long.
 */
internal class RootShell(private val io: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.IO) {
    /** Optional diagnostics sink; the repository wires it to its trace file. */
    var diagnosticSink: ((String) -> Unit)? = null

    suspend fun available(): Boolean = runInterruptible(io) {
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
        onResult: ((code: Int, stderr: String, stdoutBytes: Int) -> Unit)? = null,
    ): String? = binary(command, timeoutMillis, ignoreExitCode, onResult)?.toString(Charsets.UTF_8)

    suspend fun binary(
        command: String,
        timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
        ignoreExitCode: Boolean = false,
        onResult: ((code: Int, stderr: String, stdoutBytes: Int) -> Unit)? = null,
    ): ByteArray? = runInterruptible(io) {
        runCatching { execute(command, timeoutMillis, ignoreExitCode, onResult) }.getOrNull()
    }

    /**
     * Reads [byteCount] bytes of a process memory region page-by-page through dd.
     * Short reads at unmapped holes keep the bytes collected so far and stop the region.
     * [deadlineNanoTime] bounds the whole read across its many `dd` invocations, so one huge
     * region can't stall the caller well past its own per-command timeout.
     */
    suspend fun readMemory(
        pid: Int,
        startAddress: Long,
        byteCount: Long,
        deadlineNanoTime: Long = Long.MAX_VALUE,
        onChunk: (ByteArray) -> Unit,
    ): Boolean {
        val pageSize = 4096L
        val firstPage = startAddress / pageSize
        val pages = byteCount / pageSize
        var offset = 0L
        while (offset < pages) {
            if (System.nanoTime() >= deadlineNanoTime) return false
            val batch = minOf(pages - offset, READ_PAGES_PER_COMMAND)
            val chunk = runInterruptible(io) {
                runCatching {
                    execute(
                        "dd if=/proc/$pid/mem bs=4096 skip=${firstPage + offset} count=$batch",
                        READ_TIMEOUT_MILLIS,
                        ignoreExitCode = true,
                        onResult = { code, stderr, bytes ->
                            if (code !=
                                0
                            ) {
                                diagnosticSink?.invoke("dd pid=$pid offset=$offset code=$code err=${stderr.take(120)}")
                            }
                        },
                    )
                }.getOrNull()
            } ?: return false
            if (chunk.isEmpty()) return false
            onChunk(chunk)
            offset += batch
        }
        return true
    }

    /**
     * KernelSU executes app-issued su commands inside the caller's mount namespace, where other
     * apps' `/data/user/0` directories are hidden by data isolation. `-M` forces the global
     * mount namespace (Magisk supports it too), making every path uniformly visible. The command
     * itself must stay free of shell features (quotes, redirections, `&&`): sucompat runs it
     * directly. A non-zero exit still yields partial output when [ignoreExitCode] is set.
     */
    private fun execute(
        command: String,
        timeoutMillis: Long,
        ignoreExitCode: Boolean,
        onResult: ((code: Int, stderr: String, stdoutBytes: Int) -> Unit)? = null,
    ): ByteArray? {
        val process = ProcessBuilder("su", "-M", "-c", command).start()
        val output = ByteArrayOutputStream()
        val errors = ByteArrayOutputStream()
        // destroyForcibly() (timeout, or an interrupted caller cutting the wait short) closes
        // these streams from another thread; the pending read then throws instead of returning
        // EOF. A bare Thread has no caller to catch that, so an uncaught IOException here would
        // crash the whole process — swallow it, the drained bytes collected so far are enough.
        val reader = thread(start = true, isDaemon = true) {
            runCatching { process.inputStream.copyTo(output) }
        }
        val errorDrainer = thread(start = true, isDaemon = true) {
            runCatching { process.errorStream.copyTo(errors) }
        }
        try {
            if (!process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly()
                process.waitFor(1, TimeUnit.SECONDS)
                onResult?.invoke(-1, "timeout after ${timeoutMillis}ms", 0)
                return null
            }
            reader.join(TimeUnit.SECONDS.toMillis(2))
            errorDrainer.join(TimeUnit.SECONDS.toMillis(1))
            val code = process.exitValue()
            val stderr = errors.toString(Charsets.UTF_8).take(200)
            onResult?.invoke(code, stderr, output.size())
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

        // 16 MiB per command: whole-region buffers would exceed the app heap limit.
        const val READ_PAGES_PER_COMMAND = 4_096L
    }
}
