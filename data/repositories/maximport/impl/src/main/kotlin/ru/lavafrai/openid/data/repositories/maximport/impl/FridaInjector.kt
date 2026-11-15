package ru.lavafrai.openid.data.repositories.maximport.impl

import android.content.Context
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
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
    private val resultPath = "/data/user/0/ru.oneme.app/cache/openid_did_material.json"

    suspend fun install(): Boolean = withContext(Dispatchers.IO) {
        try {
            val binary = File(context.filesDir, BINARY_NAME)
            if (!binary.exists() || binary.length() == 0L) {
                val packed = File(context.filesDir, "$BINARY_NAME.xz")
                context.assets.open("$BINARY_NAME.xz").use { input ->
                    FileOutputStream(packed).use { output -> input.copyTo(output) }
                }
                XZInputStream(FileInputStream(packed)).use { input ->
                    FileOutputStream(binary).use { output -> input.copyTo(output) }
                }
                packed.delete()
            }
            binary.setExecutable(true, false)
            val agent = File(context.filesDir, AGENT_NAME)
            if (!agent.exists()) {
                context.assets.open(AGENT_NAME).use { input ->
                    FileOutputStream(agent).use { output -> input.copyTo(output) }
                }
            }
            val ok = binary.exists() && binary.canExecute() && agent.exists()
            trace.line("frida install ok=$ok binary=${binary.length()} agent=${agent.length()}")
            ok
        } catch (failure: Exception) {
            trace.line("frida install failed: ${failure.javaClass.simpleName}")
            false
        }
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
        onAgentReady: suspend () -> Unit,
        waitMillis: Long = 60_000L,
    ): Capture {
        val pid = shell.text(
            "pidof ${RootImportPackages.MAX}",
        )?.trim()?.lineSequence()?.firstOrNull()?.trim()?.toIntOrNull()
        trace.line("frida capture pid=$pid")
        if (pid == null) return Capture.Failed("DID_TOKEN_NOT_RUNNING")
        if (!install()) return Capture.Failed("FRIDA_INSTALL_FAILED")
        shell.text("rm $resultPath", ignoreExitCode = true)
        val binary = File(context.filesDir, BINARY_NAME).absolutePath
        val agent = File(context.filesDir, AGENT_NAME).absolutePath
        val injector: Job = scope.launch(Dispatchers.IO) {
            val outcome = shell.text(
                "$binary -p $pid -s $agent",
                timeoutMillis = waitMillis + AGENT_ARM_MILLIS + 10_000L,
                ignoreExitCode = true,
            )
            trace.line("frida injector finished len=${outcome?.length ?: -1}")
            trace.line("frida injector output: ${outcome?.take(280) ?: "null"}")
        }
        // Give the injector time to attach and arm the onResume hook before MAX is foregrounded.
        delay(AGENT_ARM_MILLIS)
        trace.line("frida agent armed, bringing MAX forward")
        onAgentReady()
        val deadline = System.currentTimeMillis() + waitMillis
        var injectorDone = false
        while (System.currentTimeMillis() < deadline) {
            delay(800)
            val raw = shell.text("cat $resultPath", ignoreExitCode = true)
            if (!raw.isNullOrEmpty()) {
                val parsed = parse(raw)
                if (parsed != null) {
                    injector.cancel()
                    shell.text("rm $resultPath", ignoreExitCode = true)
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

        // Time allowed for frida-inject to attach and the agent to arm its onResume hook before
        // MAX is brought to the foreground. The binary is decompressed ahead of time, so attaching
        // is quick; this only covers process attach plus script load.
        const val AGENT_ARM_MILLIS = 6_000L
    }
}
