package ru.lavafrai.openid.data.repositories.maximport.impl

import android.content.Context
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.security.MessageDigest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.tukaani.xz.XZInputStream
import ru.lavafrai.openid.data.core.DataException
import ru.lavafrai.openid.data.core.StrictJson
import ru.lavafrai.openid.data.repositories.maximport.api.RootImportPackages

/**
 * On-device Digital ID capture through the embedded frida-inject binary, mirroring the reference
 * exporter: the agent runs inside the MAX process, waits for MAX to come to the foreground
 * (heap enumeration in a backgrounded process crashes some ART builds), finds the visible
 * Digital ID WebView, snapshots the official bridge and writes the material into MAX's cache
 * directory — the only place the untrusted_app context may write. The result is read back
 * through su.
 */
internal class FridaInjector(
    private val context: Context,
    private val shell: RootShell,
    private val trace: TraceLog,
) {
    private fun resultPath(user: Int) = "/data/user/$user/${RootImportPackages.MAX}/cache/openid_did_material.json"

    suspend fun install(): Boolean = withContext(Dispatchers.IO) {
        try {
            val binary = File(context.filesDir, BINARY_NAME)
            val versionFile = File(context.filesDir, "$BINARY_NAME.ver")
            val packed = File(context.filesDir, "$BINARY_NAME.xz")
            val xzHash = context.assets.open("$BINARY_NAME.xz").use { sha256(it) }
            val cachedVer = versionFile.takeIf { it.exists() }?.readText()?.trim()
            val cachedXzHash = cachedVer?.substringBefore(':')
            val cachedBinaryHash = cachedVer?.substringAfter(':', "")?.takeIf { it.isNotEmpty() }
            val needsInstall = !binary.exists() || binary.length() == 0L ||
                cachedXzHash != xzHash || cachedBinaryHash == null ||
                FileInputStream(binary).use { sha256(it) } != cachedBinaryHash
            if (needsInstall) {
                context.assets.open("$BINARY_NAME.xz").use { input ->
                    FileOutputStream(packed).use { output -> input.copyTo(output) }
                }
                val tmp = File(context.filesDir, "$BINARY_NAME.tmp")
                XZInputStream(FileInputStream(packed)).use { input ->
                    FileOutputStream(tmp).use { output -> input.copyTo(output) }
                }
                packed.delete()
                if (tmp.length() == 0L) {
                    tmp.delete()
                    return@withContext false
                }
                if (!tmp.renameTo(binary)) {
                    tmp.delete()
                    return@withContext false
                }
                if (!binary.exists() || binary.length() == 0L) return@withContext false
                val binaryHash = FileInputStream(binary).use { sha256(it) }
                versionFile.writeText("$xzHash:$binaryHash")
            }
            binary.setExecutable(true, false)

            val agent = File(context.filesDir, AGENT_NAME)
            val agentAssetHash = context.assets.open(AGENT_NAME).use { sha256(it) }
            val installedHash = if (agent.exists() && agent.length() > 0) {
                FileInputStream(agent).use { sha256(it) }
            } else {
                ""
            }
            if (installedHash != agentAssetHash) {
                val tmp = File(context.filesDir, "$AGENT_NAME.tmp")
                context.assets.open(AGENT_NAME).use { input ->
                    FileOutputStream(tmp).use { output -> input.copyTo(output) }
                }
                if (tmp.length() == 0L) {
                    tmp.delete()
                    return@withContext false
                }
                tmp.renameTo(agent)
            }

            val ok = binary.exists() && binary.canExecute() && agent.exists() && agent.length() > 0
            trace.line("frida install ok=$ok binary=${binary.length()} agent=${agent.length()}")
            ok
        } catch (failure: Exception) {
            trace.line("frida install failed: ${failure.javaClass.simpleName}")
            false
        }
    }

    private fun sha256(input: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(8192)
        var r: Int
        while (input.read(buf).also { r = it } > 0) digest.update(buf, 0, r)
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    sealed interface Capture {
        data class Material(val payload: Map<String, Any?>) : Capture

        data class Failed(val code: String) : Capture
    }

    /**
     * Starts frida-inject against the MAX process and waits for the agent result file.
     *
     * The agent installs an `onResume` hook and only enumerates the heap once MAX comes to the
     * foreground (doing it while backgrounded crashes some ART builds). That hook only fires on a
     * *fresh* resume, so the caller must attach the agent while MAX is still in the background and
     * only then bring it forward: [onAgentReady] is invoked once the injector has had time to load
     * the agent and arm the hook, and the caller uses it to switch to MAX. Bringing MAX forward
     * before the hook is armed (the previous ordering) left the agent waiting until timeout.
     */
    suspend fun capture(
        scope: CoroutineScope,
        user: Int,
        onAgentReady: suspend () -> Unit,
        waitMillis: Long = 60_000L,
    ): Capture {
        val pid = resolveMaxPid(user)
        trace.line("frida capture pid=$pid user=$user")
        if (pid == null) return Capture.Failed("DID_TOKEN_NOT_RUNNING")
        if (!install()) return Capture.Failed("FRIDA_INSTALL_FAILED")
        val result = resultPath(user)
        shell.text("rm $result", ignoreExitCode = true)
        val binary = File(context.filesDir, BINARY_NAME).absolutePath
        val agentPath = patchedAgent(user)
        val injector: Job = scope.launch(Dispatchers.IO) {
            val outcome = shell.text(
                "$binary -p $pid -s $agentPath",
                timeoutMillis = waitMillis + AGENT_ARM_MILLIS + 10_000L,
                ignoreExitCode = true,
            )
            trace.line("frida injector finished len=${outcome?.length ?: -1}")
            trace.line("frida injector output: ${outcome?.take(280) ?: "null"}")
        }
        delay(AGENT_ARM_MILLIS)
        trace.line("frida agent armed, bringing MAX forward")
        onAgentReady()
        val deadline = System.currentTimeMillis() + waitMillis
        var injectorDone = false
        while (System.currentTimeMillis() < deadline) {
            delay(800)
            val raw = shell.text("cat $result", ignoreExitCode = true)
            if (!raw.isNullOrEmpty()) {
                val parsed = parse(raw)
                if (parsed != null) {
                    injector.cancel()
                    shell.text("rm $result", ignoreExitCode = true)
                    trace.line("frida capture result=${parsed.javaClass.simpleName}")
                    return parsed
                }
            }
            if (injector.isCompleted) {
                if (injectorDone) return Capture.Failed("FRIDA_INJECTOR_EXITED")
                injectorDone = true
            }
        }
        injector.cancel()
        trace.line("frida capture timeout")
        return Capture.Failed("OPEN_DIGITAL_ID_AND_UNLOCK_FIRST")
    }

    private suspend fun resolveMaxPid(user: Int): Int? {
        val raw = shell.text("pidof ${RootImportPackages.MAX}")?.trim() ?: return null
        val pids = raw.split(Regex("\\s+")).mapNotNull { it.toIntOrNull() }
        if (pids.isEmpty()) return null
        val uidMin = user * 100_000
        val uidMax = uidMin + 99_999
        for (pid in pids) {
            val uidLine = shell.text("stat -c %u /proc/$pid", ignoreExitCode = true)?.trim()
            val uid = uidLine?.toIntOrNull() ?: continue
            if (uid in uidMin..uidMax) return pid
        }
        return null
    }

    private fun patchedAgent(user: Int): String {
        val source = File(context.filesDir, AGENT_NAME)
        if (user == 0) return source.absolutePath
        val patched = File(context.filesDir, "did-agent-patched.js")
        val original = source.readText(Charsets.UTF_8)
        patched.writeText(
            original.replace(HARDCODED_RESULT_PATH, resultPath(user)),
            Charsets.UTF_8,
        )
        return patched.absolutePath
    }

    private fun parse(raw: String): Capture? = try {
        val payload = StrictJson.obj(StrictJson.parse(raw.toByteArray(Charsets.UTF_8)))
        when (payload["kind"]) {
            "material" -> Capture.Material(StrictJson.obj(payload["material"]))
            "error" -> Capture.Failed(payload["code"] as? String ?: "AGENT_ERROR_UNKNOWN")
            else -> null
        }
    } catch (_: Exception) {
        null
    }

    private companion object {
        const val BINARY_NAME = "frida-inject"
        const val AGENT_NAME = "did-agent.js"
        const val HARDCODED_RESULT_PATH = "/data/user/0/ru.oneme.app/cache/openid_did_material.json"

        // Time allowed for frida-inject to attach and the agent to arm its onResume hook before
        // MAX is brought to the foreground. The binary is decompressed ahead of time, so attaching
        // is quick; this only covers process attach plus script load.
        const val AGENT_ARM_MILLIS = 6_000L
    }
}
