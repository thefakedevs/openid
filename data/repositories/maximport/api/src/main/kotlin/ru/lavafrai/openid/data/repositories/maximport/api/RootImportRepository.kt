package ru.lavafrai.openid.data.repositories.maximport.api

import ru.lavafrai.openid.models.account.ImportedAccount

/** Progress reported while pulling MAX credentials from local storage through root access. */
enum class RootImportStage {
    CHECKING_ROOT,
    LOCATING_MAX,
    READING_PREFERENCES,
    VERIFYING_SESSION,
    READING_DIGITAL_ID,
}

/** Installed packages and links used by the automatic import flow. */
object RootImportPackages {
    const val MAX = "ru.oneme.app"
    const val DFROOT = "df.root"
    const val DFROOT_RELEASES_URL = "https://github.com/diabl0w/DFRoot/releases/latest"
}

interface RootImportRepository {
    /** True when `su` answers as uid 0; the first call may surface a root manager grant dialog. */
    suspend fun checkRootAccess(): Boolean

    /** MAX is installed and visible to this application. */
    suspend fun isMaxInstalled(): Boolean

    /** DFRoot is installed and launchable. */
    suspend fun isDfRootInstalled(): Boolean

    /**
     * Reads the MAX session and Digital ID material from local storage via root and verifies the
     * session with a short MAX login; the account id comes from the server reply. The result stays
     * in memory only. DataException reports failure causes as stable diagnostic codes.
     */
    suspend fun extract(onStage: (RootImportStage) -> Unit = {}): ImportedAccount
}
