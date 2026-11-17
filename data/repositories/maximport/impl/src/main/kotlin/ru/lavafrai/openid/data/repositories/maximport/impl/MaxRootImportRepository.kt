package ru.lavafrai.openid.data.repositories.maximport.impl

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Process
import android.webkit.WebSettings
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import ru.lavafrai.openid.data.core.DataException
import ru.lavafrai.openid.data.core.DebugTrace
import ru.lavafrai.openid.data.core.requireData
import ru.lavafrai.openid.data.repositories.max.api.MaxSessionRepository
import ru.lavafrai.openid.data.repositories.maximport.api.RootImportPackages
import ru.lavafrai.openid.data.repositories.maximport.api.RootImportRepository
import ru.lavafrai.openid.data.repositories.maximport.api.RootImportStage
import ru.lavafrai.openid.data.repositories.maximport.impl.BuildConfig
import ru.lavafrai.openid.models.account.ImportOptions
import ru.lavafrai.openid.models.account.ImportedAccount
import ru.lavafrai.openid.models.account.ImportedDigitalId
import ru.lavafrai.openid.models.account.ImportedMaxAccount
import ru.lavafrai.openid.models.digitalid.DigitalIdToken
import ru.lavafrai.openid.models.max.MaxAccessToken
import ru.lavafrai.openid.models.max.MaxClientProfile
import ru.lavafrai.openid.models.max.MaxCredentials
import ru.lavafrai.openid.models.max.MaxUserAgent

/** Pure so the AOSP `--user` semantics can be checked without a device: see MaxRootImportRepository.returnToApp. */
internal fun buildReturnToAppCommand(
    component: String,
    userId: Int,
): String = "am start -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -n $component --user $userId"

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
    private val trace = if (BuildConfig.DEBUG) TraceLog(context) else TraceLog.NOOP

    init {
        if (BuildConfig.DEBUG) {
            DebugTrace.sink = trace::line
        }
    }

    private val shell: RootShell = RootShell().also { instance ->
        if (BuildConfig.DEBUG) instance.diagnosticSink = trace::line
    }

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

    override suspend fun returnToApp() {
        val component = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?.component
            ?.flattenToShortString()
            ?.takeIf { APP_COMPONENT.matches(it) }
            ?: return
        // `am start` run as root defaults to the foreground Android user, not this app's own
        // profile: if OpenID and MAX run in a work profile while the personal profile is in the
        // foreground, an unqualified command would open (or fail to find) the wrong copy of the
        // app. --user pins it to this process's own profile, derived the same way packageUser()
        // derives another app's from its UID.
        val userId = Process.myUid() / ANDROID_USER_RANGE
        shell.text(buildReturnToAppCommand(component, userId), ignoreExitCode = true)
    }

    override suspend fun extract(onStage: (RootImportStage) -> Unit): ImportedAccount = coroutineScope {
        trace.clear()
        val injector = FridaInjector(context, shell, trace)
        // Decompress the embedded frida binary in parallel with the preference/session reads so it
        // is ready when the capture starts, instead of adding its one-time cost to the user's wait.
        val binaryInstalled = async(Dispatchers.IO) { injector.install() }
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

        onStage(RootImportStage.PREPARING_DIGITAL_ID)
        binaryInstalled.await()
        // The first capture can miss even when the Digital ID is unlocked: MAX may not have rendered
        // the live Digital ID WebView into its heap yet, so the agent's single onResume snapshot and
        // the file/log/memory fallbacks all come back empty and the pass would surface
        // DID_TOKEN_NOT_RUNNING. That first pass still brings MAX forward and warms the WebView, so a
        // re-run reliably succeeds — exactly what a manual restart did. The whole acquisition is
        // therefore retried a bounded number of times before the error is surfaced.
        var captured: ImportedDigitalId? = null
        for (attempt in 1..DID_CAPTURE_ATTEMPTS) {
            val lastAttempt = attempt == DID_CAPTURE_ATTEMPTS
            val material = DidMaterialBuilder()
            // The embedded frida agent is the primary source: it snapshots the official bridge of the
            // live Digital ID WebView exactly like the reference exporter. The agent must be attached
            // while MAX is backgrounded and MAX only brought forward afterwards, so the switch to MAX
            // (driven by the READING_DIGITAL_ID stage) is deferred until the agent has armed its hook.
            // A successful capture returns as soon as the agent posts its result, so the wait only
            // bites on a miss. The non-final warm-up pass is expected to miss and get retried, so it
            // gives up quickly; only the final pass keeps the longer ceiling.
            val fridaCapture = injector.capture(
                scope = this@coroutineScope,
                user = user,
                onAgentReady = { onStage(RootImportStage.READING_DIGITAL_ID) },
                waitMillis = if (lastAttempt) DID_FINAL_WAIT_MILLIS else DID_WARMUP_WAIT_MILLIS,
            )
            when (fridaCapture) {
                is FridaInjector.Capture.Material -> {
                    val payload = fridaCapture.payload
                    material.addPinSalt(payload["pinSalt"] as? String)
                    material.addTimestamp(payload["creationTimestamp"])
                    material.addBiometryId((payload["biometry"] as? Map<*, *>)?.get("deviceId") as? String)
                    trace.line(
                        "frida material salt=${material.pinSalt != null} ts=${material.creationTimestamp != null} " +
                            "bio=${material.biometricDeviceId != null}",
                    )
                }

                is FridaInjector.Capture.Failed -> trace.line("frida failed: ${fridaCapture.code}")
            }
            if (!material.isComplete) materialFromPreferences(records, material)
            if (!material.isComplete) materialFromLocalStorage(user, material)
            if (!material.isComplete) materialFromRawSweep(user, records, material)
            trace.line(
                "file material complete=${material.isComplete} salt=${material.pinSalt != null} " +
                    "ts=${material.creationTimestamp != null} bio=${material.biometricDeviceId != null}",
            )

            captured = if (material.isComplete) {
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
                        when (val capture = MemoryTokenScanner(shell, RootImportPackages.MAX, user, trace).capture()) {
                            MemoryTokenScanner.Capture.NotFound ->
                                if (lastAttempt) throw DataException("DID_TOKEN_NOT_RUNNING") else null

                            MemoryTokenScanner.Capture.Ambiguous -> throw DataException("DID_TOKEN_AMBIGUOUS")

                            is MemoryTokenScanner.Capture.Token -> ImportedDigitalId(
                                biometricToken = DigitalIdToken(capture.value),
                            )
                        }
                    }
                }
            }
            if (captured != null) break
            trace.line("digital id not resident yet, retrying attempt=$attempt")
            delay(DID_RETRY_DELAY_MILLIS)
        }
        val did = checkNotNull(captured)
        trace.line("assembling account tokenProfile=${did.tokenProfile}")
        trace.clear()
        ImportedAccount(
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
        runCatching { context.packageManager.getApplicationInfo(RootImportPackages.MAX, 0).uid / ANDROID_USER_RANGE }
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
        // The user is sent to MAX during the import, so this app is often backgrounded while the login
        // is in flight and the OS may cut its sockets. One fresh connection absorbs that; any other
        // failure, a second connection failure and cancellation are reported as before.
        var attempt = 0
        while (true) {
            try {
                sessions.open(credentials, profile).use { session ->
                    val result = session.login()
                    val accountId = result.accountId
                    requireData(accountId != null && accountId > 0, "MAX_ACCOUNT_ID_MISSING")
                    return Verified(checkNotNull(accountId), result.rotatedAccessToken?.value ?: accessToken)
                }
            } catch (failure: DataException) {
                if (failure.code != "MAX_TCP_CONNECTION_FAILED" || ++attempt > 1) throw failure
                currentCoroutineContext().ensureActive()
                trace.line("max login connection failed, retrying once")
            }
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
            // su -M -c hands the command string to a shell (see RootShell), so operands follow
            // shell word-splitting. These operands are built only from constants ($directory and
            // $pattern below contain no spaces or metacharacters), so they need no quoting; a
            // captured file path, which may contain spaces (e.g. "Local Storage"), is always
            // wrapped with shellArg() before interpolation so it stays a single argument.
            val listing = shell.text(
                "find $directory -maxdepth 1 -type f -name $pattern -print0",
                ignoreExitCode = true,
                onResult = { code, stderr, bytes -> trace.line("find result code=$code out=$bytes stderr=$stderr") },
            )
            trace.line("find $subdirectory chars=${listing?.length}")
            for (path in listing?.split('\u0000') ?: emptyList()) {
                if (files >= MAX_FILES || totalBytes >= MAX_TOTAL_BYTES) break
                if (!path.startsWith(directory)) continue
                val raw =
                    shell.binary("head -c ${MAX_FILE_BYTES + 1} ${shellArg(path)}", ignoreExitCode = true) ?: continue
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
            "find $webviewRoot -type d -name leveldb -print0",
            timeoutMillis = READ_TIMEOUT_MILLIS,
            ignoreExitCode = true,
        ) ?: return
        val directories = listing.split('\u0000').filter { it.startsWith(webviewRoot) && it.endsWith("/leveldb") }
        for (directory in directories.take(MAX_LEVELDB_DIRECTORIES)) {
            applyLocalStorage(readLevelDb(directory), material)
            currentCoroutineContext().ensureActive()
            if (material.isComplete) return
        }
    }

    /**
     * Reads every file of one LevelDB directory (including CURRENT and MANIFEST) so the codec can
     * resolve the database's current state. Nothing is applied until all files were merged: a value
     * in one file may be superseded or deleted by another, and a file that could not be read leaves
     * the state untrusted.
     */
    private suspend fun readLevelDb(directory: String): LocalStorageCodec.State {
        val state = LocalStorageCodec.State(LOCAL_STORAGE_KEYS)
        val listing = shell.text(
            "find ${shellArg(directory)} -maxdepth 1 -type f -print0",
            timeoutMillis = READ_TIMEOUT_MILLIS,
            ignoreExitCode = true,
        )
        if (listing == null) {
            state.markIncomplete()
            return state
        }
        var files = 0
        for (path in listing.split('\u0000')) {
            if (!path.startsWith("$directory/")) continue
            if (files >= MAX_FILES) {
                state.markIncomplete()
                break
            }
            val raw = shell.binary("head -c ${MAX_FILE_BYTES + 1} ${shellArg(path)}", ignoreExitCode = true)
            if (raw == null || raw.size > MAX_FILE_BYTES) {
                state.markIncomplete()
                continue
            }
            files++
            state.add(path, raw)
            currentCoroutineContext().ensureActive()
        }
        return state
    }

    private fun applyLocalStorage(
        state: LocalStorageCodec.State,
        material: DidMaterialBuilder,
    ) {
        val values = state.values()
        material.addPinSaltEnvelope(values[KEY_PIN_SALT], nowMillis())
        material.addTimestampEnvelope(values[KEY_CREATION_TIMESTAMP], nowMillis())
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
        val levelDbDirectories = linkedSetOf<String>()
        for (path in listing.split('\n')) {
            if (!path.startsWith("/data/user/$user/${RootImportPackages.MAX}")) continue
            if (path.contains("leveldb")) {
                // Resolved per database after the loop: one file alone cannot tell whether a key
                // was superseded, and CURRENT/MANIFEST never contain the searched keys.
                levelDbDirectories += path.substringBeforeLast('/')
                continue
            }
            if (files >= MAX_FILES) break
            val raw = shell.binary("head -c ${MAX_FILE_BYTES + 1} ${shellArg(path)}", ignoreExitCode = true) ?: continue
            if (raw.size > MAX_FILE_BYTES) continue
            files++
            when {
                path.endsWith(".prefs") -> materialFromPreferences(parsePrefs(raw), material)

                path.endsWith(".xml") -> materialFromPreferences(XmlPrefsCodec.parse(raw), material)

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
        for (directory in levelDbDirectories.take(MAX_LEVELDB_DIRECTORIES)) {
            applyLocalStorage(readLevelDb(directory), material)
            currentCoroutineContext().ensureActive()
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
            val raw = shell.binary("head -c ${MAX_FILE_BYTES + 1} ${shellArg(path)}", ignoreExitCode = true) ?: continue
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

    /**
     * Wraps a captured file path as a single POSIX shell word so a space (e.g. "Local Storage")
     * cannot split it into several arguments once su passes the command to a shell. Embedded single
     * quotes are escaped the standard way ('"'"' → close, literal quote, reopen) rather than left to
     * break the quoting; app-private WebView paths never contain one, but the escaping keeps the
     * command well-formed regardless of the bytes find returns.
     */
    private fun shellArg(path: String): String = "'" + path.replace("'", "'\\''") + "'"

    private companion object {
        const val KEY_ACCESS_TOKEN = "auth.token"
        const val KEY_DEVICE_ID = "device.id"
        const val KEY_BOT_ID = "digitalid-botid"
        const val KEY_PIN_SALT = "pin_salt::digitalId"
        const val KEY_CREATION_TIMESTAMP = "creation_timestamp::digitalId"
        private const val MAX_LEVELDB_DIRECTORIES = 4
        private val APP_COMPONENT = Regex("[A-Za-z0-9_.]+/[A-Za-z0-9_.]+")

        /** UID-to-user-id divisor (AOSP's hidden UserHandle.PER_USER_RANGE); stable across Android versions. */
        internal const val ANDROID_USER_RANGE = 100_000
        private val LOCAL_STORAGE_KEYS = setOf(KEY_PIN_SALT, KEY_CREATION_TIMESTAMP)
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

        // Full Digital ID capture passes before DID_TOKEN_NOT_RUNNING is surfaced. The first pass
        // can come back empty while it warms MAX's live WebView into the heap; a second pass then
        // finds the token, which is what a manual import restart did.
        const val DID_CAPTURE_ATTEMPTS = 2
        const val DID_RETRY_DELAY_MILLIS = 1_500L

        // Per-pass ceilings for the frida wait. A capture returns early on success, so these only cap
        // a miss: the warm-up pass bails fast because it is retried, the final pass keeps more slack.
        const val DID_WARMUP_WAIT_MILLIS = 12_000L
        const val DID_FINAL_WAIT_MILLIS = 30_000L
    }
}
