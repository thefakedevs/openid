package ru.lavafrai.openid.data.repositories.maximport.api

import ru.lavafrai.openid.models.account.ImportedAccount

/** Progress reported while pulling MAX credentials from local storage through root access. */
enum class RootImportStage {
    CHECKING_ROOT,
    LOCATING_MAX,
    READING_PREFERENCES,
    VERIFYING_SESSION,
    PREPARING_DIGITAL_ID,
    READING_DIGITAL_ID,
    SCANNING_MEMORY,
}

/** Installed packages and links used by the automatic import flow. */
object RootImportPackages {
    const val MAX = "ru.oneme.app"
    const val KSU_RELEASES_URL = "https://github.com/tiann/KernelSU/releases/latest"

    /** Name of the debug-only extraction trace, written in the app cache directory. */
    const val TRACE_LOG_FILE = "maximport_trace.log"
}

interface RootImportRepository {
    /** True when `su` answers as uid 0; the first call may surface a root manager grant dialog. */
    suspend fun checkRootAccess(): Boolean

    /** MAX is installed and visible to this application. */
    suspend fun isMaxInstalled(): Boolean

    /** Installed SU managers able to provide `libksud.so` for the embedded DFRoot exploit. */
    suspend fun suManagers(): List<String>

    /**
     * Reads the MAX session and Digital ID material from local storage via root and verifies the
     * session with a short MAX login; the account id comes from the server reply. When MAX stores
     * its Digital ID SecureStorage encrypted, the last authorization token is captured from the
     * MAX process memory instead, producing a token profile without PIN material. The result
     * stays in memory only. DataException reports failure causes as stable diagnostic codes.
     */
    suspend fun extract(onStage: (RootImportStage) -> Unit = {}): ImportedAccount

    /**
     * Brings this application back to the foreground after the user was sent to MAX. Best effort:
     * a backgrounded app may not start its own activity, so it is launched as root, like the
     * launcher would. Does nothing without root; never throws.
     */
    suspend fun returnToApp()
}
