package ru.lavafrai.openid.ui.dfroot

import android.content.Context
import df.root.ExploitRunner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Runs the vendored DFRoot exploit in-process; the SU manager must already be installed and
 * contain `libksud.so`. The device may soft-reboot or require a manual reboot afterwards.
 */
object DfRootRunner {
    const val SU_MANAGER_PREFS = "dfroot"
    const val SU_MANAGER_KEY = "su_manager"

    suspend fun run(
        context: Context,
        suManagerPackage: String,
        report: (String) -> Unit,
    ): Unit = withContext(Dispatchers.IO) {
        context.getSharedPreferences(SU_MANAGER_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(SU_MANAGER_KEY, suManagerPackage)
            .apply()
        try {
            ExploitRunner.run(context) { line -> report(line) }
        } catch (failure: Exception) {
            report("ERROR: ${failure.javaClass.simpleName}")
        }
    }
}
