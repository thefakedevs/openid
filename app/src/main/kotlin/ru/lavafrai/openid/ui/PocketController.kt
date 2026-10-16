package ru.lavafrai.openid.ui

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
import ru.lavafrai.openid.models.account.AccountState
import ru.lavafrai.openid.models.auth.AuthenticationException
import ru.lavafrai.openid.models.pin.PinProtection
import ru.lavafrai.openid.models.pin.PinSource
import ru.lavafrai.openid.models.pin.SavedPinState
import ru.lavafrai.openid.navigation.ImportMode
import ru.lavafrai.openid.navigation.PocketDestination

/** App-level UI state, kept by a ViewModel. Secrets are not written to SavedStateHandle or route arguments. */
class PocketController(
    private val actions: PocketActions,
    private val scope: CoroutineScope,
    private val pinPreferences: PinProtectionPreferences,
    nowMillis: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    private val mutableState = MutableStateFlow(PocketUiState())
    val state: StateFlow<PocketUiState> = mutableState.asStateFlow()
    private var replacementSettings: ReplacementSettings? = null
    private var observer: Job? = null
    private var foreground = true
    private var disposed = false
    private val authentication = AuthenticationCoordinator { prompt ->
        mutableState.update { it.copy(authPrompt = prompt) }
    }
    private val attempts = PinAttemptLimiter(scope, nowMillis) { seconds ->
        mutableState.update { it.copy(retrySeconds = seconds) }
    }
    private val imports = ImportFlow(actions.importAccount, actions.replaceAccount, scope, nowMillis, ::expireImport)
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
                mutableState.update { it.copy(message = "Подтверждение отменено. Можно повторить попытку.") }
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
                            mutableState.update {
                                it.copy(
                                    destination = if (summary == null) {
                                        PocketDestination.IMPORT
                                    } else {
                                        PocketDestination.UNLOCK
                                    },
                                    unlockPinProtection = effectivePinProtection(),
                                    autoUnlockPending = true,
                                )
                            }
                        } else if (summary == null &&
                            state.value.destination !in setOf(PocketDestination.IMPORT, PocketDestination.SETUP)
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

    private fun continueImport() {
        val current = state.value
        if (current.busy) return
        if (current.importForm.link.isBlank() || current.importForm.password.isEmpty()) {
            mutableState.update { it.copy(message = "Введите ссылку импорта и пароль ссылки.") }
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
            // Only the explicit save ends the previous session and permits replacing storage.
            replacementSettings = null
            qrSession.stopAndWait()
            val summary = imports.commit(imported, current.importMode)
            currentCoroutineContext().ensureActive()
            pinPreferences.save(summary.accountId, current.pinProtection)
            attempts.reset()
            imports.clear()
            mutableState.update {
                it.copy(
                    destination = PocketDestination.UNLOCK,
                    account = summary,
                    accountMigrationRequired = false,
                    unlockPinProtection = current.pinProtection,
                    importForm = ImportForm(),
                    pin = "",
                    message = null,
                )
            }
        }
    }

    private fun submitPin(save: Boolean = true) {
        val current = state.value
        if (!canSubmit() || current.pin.length != 4) return
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

    private fun useSavedPin() {
        if (!canSubmit() || state.value.replaceSavedPin) return
        val saved = state.value.savedPin as? SavedPinState.Saved ?: return
        if (saved.accountId != state.value.account?.accountId) return
        startQr(PinSource.Saved(authentication.authenticator(AuthKind.BIOMETRIC)), false)
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
            mutableState.update {
                it.copy(
                    destination = PocketDestination.UNLOCK,
                    settings = null,
                    unlockPinProtection = current.pinProtection,
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
                                "Включён вход по PIN. Сохранённый PIN удалён."

                            saved == null || it.replaceSavedPin ->
                                "Введите PIN — после проверки сохраним его с биометрией."

                            else -> "Включён вход по биометрии."
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
        mutableState.update {
            it.copy(
                destination = PocketDestination.IMPORT,
                settings = null,
                importMode = mode,
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
            attempts.reset()
            beginImport(ImportMode.NEW)
        }
    }

    private fun back() {
        if (state.value.busy) return
        when (state.value.destination) {
            PocketDestination.SETUP -> {
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

            PocketDestination.IMPORT -> if (state.value.importMode == ImportMode.REPLACE) {
                val settings = replacementSettings
                replacementSettings = null
                imports.clear()
                mutableState.update {
                    it.copy(
                        destination = if (settings != null) PocketDestination.SETTINGS else PocketDestination.UNLOCK,
                        importMode = ImportMode.NEW,
                        importForm = ImportForm(),
                        unlockPinProtection = effectivePinProtection(),
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
        mutableState.update {
            it.copy(
                destination = if (it.account == null) PocketDestination.IMPORT else PocketDestination.UNLOCK,
                importMode = ImportMode.NEW,
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
        if (!editingImport) imports.clear()
        mutableState.update {
            it.copy(
                destination = when {
                    editingImport -> it.destination
                    it.destination == PocketDestination.START -> PocketDestination.START
                    it.account == null -> PocketDestination.IMPORT
                    else -> PocketDestination.UNLOCK
                },
                importMode = if (editingImport) it.importMode else ImportMode.NEW,
                settings = null,
                notice = null,
                importForm = if (editingImport) it.importForm else ImportForm(),
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
                    message = "Профиль обновлён. Теперь подтверждение требуется только для сохранённого PIN.",
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
