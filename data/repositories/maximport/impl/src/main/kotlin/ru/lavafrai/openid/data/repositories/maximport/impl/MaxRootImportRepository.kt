package ru.lavafrai.openid.data.repositories.maximport.impl

import android.content.Context
import android.content.Intent
import android.os.Build
import android.webkit.WebSettings
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import ru.lavafrai.openid.data.core.DataException
import ru.lavafrai.openid.data.core.DebugTrace
import ru.lavafrai.openid.data.core.requireData
import ru.lavafrai.openid.data.repositories.max.api.MaxSessionRepository
import ru.lavafrai.openid.data.repositories.maximport.api.RootImportPackages
import ru.lavafrai.openid.data.repositories.maximport.api.RootImportRepository
import ru.lavafrai.openid.data.repositories.maximport.api.RootImportStage
import ru.lavafrai.openid.models.account.ImportOptions
import ru.lavafrai.openid.models.account.ImportedAccount
import ru.lavafrai.openid.models.account.ImportedDigitalId
import ru.lavafrai.openid.models.account.ImportedMaxAccount
import ru.lavafrai.openid.models.digitalid.DigitalIdToken
import ru.lavafrai.openid.models.max.MaxAccessToken
import ru.lavafrai.openid.models.max.MaxClientProfile
import ru.lavafrai.openid.models.max.MaxCredentials
import ru.lavafrai.openid.models.max.MaxUserAgent

/**
 * Pulls the MAX session and Digital ID material from local storage on this device via root.
 * The preference reading mirrors the reference exporter (`.prefs` binaries and `.xml` maps,
 * two stable passes); the Digital ID material is located best-effort because MAX does not
 * expose where mini-app SecureStorage is persisted. The account id is resolved with a short
 * MAX login instead of a guessed preference key.
 */
internal class MaxRootImportRepository(
    private val context: Context,
    private val sessions: MaxSessionRepository,
    private val nowMillis: () -> Long = { System.currentTimeMillis() },
) : RootImportRepository {
    private val trace = TraceLog(context)

    init {
        // Diagnostic builds also capture Digital ID HTTP rejection bodies into the trace.
        DebugTrace.sink = trace::line
    }

    private val shell: RootShell = RootShell().also { instance -> instance.diagnosticSink = trace::line }

    override suspend fun checkRootAccess(): Boolean = shell.available()

    override suspend fun isMaxInstalled(): Boolean = installed(RootImportPackages.MAX)

    override suspend fun suManagers(): List<String> = withContext(Dispatchers.IO) {
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val activities = runCatching {
            context.packageManager.queryIntentActivities(launcher, 0)
        }.getOrDefault(emptyList())
        activities.mapNotNull { info ->
            val packageName = info.activityInfo?.packageName ?: return@mapNotNull null
            runCatching {
                val application = context.packageManager.getApplicationInfo(packageName, 0)
                val nativeDir = application.nativeLibraryDir ?: return@mapNotNull null
                packageName.takeIf { File(nativeDir, KSUD_LIBRARY).exists() }
            }.getOrNull()
        }.distinct()
    }

    override suspend fun extract(onStage: (RootImportStage) -> Unit): ImportedAccount {
        trace.clear()
        onStage(RootImportStage.CHECKING_ROOT)
        val rootOk = shell.available()
        trace.line("root available=$rootOk")
        requireData(rootOk, "ROOT_ACCESS_DENIED")

        onStage(RootImportStage.LOCATING_MAX)
        val appVersion = packageVersion() ?: throw DataException("MAX_APP_MISSING")
        val user = packageUser()
        trace.line("max version=$appVersion user=$user")
        requireData(user >= 0, "MAX_APP_MISSING")

        onStage(RootImportStage.READING_PREFERENCES)
        // Why can a root session from THIS app fail to see another app's data while adb-root
        // succeeds? Capture the session context, mount namespace and path visibility level.
        trace.line("diag ctx=" + (shell.text("cat /proc/self/attr/current", ignoreExitCode = true)?.trim() ?: "null"))
        trace.line("diag mnt=" + (shell.text("readlink /proc/self/ns/mnt", ignoreExitCode = true)?.trim() ?: "null"))
        trace.line(
            "diag ls-data=" + (shell.text(
                "ls /data/user/0",
                ignoreExitCode = true,
                onResult = { code, stderr, bytes -> trace.line("diag ls-data code=$code out=$bytes err=$stderr") },
            )?.lineSequence()?.take(3)?.joinToString(",") ?: "null"),
        )
        shell.text(
            "ls /data/user/0/ru.oneme.app",
            ignoreExitCode = true,
            onResult = { code, stderr, bytes -> trace.line("diag ls-max code=$code out=$bytes err=$stderr") },
        )
        val records = stablePreferences(user)
        trace.line("prefs records=${records.size} keys=${records.map { it.key }.distinct()}")
        val accessToken = requireSessionText(records, KEY_ACCESS_TOKEN, minLength = 8, maxLength = 32_768)
        val deviceId = requireSessionText(records, KEY_DEVICE_ID, minLength = 8, maxLength = 256)
        val botId = uniqueText(records, KEY_BOT_ID) { value -> value as? Long }?.takeIf { it > 0 }
        trace.line("session deviceIdLen=${deviceId.length} botId=$botId")

        onStage(RootImportStage.VERIFYING_SESSION)
        val webUserAgent = defaultUserAgent()
        val verified = verifySession(accessToken, deviceId, appVersion, webUserAgent)
        trace.line("session verified accountId=${verified.accountId} rotated=${verified.accessToken != accessToken}")

        onStage(RootImportStage.READING_DIGITAL_ID)
        val material = DidMaterialBuilder()
        materialFromPreferences(records, material)
        if (!material.isComplete) materialFromLocalStorage(user, material)
        if (!material.isComplete) materialFromRawSweep(user, records, material)
        trace.line(
            "file material complete=${material.isComplete} salt=${material.pinSalt != null} " +
                "ts=${material.creationTimestamp != null} bio=${material.biometricDeviceId != null}",
        )

        val did = if (material.isComplete) {
            ImportedDigitalId(
                biometricDeviceId = checkNotNull(material.biometricDeviceId),
                creationTimestamp = checkNotNull(material.creationTimestamp),
                pinSalt = checkNotNull(material.pinSalt),
            )
        } else {
            // MAX encrypts its SecureStorage at rest; the plaintext never reaches the disk.
            // The last authorization token does reach MAX's own bridge log files, so read it
            // there first; the process memory ring is the fallback.
            onStage(RootImportStage.READING_DIGITAL_ID)
            val fromLogs = tokenFromLogs(user)
            when {
                fromLogs.size > 1 -> throw DataException("DID_TOKEN_AMBIGUOUS")

                fromLogs.size == 1 -> ImportedDigitalId(biometricToken = DigitalIdToken(fromLogs.first()))

                else -> {
                    onStage(RootImportStage.SCANNING_MEMORY)
                    when (val capture = MemoryTokenScanner(shell, RootImportPackages.MAX, trace).capture()) {
                        MemoryTokenScanner.Capture.NotFound -> throw DataException("DID_TOKEN_NOT_RUNNING")

                        MemoryTokenScanner.Capture.Ambiguous -> throw DataException("DID_TOKEN_AMBIGUOUS")

                        is MemoryTokenScanner.Capture.Token -> ImportedDigitalId(
                            biometricToken = DigitalIdToken(capture.value),
                        )
                    }
                }
            }
        }
        trace.line("assembling account tokenProfile=${did.tokenProfile}")
        return ImportedAccount(
            accountId = verified.accountId,
            max = ImportedMaxAccount(
                accessToken = MaxAccessToken(verified.accessToken),
                deviceId = deviceId,
                userAgent = MaxUserAgent(
                    appVersion = appVersion,
                    osVersion = Build.VERSION.RELEASE,
                    deviceName = Build.MODEL,
                    headerUserAgent = webUserAgent,
                ),
                botId = botId,
            ),
            did = did,
            webUserAgent = webUserAgent,
            options = ImportOptions(),
        )
    }

    private suspend fun installed(packageName: String): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            context.packageManager.getPackageInfo(packageName, 0)
            true
        }.getOrDefault(false)
    }

    private suspend fun packageVersion(): String? = withContext(Dispatchers.IO) {
        runCatching { context.packageManager.getPackageInfo(RootImportPackages.MAX, 0).versionName }
            .getOrNull()
            ?.takeIf { it.length in 1..64 }
    }

    private suspend fun packageUser(): Int = withContext(Dispatchers.IO) {
        runCatching { context.packageManager.getApplicationInfo(RootImportPackages.MAX, 0).uid / 100_000 }
            .getOrDefault(-1)
    }

    private suspend fun defaultUserAgent(): String = withContext(Dispatchers.Main) {
        WebSettings.getDefaultUserAgent(context)
    }.also { userAgent ->
        requireData(userAgent.length in 8..1024 && userAgent.all { it.code in 32..126 }, "WEB_USER_AGENT_INVALID")
    }

    /** Session verification resolves the account id and adopts a token rotation, if any. */
    private suspend fun verifySession(
        accessToken: String,
        deviceId: String,
        appVersion: String,
        webUserAgent: String,
    ): Verified {
        val profile = MaxClientProfile(
            userAgent = MaxUserAgent(
                appVersion = appVersion,
                osVersion = Build.VERSION.RELEASE,
                deviceName = Build.MODEL,
                headerUserAgent = webUserAgent,
            ),
            webUserAgent = webUserAgent,
        )
        // A zero account id asks the session to trust the server reply during login.
        val credentials = MaxCredentials(0L, MaxAccessToken(accessToken), deviceId)
        sessions.open(credentials, profile).use { session ->
            val result = session.login()
            val accountId = result.accountId
            requireData(accountId != null && accountId > 0, "MAX_ACCOUNT_ID_MISSING")
            return Verified(checkNotNull(accountId), result.rotatedAccessToken?.value ?: accessToken)
        }
    }

    private suspend fun stablePreferences(user: Int): List<PrefRecord> {
        trace.line("pass 1 started")
        val first = readPreferences(user)
        trace.line("pass 1 done files=${first.size}")
        val second = readPreferences(user)
        trace.line("pass 2 done files=${second.size} equal=${first == second}")
        requireData(first == second, "MAX_IMPORT_CHANGED")
        return second
    }

    private suspend fun readPreferences(user: Int): List<PrefRecord> {
        val dataRoot = "/data/user/$user/${RootImportPackages.MAX}"
        val records = mutableListOf<PrefRecord>()
        var files = 0
        var totalBytes = 0L
        for ((subdirectory, pattern) in PREFERENCE_DIRECTORIES) {
            val directory = "$dataRoot/$subdirectory"
            // No quotes, redirections or && here: app-issued su commands run without a shell.
            val listing = shell.text(
                "find $directory -maxdepth 1 -type f -name $pattern -print0",
                ignoreExitCode = true,
                onResult = { code, stderr, bytes -> trace.line("find result code=$code out=$bytes stderr=$stderr") },
            )
            trace.line("find $subdirectory chars=${listing?.length}")
            for (path in listing?.split('\u0000') ?: emptyList()) {
                if (files >= MAX_FILES || totalBytes >= MAX_TOTAL_BYTES) break
                if (!path.startsWith(directory)) continue
                val raw = shell.binary("head -c ${MAX_FILE_BYTES + 1} $path", ignoreExitCode = true) ?: continue
                if (raw.size > MAX_FILE_BYTES) continue
                files++
                totalBytes += raw.size
                records += if (path.endsWith(".prefs")) parsePrefs(raw) else XmlPrefsCodec.parse(raw)
                currentCoroutineContext().ensureActive()
            }
        }
        trace.line("prefs files=$files bytes=$totalBytes records=${records.size}")
        requireData(files > 0, "MAX_PREFERENCES_MISSING")
        return records
    }

    private fun materialFromPreferences(
        records: List<PrefRecord>,
        material: DidMaterialBuilder,
    ) {
        for (record in records) {
            when {
                record.key == KEY_PIN_SALT -> material.addPinSaltEnvelope(record.value as? String, nowMillis())

                record.key == KEY_CREATION_TIMESTAMP ->
                    material.addTimestampEnvelope(record.value as? String, nowMillis())

                record.key.contains(BIOMETRY_FRAGMENT, ignoreCase = true) ->
                    material.addBiometryId(record.value as? String)
            }
        }
    }

    private suspend fun materialFromLocalStorage(
        user: Int,
        material: DidMaterialBuilder,
    ) {
        val webviewRoot = "/data/user/$user/${RootImportPackages.MAX}/app_webview"
        val listing = shell.text(
            "find $webviewRoot -type f -path *leveldb* -size -${MAX_FILE_BYTES + 1}c -print0",
            timeoutMillis = READ_TIMEOUT_MILLIS,
            ignoreExitCode = true,
        ) ?: return
        var files = 0
        for (path in listing.split('\u0000')) {
            if (!path.contains("leveldb") || files >= MAX_FILES) continue
            val raw = shell.binary("head -c ${MAX_FILE_BYTES + 1} $path", ignoreExitCode = true) ?: continue
            if (raw.size > MAX_FILE_BYTES) continue
            files++
            val values = LocalStorageCodec.scan(raw, setOf(KEY_PIN_SALT, KEY_CREATION_TIMESTAMP))
            material.addPinSaltEnvelope(values[KEY_PIN_SALT], nowMillis())
            material.addTimestampEnvelope(values[KEY_CREATION_TIMESTAMP], nowMillis())
            currentCoroutineContext().ensureActive()
            if (material.isComplete) return
        }
    }

    private suspend fun materialFromRawSweep(
        user: Int,
        records: List<PrefRecord>,
        material: DidMaterialBuilder,
    ) {
        val dataRoot = "/data/user/$user/${RootImportPackages.MAX}"
        val listing = shell.text(
            "grep -rls -e $KEY_PIN_SALT -e $KEY_CREATION_TIMESTAMP $dataRoot" +
                " --exclude-dir=cache --exclude-dir=code_cache --exclude-dir=no_backup",
            timeoutMillis = SWEEP_TIMEOUT_MILLIS,
            ignoreExitCode = true,
        ) ?: return
        var files = 0
        for (path in listing.split('\n')) {
            if (files >= MAX_FILES) break
            if (!path.startsWith("/data/user/$user/${RootImportPackages.MAX}")) continue
            val raw = shell.binary("head -c ${MAX_FILE_BYTES + 1} $path", ignoreExitCode = true) ?: continue
            if (raw.size > MAX_FILE_BYTES) continue
            files++
            when {
                path.endsWith(".prefs") -> materialFromPreferences(parsePrefs(raw), material)

                path.endsWith(".xml") -> materialFromPreferences(XmlPrefsCodec.parse(raw), material)

                path.contains("leveldb") -> {
                    val values = LocalStorageCodec.scan(raw, setOf(KEY_PIN_SALT, KEY_CREATION_TIMESTAMP))
                    material.addPinSaltEnvelope(values[KEY_PIN_SALT], nowMillis())
                    material.addTimestampEnvelope(values[KEY_CREATION_TIMESTAMP], nowMillis())
                }

                else -> {
                    material.addPinSaltEnvelope(RawFileScanner.envelopes(raw, KEY_PIN_SALT).firstOrNull(), nowMillis())
                    material.addTimestampEnvelope(
                        RawFileScanner.envelopes(raw, KEY_CREATION_TIMESTAMP).firstOrNull(),
                        nowMillis(),
                    )
                }
            }
            currentCoroutineContext().ensureActive()
            if (material.isComplete) return
        }
        if (!material.isComplete) materialFromPreferences(records, material)
    }

    /**
     * MAX's webapp bridge log keeps the last authorization token in plaintext; the log files are
     * named `YYYY_MM_DD_…log` so sorting by name yields the freshest first.
     */
    private suspend fun tokenFromLogs(user: Int): Set<String> {
        val logsRoot = "/data/user/$user/${RootImportPackages.MAX}/logs"
        val listing = shell.text(
            "find $logsRoot -maxdepth 1 -type f -name *.log -print0",
            ignoreExitCode = true,
        ) ?: return emptySet()
        val paths = listing
            .split('\u0000')
            .filter { it.startsWith(logsRoot) && it.endsWith(".log") }
            .sortedDescending()
            .take(MAX_LOG_FILES)
        val found = linkedSetOf<String>()
        for (path in paths) {
            val raw = shell.binary("head -c ${MAX_FILE_BYTES + 1} $path", ignoreExitCode = true) ?: continue
            if (raw.size > MAX_FILE_BYTES) continue
            TokenScan.collect(raw, found)
            currentCoroutineContext().ensureActive()
            if (found.size > 1) break
        }
        trace.line("log tokens=${found.size} files=${paths.size}")
        return found
    }

    private fun requireSessionText(
        records: List<PrefRecord>,
        key: String,
        minLength: Int,
        maxLength: Int,
    ): String {
        val value = uniqueText(records, key) { candidate -> candidate as? String }
        requireData(value != null, "MAX_PREFERENCES_MISSING")
        val text = checkNotNull(value)
        requireData(
            text.length in minLength..maxLength &&
                text.none { it.code < 32 || it.code == 127 } &&
                text.none { it.isWhitespace() },
            "MAX_PREFERENCES_MISSING",
        )
        return text
    }

    private fun <T> uniqueText(
        records: List<PrefRecord>,
        key: String,
        transform: (Any) -> T?,
    ): T? {
        val values = records.filter { it.key == key }.mapNotNull { transform(it.value) }.distinct()
        requireData(values.size <= 1, "MAX_SESSION_AMBIGUOUS")
        return values.singleOrNull()
    }

    /** Exact session keys first; biometry hints come from the walk-back fragment scan. */
    private fun parsePrefs(raw: ByteArray): List<PrefRecord> =
        JavaPrefsCodec.scan(raw, SESSION_STRING_KEYS, SESSION_NUMERIC_KEYS) +
            JavaPrefsCodec.scanFragments(raw, BIOMETRY_FRAGMENTS)

    private class Verified(
        val accountId: Long,
        val accessToken: String,
    )

    private companion object {
        const val KEY_ACCESS_TOKEN = "auth.token"
        const val KEY_DEVICE_ID = "device.id"
        const val KEY_BOT_ID = "digitalid-botid"
        const val KEY_PIN_SALT = "pin_salt::digitalId"
        const val KEY_CREATION_TIMESTAMP = "creation_timestamp::digitalId"
        const val BIOMETRY_FRAGMENT = "biomet"
        const val KSUD_LIBRARY = "libksud.so"
        val SESSION_STRING_KEYS = setOf(KEY_ACCESS_TOKEN, KEY_DEVICE_ID, KEY_PIN_SALT, KEY_CREATION_TIMESTAMP)
        val SESSION_NUMERIC_KEYS = setOf(KEY_BOT_ID)
        val BIOMETRY_FRAGMENTS = setOf(BIOMETRY_FRAGMENT)
        val PREFERENCE_DIRECTORIES = listOf("app_file_prefs" to "*.prefs", "shared_prefs" to "*.xml")
        const val MAX_FILES = 128
        const val MAX_LOG_FILES = 6
        const val MAX_FILE_BYTES = 8_388_608
        const val MAX_TOTAL_BYTES = 32_000_000L
        const val READ_TIMEOUT_MILLIS = 20_000L
        const val SWEEP_TIMEOUT_MILLIS = 30_000L
    }
}
