package ru.lavafrai.openid.ui

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.annotation.StringRes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import ru.lavafrai.openid.R
import ru.lavafrai.openid.data.core.VaultAuthentication
import ru.lavafrai.openid.data.core.VaultProtection
import ru.lavafrai.openid.data.repositories.maximport.api.RootImportPackages
import ru.lavafrai.openid.data.repositories.maximport.api.RootImportStage
import ru.lavafrai.openid.models.account.AccountState
import ru.lavafrai.openid.models.account.ImportedAccount
import ru.lavafrai.openid.models.auth.AuthenticationException
import ru.lavafrai.openid.models.pin.PinProtection
import ru.lavafrai.openid.models.pin.PinSource
import ru.lavafrai.openid.models.pin.SavedPinState
import ru.lavafrai.openid.navigation.ImportMethod
import ru.lavafrai.openid.navigation.ImportMode
import ru.lavafrai.openid.navigation.PocketDestination
import ru.lavafrai.openid.navigation.RootSource

/** App-level UI state, kept by a ViewModel. Secrets are not written to SavedStateHandle or route arguments. */
class PocketController(
    private val actions: PocketActions,
    private val scope: CoroutineScope,
    private val pinPreferences: PinProtectionPreferences,
    private val context: Context,
    nowMillis: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    private val mutableState = MutableStateFlow(PocketUiState())
    val state: StateFlow<PocketUiState> = mutableState.asStateFlow()
    private var replacementSettings: ReplacementSettings? = null
    private var observer: Job? = null
    private var rootCheckJob: Job? = null
    private var foreground = true
    private var disposed = false
    private val authentication = AuthenticationCoordinator { prompt ->
        mutableState.update { it.copy(authPrompt = prompt) }
    }
    private val attempts = PinAttemptLimiter(context, scope, nowMillis) { seconds ->
        mutableState.update { it.copy(retrySeconds = seconds) }
    }
    private val imports = ImportFlow(actions.importAccount, actions.replaceAccount, scope, nowMillis, ::expireImport)
    private val tokenLock = TokenProfileLock(context)
    private val operations = ScreenOperations(
        scope = scope,
        onStarted = {
            mutableState.update { it.copy(screenBusy = true, message = null, notice = null, screenError = null) }
        },
        onFailure = { error -> mutableState.update { it.copy(screenError = error) } },
        onFinished = {
            mutableState.update { it.copy(screenBusy = false) }
            if (!foreground && !disposed && state.value.editingImport) imports.onBackground()
        },
    )
    private val qrSession = QrSessionController(
        actions.generateQr,
        scope,
        nowMillis,
        ::onSessionChanged,
        attempts::rejected,
    ) { result, source, repair ->
        when {
            result.canRetryInSession -> ErrorRecovery.QR

            state.value.editingImport || state.value.destination in
                setOf(PocketDestination.QR, PocketDestination.SETTINGS) -> ErrorRecovery.UNLOCK

            source is PinSource.Saved && !repair && !state.value.replaceSavedPin -> ErrorRecovery.BIOMETRIC

            else -> null
        }
    }

    init {
        observeStorage()
    }

    fun dispatch(action: PocketAction) {
        when (action) {
            is PocketAction.EditLink -> editForm { it.copy(link = action.value) }

            is PocketAction.EditPassword -> editForm { it.copy(password = action.value) }

            is PocketAction.EditPin -> if (!state.value.busy) {
                mutableState.update {
                    it.copy(pin = action.value.filter { char -> char in '0'..'9' }.take(4), message = null)
                }
            }

            is PocketAction.SelectImportMethod -> selectImportMethod(action.value)

            PocketAction.ContinueImportMethod -> continueImportMethod()

            is PocketAction.SelectRootSource -> selectRootSource(action.value)

            PocketAction.ContinueRootImport -> continueRootImport()

            PocketAction.RefreshRootCheck -> refreshRootCheck()

            PocketAction.RunDfRoot -> runDfRoot()

            PocketAction.MigrateAccountStorage -> migrateAccountStorage()

            is PocketAction.SelectPinProtection -> selectPinProtection(action.value)

            PocketAction.ContinueImport -> continueImport()

            PocketAction.CommitImport -> commitImport()

            PocketAction.SubmitPin -> submitPin()

            PocketAction.SubmitPinWithoutSaving -> submitPin(save = false)

            PocketAction.UseSavedPin -> useSavedPin()

            PocketAction.AutoUnlock -> autoUnlock()

            PocketAction.RefreshQr -> refreshQr()

            is PocketAction.RecoverError -> recoverError(action.error)

            PocketAction.OpenSettings -> openSettings()

            PocketAction.ApplySettings -> applySettings()

            is PocketAction.DismissNotice -> mutableState.update {
                if (it.notice === action.notice) it.copy(notice = null) else it
            }

            PocketAction.ReplaceAccount -> if (!state.value.busy) beginImport(ImportMode.REPLACE)

            PocketAction.AskDelete -> askDelete()

            PocketAction.ConfirmDelete -> deleteAccount()

            PocketAction.DismissDelete -> mutableState.update { it.copy(confirmDelete = false) }

            is PocketAction.ConfirmAuth -> authentication.confirm(action.request)

            is PocketAction.CancelAuth -> if (state.value.authPrompt === action.request) {
                mutableState.update { it.copy(message = UiText(R.string.notice_auth_cancelled)) }
                authentication.cancel()
            }

            is PocketAction.AuthFailed -> if (state.value.authPrompt === action.request) {
                authentication.fail(action.request, AuthenticationException(action.reason, details = action.diagnostic))
            }

            is PocketAction.Back -> if (state.value.destination == action.from) back()

            PocketAction.EndSession -> endSession()

            PocketAction.RetryStartup -> observeStorage()
        }
    }

    private fun observeStorage() {
        observer?.cancel()
        mutableState.update { it.copy(message = null, screenError = null) }
        observer = scope.launch {
            try {
                combine(actions.accountState(), actions.savedPinState()) { account, pin -> account to pin }
                    .collect { (account, pin) ->
                        val unavailable = (account as? AccountState.Unavailable)?.diagnostic
                            ?: (pin as? SavedPinState.Unavailable)?.diagnostic
                        if (unavailable != null) {
                            qrSession.stop()
                            authentication.cancel()
                            mutableState.update {
                                it.copy(
                                    destination = PocketDestination.START,
                                    screenError = unavailable.toPocketError(
                                        ErrorOperation.STARTUP,
                                        ErrorRecovery.STARTUP,
                                    ),
                                    message = null,
                                    authPrompt = null,
                                    autoUnlockPending = false,
                                    screenBusy = false,
                                )
                            }
                            return@collect
                        }
                        if (account == AccountState.Loading || pin == SavedPinState.Loading) return@collect
                        val summary = when (account) {
                            is AccountState.Locked -> account.summary
                            is AccountState.Unlocked -> account.summary
                            is AccountState.MigrationRequired -> account.summary
                            else -> null
                        }
                        val wasStarting = state.value.destination == PocketDestination.START
                        mutableState.update {
                            it.copy(
                                account = summary,
                                savedPin = pin,
                                accountMigrationRequired = account is AccountState.MigrationRequired,
                            )
                        }
                        if (wasStarting) {
                            pinPreferences.recoverPendingImport(summary?.accountId)
                            mutableState.update {
                                it.copy(
                                    destination = if (summary == null) {
                                        PocketDestination.METHOD
                                    } else {
                                        PocketDestination.UNLOCK
                                    },
                                    unlockPinProtection = effectivePinProtection(),
                                    tokenProfile = summary != null && pinPreferences.isTokenProfile(summary.accountId),
                                    tokenLockConfigured = summary != null && tokenLock.isConfigured(summary.accountId),
                                    tokenLockBiometricAvailable = summary != null &&
                                        tokenLock.biometricEnabled(summary.accountId) &&
                                        VaultAuthentication.unavailable(context, VaultProtection.BIOMETRIC) == null,
                                    autoUnlockPending = true,
                                )
                            }
                        } else if (summary == null &&
                            state.value.destination !in
                            setOf(
                                PocketDestination.METHOD,
                                PocketDestination.IMPORT,
                                PocketDestination.ROOT_SOURCE,
                                PocketDestination.SETUP,
                            )
                        ) {
                            beginImport(ImportMode.NEW)
                        }
                    }
            } catch (failure: Throwable) {
                if (failure is CancellationException || failure is Error) throw failure
                qrSession.stop()
                mutableState.update {
                    it.copy(
                        destination = PocketDestination.START,
                        screenError = failure.toPocketError(ErrorOperation.STARTUP, ErrorRecovery.STARTUP),
                        screenBusy = false,
                    )
                }
            }
        }
    }

    private fun editForm(change: (ImportForm) -> ImportForm) {
        if (!state.value.busy) {
            mutableState.update {
                it.copy(importForm = change(it.importForm), message = null, screenError = null)
            }
        }
    }

    private fun selectImportMethod(method: ImportMethod) {
        if (state.value.busy) return
        mutableState.update { it.copy(importMethod = method, message = null, screenError = null) }
    }

    private fun continueImportMethod() {
        val current = state.value
        if (current.destination != PocketDestination.METHOD || current.busy) return
        when (current.importMethod) {
            ImportMethod.MANUAL -> mutableState.update {
                it.copy(destination = PocketDestination.IMPORT, message = null, screenError = null)
            }

            ImportMethod.AUTO -> {
                imports.clear()
                mutableState.update {
                    it.copy(
                        destination = PocketDestination.ROOT_SOURCE,
                        rootSource = null,
                        rootStage = null,
                        importForm = ImportForm(),
                        message = null,
                        screenError = null,
                    )
                }
                checkRootEnvironment()
            }

            null -> mutableState.update { it.copy(message = UiText(R.string.notice_method_required)) }
        }
    }

    private fun selectRootSource(source: RootSource) {
        if (state.value.busy) return
        mutableState.update { it.copy(rootSource = source, message = null, screenError = null) }
    }

    private fun refreshRootCheck() {
        if (state.value.destination != PocketDestination.ROOT_SOURCE || state.value.busy) return
        checkRootEnvironment()
    }

    private suspend fun probeRootEnvironment() {
        mutableState.update { it.copy(rootCheck = RootCheckState.CHECKING) }
        val environment = try {
            actions.checkRootEnvironment()
        } catch (failure: CancellationException) {
            throw failure
        } catch (_: Exception) {
            mutableState.update { it.copy(rootCheck = RootCheckState.UNAVAILABLE) }
            return
        }
        mutableState.update {
            it.copy(
                rootCheck = if (environment.rootAvailable) {
                    RootCheckState.AVAILABLE
                } else {
                    RootCheckState.UNAVAILABLE
                },
                maxInstalled = environment.maxInstalled,
                suManagers = environment.suManagers,
            )
        }
    }

    private fun checkRootEnvironment() {
        if (state.value.busy || rootCheckJob?.isActive == true) return
        rootCheckJob = scope.launch { probeRootEnvironment() }
    }

    private fun runDfRoot() {
        val current = state.value
        if (current.destination != PocketDestination.ROOT_SOURCE || current.busy) return
        val manager = current.suManagers.firstOrNull() ?: return
        runOperation(ErrorOperation.ROOT_IMPORT, ErrorRecovery.ROOT_IMPORT) {
            actions.runDfRoot(manager) { line ->
                mutableState.update { it.copy(rootStage = null, message = UiText(direct = line.take(160))) }
            }
            currentCoroutineContext().ensureActive()
            mutableState.update { it.copy(message = null, rootStage = null) }
            probeRootEnvironment()
        }
    }

    private fun continueRootImport() {
        val current = state.value
        if (current.destination != PocketDestination.ROOT_SOURCE || current.busy || current.rootSource == null) return
        // Durable: the frida capture waits for the user to open Digital ID in MAX, which
        // backgrounds this app; the operation must survive that round trip.
        runOperation(ErrorOperation.ROOT_IMPORT, ErrorRecovery.ROOT_IMPORT, durable = true) {
            try {
                var switchedToMax = false
                val account = actions.extractMaxAccount { stage ->
                    mutableState.update { it.copy(rootStage = stage) }
                    if (stage == RootImportStage.READING_DIGITAL_ID && !switchedToMax) {
                        switchedToMax = true
                        switchToMaxDigitalId()
                    }
                }
                currentCoroutineContext().ensureActive()
                imports.hold(account)
                showToast(R.string.root_import_done_toast)
                // A captured-token profile has no Digital ID PIN to protect it (GenerateQrUseCase
                // authorizes it with the bare token, bypassing PIN checks entirely), so without a
                // deliberate local lock, opening its QR would need no confirmation at all once the
                // phone is unlocked. The setup screen still asks for a protection choice here; it
                // is enforced locally by TokenProfileLock instead of the server-verified PIN flow.
                mutableState.update {
                    it.copy(
                        rootStage = null,
                        tokenProfile = account.did.tokenProfile,
                        destination = PocketDestination.SETUP,
                        setupPinProtection = PinProtection.NOT_SAVED,
                        message = null,
                        screenError = null,
                    )
                }
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                mutableState.update { it.copy(rootStage = null) }
                throw failure
            }
        }
    }

    /** Brings MAX to the foreground and asks the user, via a system toast, to open and unlock Digital ID. */
    private fun switchToMaxDigitalId() {
        val launch = context.packageManager.getLaunchIntentForPackage(RootImportPackages.MAX)
        if (launch != null) {
            runCatching { context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }
        showToast(R.string.root_import_switch_toast)
    }

    /** Stage callbacks can arrive off the main thread; Toast requires a Looper thread. */
    private fun showToast(@StringRes textRes: Int) {
        Handler(Looper.getMainLooper()).post {
            runCatching { Toast.makeText(context, textRes, Toast.LENGTH_LONG).show() }
        }
    }

    /** Only reached after [TokenProfileLock] confirms the local PIN or biometric gate. */
    private fun openTokenQr() {
        if (!state.value.tokenProfile || state.value.account == null) return
        startQr(source = PinSource.Manual { CharArray(0) }, saveAfterVerification = false)
    }

    private fun continueImport() {
        val current = state.value
        if (current.busy) return
        if (current.importForm.link.isBlank() || current.importForm.password.isEmpty()) {
            mutableState.update { it.copy(message = UiText(R.string.notice_import_required)) }
            return
        }
        runOperation(ErrorOperation.IMPORT, ErrorRecovery.IMPORT) {
            imports.prepare(current.importForm)
            mutableState.update {
                it.copy(
                    destination = PocketDestination.SETUP,
                    importForm = ImportForm(),
                    setupPinProtection = PinProtection.NOT_SAVED,
                    message = null,
                    screenError = null,
                )
            }
        }
    }

    private fun commitImport() {
        val current = state.value
        val imported = imports.prepared ?: return
        runOperation(ErrorOperation.IMPORT, ErrorRecovery.IMPORT, durable = true) {
            replacementSettings = null
            finishCommit(imported, current.importMode, current.pinProtection, imported.did.tokenProfile)
        }
    }

    /**
     * Shared tail of both commit paths: a manual save from [SetupScreen][ru.lavafrai.openid.ui.setup.SetupScreen]
     * and the token-profile fast path that skips it (see [continueRootImport]). Must run inside
     * an already-started [runOperation] block.
     */
    private suspend fun finishCommit(
        imported: ImportedAccount,
        mode: ImportMode,
        pinProtection: PinProtection,
        tokenProfile: Boolean,
    ) {
        qrSession.stopAndWait()
        // Write-ahead: record the intended preferences so recoverPendingImport() can
        // finalize them if the process dies after the account write but before
        // finalizeImport(). The current preferences stay untouched until success.
        pinPreferences.prepareImport(imported.accountId, pinProtection, tokenProfile)
        val summary = imports.commit(imported, mode)
        pinPreferences.markImportCommitted()
        tokenLock.clear()
        pinPreferences.finalizeImport()
        currentCoroutineContext().ensureActive()
        attempts.reset()
        imports.clear()
        mutableState.update {
            it.copy(
                destination = PocketDestination.UNLOCK,
                account = summary,
                accountMigrationRequired = false,
                tokenProfile = tokenProfile,
                unlockPinProtection = pinProtection,
                tokenLockConfigured = false,
                tokenLockBiometricAvailable = false,
                importForm = ImportForm(),
                pin = "",
                message = null,
            )
        }
    }

    private fun submitPin(save: Boolean = true) {
        val current = state.value
        if (!canSubmit() || current.pin.length != 4) return
        if (current.tokenProfile) {
            submitTokenLockPin(current, save)
            return
        }
        var buffer: CharArray? = current.pin.toCharArray()
        mutableState.update { it.copy(pin = "") }
        startQr(
            source = PinSource.Manual {
                checkNotNull(buffer).also { buffer = null }
            },
            saveAfterVerification =
                save && (
                    current.replaceSavedPin ||
                        (current.savedPin as? SavedPinState.Saved)?.accountId != current.account?.accountId
                    ),
            dispose = {
                buffer?.fill('\u0000')
                buffer = null
            },
        )
    }

    /**
     * Checks (or, on first use, creates) the on-device lock for a token-profile account. Never
     * touches [ru.lavafrai.openid.data.repositories.pin.api.PinRepository] or the server: this PIN
     * only gates the local UI.
     */
    private fun submitTokenLockPin(
        current: PocketUiState,
        save: Boolean = true,
    ) {
        val accountId = current.account?.accountId ?: return
        val pin = current.pin.toCharArray()
        mutableState.update { it.copy(pin = "") }
        try {
            if (!tokenLock.isConfigured(accountId)) {
                val biometric = save &&
                    current.unlockPinProtection == PinProtection.BIOMETRIC &&
                    VaultAuthentication.unavailable(context, VaultProtection.BIOMETRIC) == null
                tokenLock.set(accountId, pin, biometric)
                attempts.reset()
                mutableState.update {
                    it.copy(tokenLockConfigured = true, tokenLockBiometricAvailable = biometric, message = null)
                }
                openTokenQr()
            } else if (tokenLock.verify(accountId, pin)) {
                attempts.reset()
                openTokenQr()
            } else {
                attempts.rejected()
                mutableState.update { it.copy(message = UiText(R.string.token_lock_invalid)) }
            }
        } finally {
            pin.fill('\u0000')
        }
    }

    private fun useSavedPin() {
        if (!canSubmit() || state.value.replaceSavedPin) return
        if (state.value.tokenProfile) {
            useTokenLockBiometric()
            return
        }
        val saved = state.value.savedPin as? SavedPinState.Saved ?: return
        if (saved.accountId != state.value.account?.accountId) return
        startQr(PinSource.Saved(authentication.authenticator(AuthKind.BIOMETRIC)), false)
    }

    /** Biometric success alone is the gate; the cipher's output is never used for anything. */
    private fun useTokenLockBiometric() {
        val accountId = state.value.account?.accountId ?: return
        if (!tokenLock.biometricEnabled(accountId)) return
        runOperation(ErrorOperation.UNLOCK, ErrorRecovery.UNLOCK) {
            val cipher = tokenLock.biometricCipher(accountId)
            if (cipher == null) {
                tokenLock.disableBiometric(accountId)
                mutableState.update { it.copy(tokenLockBiometricAvailable = false) }
                return@runOperation
            }
            authentication.authenticator(AuthKind.BIOMETRIC).authenticate(cipher)
            currentCoroutineContext().ensureActive()
            openTokenQr()
        }
    }

    private fun autoUnlock() {
        val current = state.value
        if (current.destination != PocketDestination.UNLOCK || !current.autoUnlockPending || current.busy) return
        // Consume once per visit, including cancellation, failure, manual entry and a running PIN cooldown.
        mutableState.update { it.copy(autoUnlockPending = false) }
        if (current.message != null || current.error != null || current.confirmDelete ||
            current.pin.isNotEmpty()
        ) {
            return
        }
        useSavedPin()
    }

    private fun refreshQr() {
        val current = state.value
        if (current.destination == PocketDestination.QR && !current.busy &&
            (current.qr != null || current.error?.recovery == ErrorRecovery.QR)
        ) {
            qrSession.refresh()
        }
    }

    private fun canSubmit(): Boolean =
        !state.value.busy && !state.value.accountMigrationRequired && state.value.account != null && attempts.canAttempt

    private fun startQr(
        source: PinSource,
        saveAfterVerification: Boolean,
        dispose: () -> Unit = {},
    ) {
        if (state.value.account == null || state.value.accountMigrationRequired) return
        mutableState.update { it.copy(message = null, screenError = null, autoUnlockPending = false) }
        qrSession.start(source, onPinVerified = { verified ->
            attempts.reset()
            if (saveAfterVerification && effectivePinProtection() != PinProtection.NOT_SAVED) {
                actions.savePin(verified, authentication.authenticator(AuthKind.BIOMETRIC))
                mutableState.update { it.copy(replaceSavedPin = false) }
            }
        }, dispose = dispose)
    }

    private fun onSessionChanged(session: QrSessionUiState) {
        mutableState.update {
            val completed = session.qr != null || session.error != null
            val destination = when {
                it.editingImport || it.destination == PocketDestination.SETTINGS -> it.destination
                session.qr != null || session.error?.recovery == ErrorRecovery.QR -> PocketDestination.QR
                session.error != null && it.destination != PocketDestination.QR -> PocketDestination.UNLOCK
                else -> it.destination
            }
            it.copy(
                session = session,
                destination = destination,
                replaceSavedPin = it.replaceSavedPin || session.requiresPinReplacement,
                screenError = if (completed && !it.editingImport) null else it.screenError,
                message = if (completed && !it.editingImport) null else it.message,
            )
        }
    }

    private fun selectPinProtection(protection: PinProtection) {
        if (state.value.busy) return
        mutableState.update {
            when (it.destination) {
                PocketDestination.SETUP -> it.copy(setupPinProtection = protection)

                PocketDestination.SETTINGS -> {
                    val updated = it.copy(settings = it.settings?.copy(selection = protection))
                    val error = updated.screenError
                    if (!updated.hasSettingsChanges && error?.recovery == ErrorRecovery.SETTINGS) {
                        updated.copy(screenError = error.copy(recovery = ErrorRecovery.UNLOCK))
                    } else {
                        updated
                    }
                }

                else -> it
            }
        }
    }

    private fun openSettings() {
        if (state.value.busy || state.value.destination != PocketDestination.QR) return
        val protection = effectivePinProtection()
        // Settings is inside the active QR session. Back resumes displaying it without another login.
        mutableState.update {
            it.copy(
                destination = PocketDestination.SETTINGS,
                settings = PinSettingsState(protection, protection),
                message = null,
            )
        }
    }

    private fun applySettings() {
        val current = state.value
        if (!current.hasSettingsChanges) return
        val account = current.account ?: return
        runOperation(ErrorOperation.SETTINGS, ErrorRecovery.SETTINGS) {
            qrSession.stopAndWait()
            val saved = (current.savedPin as? SavedPinState.Saved)?.takeIf { it.accountId == account.accountId }
            if (current.pinProtection == PinProtection.NOT_SAVED) {
                actions.deleteSavedPin(account.accountId)
            }
            currentCoroutineContext().ensureActive()
            pinPreferences.save(account.accountId, current.pinProtection)
            if (current.tokenProfile && tokenLock.isConfigured(account.accountId)) {
                val wantBiometric = current.pinProtection == PinProtection.BIOMETRIC &&
                    VaultAuthentication.unavailable(context, VaultProtection.BIOMETRIC) == null
                if (wantBiometric && !tokenLock.biometricEnabled(account.accountId)) {
                    tokenLock.enableBiometric(account.accountId)
                } else if (!wantBiometric && tokenLock.biometricEnabled(account.accountId)) {
                    tokenLock.disableBiometric(account.accountId)
                }
            }
            val biometricAvailable = current.tokenProfile &&
                tokenLock.isConfigured(account.accountId) &&
                tokenLock.biometricEnabled(account.accountId) &&
                VaultAuthentication.unavailable(context, VaultProtection.BIOMETRIC) == null
            mutableState.update {
                it.copy(
                    destination = PocketDestination.UNLOCK,
                    settings = null,
                    unlockPinProtection = current.pinProtection,
                    tokenLockBiometricAvailable = biometricAvailable,
                    savedPin = if (current.pinProtection == PinProtection.NOT_SAVED) {
                        SavedPinState.NotSaved
                    } else {
                        it.savedPin
                    },
                    pin = "",
                    replaceSavedPin = it.replaceSavedPin && current.pinProtection == PinProtection.BIOMETRIC,
                    autoUnlockPending = current.pinProtection == PinProtection.BIOMETRIC &&
                        saved != null && !it.replaceSavedPin,
                    notice = PocketNotice(
                        when {
                            current.pinProtection == PinProtection.NOT_SAVED ->
                                UiText(R.string.notice_manual_enabled)

                            saved == null || it.replaceSavedPin ->
                                UiText(R.string.notice_pin_save_required)

                            else -> UiText(R.string.notice_biometric_enabled)
                        },
                    ),
                )
            }
        }
    }

    private fun beginImport(mode: ImportMode) {
        val current = state.value
        replacementSettings = if (mode == ImportMode.REPLACE && current.destination == PocketDestination.SETTINGS) {
            ReplacementSettings(checkNotNull(current.settings), current.replaceSavedPin)
        } else {
            null
        }
        val preserveSession = replacementSettings != null
        if (!preserveSession) qrSession.stop()
        imports.clear()
        rootCheckJob?.cancel()
        mutableState.update {
            it.copy(
                destination = PocketDestination.METHOD,
                settings = null,
                importMode = mode,
                importMethod = null,
                rootSource = null,
                rootCheck = RootCheckState.IDLE,
                rootStage = null,
                maxInstalled = true,
                suManagers = emptyList(),
                tokenProfile = false,
                tokenLockConfigured = false,
                tokenLockBiometricAvailable = false,
                notice = null,
                replaceSavedPin = false,
                importForm = ImportForm(),
                pin = "",
                screenBusy = false,
                message = null, screenError = null,
                confirmDelete = false,
            )
        }
    }

    private fun askDelete() {
        if (state.value.busy || operations.active) return
        mutableState.update {
            it.copy(confirmDelete = true, pin = "", autoUnlockPending = false)
        }
    }

    private fun deleteAccount() {
        if (!state.value.confirmDelete) return
        mutableState.update { it.copy(confirmDelete = false) }
        runOperation(ErrorOperation.DELETE, ErrorRecovery.DELETE) {
            qrSession.stopAndWait()
            actions.deleteAccount()
            currentCoroutineContext().ensureActive()
            pinPreferences.clear()
            tokenLock.clear()
            attempts.reset()
            beginImport(ImportMode.NEW)
        }
    }

    private fun back() {
        if (state.value.busy) return
        when (state.value.destination) {
            PocketDestination.SETUP -> if (state.value.importMethod == ImportMethod.AUTO) {
                mutableState.update {
                    it.copy(
                        destination = PocketDestination.ROOT_SOURCE,
                        message = null,
                        screenError = null,
                    )
                }
            } else {
                val form = imports.restoreForm()
                imports.clear()
                mutableState.update {
                    it.copy(
                        destination = PocketDestination.IMPORT,
                        importForm = form,
                        message = null,
                        screenError = null,
                    )
                }
            }

            PocketDestination.ROOT_SOURCE -> {
                imports.clear()
                mutableState.update {
                    it.copy(
                        destination = PocketDestination.METHOD,
                        rootSource = null,
                        rootStage = null,
                        message = null,
                        screenError = null,
                    )
                }
            }

            PocketDestination.IMPORT -> {
                val form = imports.restoreForm()
                imports.clear()
                mutableState.update {
                    it.copy(
                        destination = PocketDestination.METHOD,
                        importMethod = null,
                        importForm = form,
                        message = null,
                        screenError = null,
                    )
                }
            }

            PocketDestination.METHOD -> if (state.value.importMode == ImportMode.REPLACE) {
                val settings = replacementSettings
                replacementSettings = null
                imports.clear()
                val accountId = state.value.account?.accountId
                mutableState.update {
                    it.copy(
                        destination = if (settings != null) PocketDestination.SETTINGS else PocketDestination.UNLOCK,
                        importMode = ImportMode.NEW,
                        importMethod = null,
                        importForm = ImportForm(),
                        unlockPinProtection = effectivePinProtection(),
                        tokenProfile = accountId != null && pinPreferences.isTokenProfile(accountId),
                        tokenLockConfigured = accountId != null && tokenLock.isConfigured(accountId),
                        tokenLockBiometricAvailable = accountId != null &&
                            tokenLock.biometricEnabled(accountId) &&
                            VaultAuthentication.unavailable(context, VaultProtection.BIOMETRIC) == null,
                        settings = settings?.pin,
                        replaceSavedPin = settings?.replaceSavedPin == true || it.replaceSavedPin,
                        message = null,
                        screenError = null,
                        autoUnlockPending = settings == null,
                    )
                }
            }

            PocketDestination.QR -> endSession()

            PocketDestination.SETTINGS -> mutableState.update {
                it.copy(
                    destination = PocketDestination.QR,
                    unlockPinProtection = effectivePinProtection(),
                    settings = null,
                    message = null,
                    screenError = it.screenError?.let { error ->
                        if (error.recovery in setOf(ErrorRecovery.SETTINGS, ErrorRecovery.DELETE)) {
                            error.copy(recovery = ErrorRecovery.UNLOCK)
                        } else {
                            error
                        }
                    },
                    autoUnlockPending = false,
                )
            }

            else -> Unit
        }
    }

    private fun endSession() {
        if (state.value.destination != PocketDestination.QR || state.value.busy) return
        qrSession.stop()
        mutableState.update {
            it.copy(
                destination = PocketDestination.UNLOCK,
                pin = "",
                message = null,
                notice = null,
                screenError = null,
                autoUnlockPending = true,
            )
        }
    }

    fun onForeground() {
        foreground = true
        imports.onForeground()
    }

    private fun expireImport() {
        imports.clear()
        val accountId = state.value.account?.accountId
        mutableState.update {
            it.copy(
                destination = if (it.account == null) PocketDestination.METHOD else PocketDestination.UNLOCK,
                importMode = ImportMode.NEW,
                importMethod = null,
                rootSource = null,
                rootCheck = RootCheckState.IDLE,
                rootStage = null,
                tokenProfile = accountId != null && pinPreferences.isTokenProfile(accountId),
                tokenLockConfigured = accountId != null && tokenLock.isConfigured(accountId),
                tokenLockBiometricAvailable = accountId != null &&
                    tokenLock.biometricEnabled(accountId) &&
                    VaultAuthentication.unavailable(context, VaultProtection.BIOMETRIC) == null,
                importForm = ImportForm(),
                screenError = null,
                message = null,
            )
        }
    }

    /** Close the session immediately; an unfinished import has a short, memory-only grace period. */
    fun onBackground() {
        val editingImport = state.value.editingImport
        foreground = false
        val committing = operations.committing
        operations.onBackground()
        authentication.cancel()
        qrSession.stop()
        replacementSettings = null
        if (!editingImport) {
            rootCheckJob?.cancel()
            imports.clear()
        }
        mutableState.update {
            it.copy(
                destination = when {
                    editingImport -> it.destination
                    it.destination == PocketDestination.START -> PocketDestination.START
                    it.account == null -> PocketDestination.METHOD
                    else -> PocketDestination.UNLOCK
                },
                importMode = if (editingImport) it.importMode else ImportMode.NEW,
                settings = null,
                notice = null,
                importForm = if (editingImport) it.importForm else ImportForm(),
                rootStage = null,
                pin = "",
                authPrompt = null,
                autoUnlockPending = true,
                confirmDelete = false,
                screenBusy = committing,
                message = null, screenError = null,
            )
        }
        if (editingImport && !committing) imports.onBackground()
    }

    fun dispose() {
        disposed = true
        onBackground()
        rootCheckJob?.cancel()
        imports.clear()
        mutableState.update { it.copy(importForm = ImportForm()) }
        observer?.cancel()
        attempts.dispose()
    }

    private fun runOperation(
        errorOperation: ErrorOperation,
        recovery: ErrorRecovery,
        durable: Boolean = false,
        block: suspend () -> Unit,
    ) {
        if (!state.value.busy) operations.run(errorOperation, recovery, durable, block)
    }

    private fun migrateAccountStorage() {
        if (!state.value.accountMigrationRequired) return
        runOperation(ErrorOperation.MIGRATION, ErrorRecovery.MIGRATION) {
            actions.migrateAccountStorage(authentication.authenticator(AuthKind.LEGACY_PROFILE_MIGRATION))
            currentCoroutineContext().ensureActive()
            mutableState.update {
                it.copy(
                    accountMigrationRequired = false,
                    message = UiText(R.string.notice_migration_complete),
                )
            }
        }
    }

    private fun effectivePinProtection(): PinProtection = state.value.account?.accountId?.let(pinPreferences::get)
        ?: if ((state.value.savedPin as? SavedPinState.Saved)?.accountId == state.value.account?.accountId &&
            state.value.account != null
        ) {
            PinProtection.BIOMETRIC
        } else {
            PinProtection.NOT_SAVED
        }

    private fun recoverError(error: PocketError) {
        if (state.value.error !== error || state.value.busy) return
        when (error.recovery) {
            ErrorRecovery.STARTUP -> observeStorage()

            ErrorRecovery.EDIT_IMPORT -> if (state.value.destination == PocketDestination.IMPORT) {
                mutableState.update { it.copy(screenError = null) }
            } else {
                back()
            }

            ErrorRecovery.IMPORT -> if (state.value.destination ==
                PocketDestination.IMPORT
            ) {
                continueImport()
            } else {
                commitImport()
            }

            ErrorRecovery.ROOT_IMPORT -> continueRootImport()

            ErrorRecovery.BIOMETRIC -> useSavedPin()

            ErrorRecovery.QR -> {
                if (state.value.destination == PocketDestination.SETTINGS) {
                    mutableState.update { it.copy(destination = PocketDestination.QR) }
                }
                refreshQr()
            }

            ErrorRecovery.SETTINGS -> applySettings()

            ErrorRecovery.DELETE -> askDelete()

            ErrorRecovery.MIGRATION -> migrateAccountStorage()

            ErrorRecovery.REPLACE -> beginImport(ImportMode.REPLACE)

            ErrorRecovery.UNLOCK -> {
                qrSession.stop()
                mutableState.update {
                    it.copy(
                        destination = PocketDestination.UNLOCK,
                        screenError = null,
                        message = null,
                        autoUnlockPending = true,
                    )
                }
            }

            null -> Unit
        }
    }

    private class ReplacementSettings(
        val pin: PinSettingsState,
        val replaceSavedPin: Boolean,
    )
}
