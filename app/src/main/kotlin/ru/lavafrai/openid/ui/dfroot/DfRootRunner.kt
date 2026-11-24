package ru.lavafrai.openid.ui.dfroot

import android.content.Context
import android.os.Build
import android.os.Process
import df.root.ExploitRunner
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ru.lavafrai.openid.BuildConfig
import ru.lavafrai.openid.data.core.DataException

/**
 * Runs the vendored DFRoot exploit in-process; the SU manager must already be installed and
 * contain `libksud.so`. The device may soft-reboot or require a manual reboot afterwards.
 */
object DfRootRunner {
    const val SU_MANAGER_PREFS = ExploitRunner.PREFS_NAME
    const val SU_MANAGER_KEY = ExploitRunner.PREF_SU_MANAGER

    fun isSupported(context: Context): Boolean =
        BuildConfig.DEBUG && Build.VERSION.SDK_INT >= 28 && Process.is64Bit() &&
            "arm64-v8a" in Build.SUPPORTED_64_BIT_ABIS &&
            listOf("libdfroot.so", "libbootstrap.so").all {
                File(context.applicationInfo.nativeLibraryDir, it).isFile
            }

    suspend fun run(
        context: Context,
        suManagerPackage: String,
        report: (String) -> Unit,
    ): Unit = withContext(Dispatchers.IO) {
        if (!isSupported(context)) throw DataException("DFROOT_UNSUPPORTED")
        context.getSharedPreferences(SU_MANAGER_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(SU_MANAGER_KEY, suManagerPackage)
            .apply()
        val exitCode = ExploitRunner.run(context) { line -> report(line) }
        if (exitCode != 0) throw DataException("DFROOT_FAILED")
    }
}
