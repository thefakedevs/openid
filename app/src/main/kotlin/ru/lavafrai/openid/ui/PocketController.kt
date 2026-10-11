package ru.lavafrai.openid.ui

import kotlin.math.ceil
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import ru.lavafrai.openid.data.core.DataException
import ru.lavafrai.openid.domain.models.QrGenerationFailure
import ru.lavafrai.openid.domain.models.QrGenerationState
import ru.lavafrai.openid.domain.usecases.DigitalIdPreflightFailure
import ru.lavafrai.openid.models.account.AccountState
import ru.lavafrai.openid.models.auth.AuthenticationException
import ru.lavafrai.openid.models.auth.CryptoAuthenticator
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
    private val nowMillis: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    private val mutableState = MutableStateFlow(PocketUiState())
    val state: StateFlow<PocketUiState> = mutableState.asStateFlow()
    private var draft: ImportDraft? = null
    private var observer: Job? = null
    private var operation: Job? = null
    private var operationVersion = 0
    private var qrJob: Job? = null
    private var qrVersion = 0
    private var pendingAuth: CompletableDeferred<Unit>? = null
    private var timer: Job? = null
    private var failedAttempts = 0
    private var retryAtMillis = 0L

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

            is PocketAction.SelectPinProtection -> if (!state.value.busy) {
                mutableState.update {
                    it.copy(pinProtection = action.value)
                }
            }

            PocketAction.ContinueImport -> continueImport()

            PocketAction.CommitImport -> commitImport()

            PocketAction.SubmitPin -> submitPin()

            PocketAction.SubmitPinWithoutSaving -> submitPin(save = false)

            PocketAction.UseSavedPin -> useSavedPin()

            PocketAction.OpenSettings -> openSettings()

            PocketAction.ApplySettings -> applySettings()

            PocketAction.ReplaceAccount -> if (!state.value.busy) beginImport(ImportMode.REPLACE)

            PocketAction.AskDelete -> askDelete()

            PocketAction.ConfirmDelete -> deleteAccount()

            PocketAction.DismissDelete -> mutableState.update { it.copy(confirmDelete = false) }

            is PocketAction.ConfirmAuth -> if (state.value.authPrompt === action.request) pendingAuth?.complete(Unit)

            is PocketAction.CancelAuth -> if (state.value.authPrompt === action.request) {
                mutableState.update { it.copy(message = "Подтверждение отменено. Можно повторить попытку.") }
                pendingAuth?.cancel()
            }

            is PocketAction.AuthFailed -> if (state.value.authPrompt === action.request) {
                pendingAuth?.completeExceptionally(AuthenticationException(action.reason))
            }

            PocketAction.Back -> back()

            PocketAction.RetryStartup -> observeStorage()
        }
    }

    private fun observeStorage() {
        observer?.cancel()
        mutableState.update { it.copy(message = null) }
        observer = scope.launch {
            try {
                combine(actions.accountState(), actions.savedPinState()) { account, pin -> account to pin }
                    .collect { (account, pin) ->
                        if (account == AccountState.Unavailable || pin == SavedPinState.Unavailable) {
                            mutableState.update {
                                it.copy(
                                    destination = PocketDestination.START,
                                    message = "Не удалось открыть хранилище. Повторите попытку.",
                                    busy = false,
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
                                    pinProtection = effectivePinProtection(),
                                )
                            }
                        } else if (summary == null &&
                            state.value.destination !in setOf(PocketDestination.IMPORT, PocketDestination.SETUP)
                        ) {
                            beginImport(ImportMode.NEW)
                        }
                    }
            } catch (failure: Throwable) {
                if (failure is CancellationException) throw failure
                mutableState.update { it.copy(message = actionError(failure), busy = false) }
            }
        }
    }

    private fun editForm(change: (ImportForm) -> ImportForm) {
        if (!state.value.busy) mutableState.update { it.copy(importForm = change(it.importForm), message = null) }
    }

    private fun continueImport() {
        val current = state.value
        if (current.busy) return
        if (current.importForm.link.isBlank() || current.importForm.password.isEmpty()) {
            mutableState.update { it.copy(message = "Введите ссылку импорта и пароль ссылки.") }
            return
        }
        clearDraft()
        draft = ImportDraft(current.importForm.link.trim(), current.importForm.password.toCharArray())
        mutableState.update {
            it.copy(
                destination = PocketDestination.SETUP,
                importForm = ImportForm(),
                pinProtection = PinProtection.NOT_SAVED,
                message = null,
            )
        }
    }

    private fun commitImport() {
        val current = state.value
        val imported = draft ?: return
        runOperation {
            val password = imported.password.copyOf()
            try {
                val summary = if (current.importMode == ImportMode.REPLACE) {
                    actions.replaceAccount(imported.link, password)
                } else {
                    actions.importAccount(imported.link, password)
                }
                currentCoroutineContext().ensureActive()
                pinPreferences.save(summary.accountId, current.pinProtection)
                resetAttempts()
                clearDraft()
                mutableState.update {
                    it.copy(
                        destination = PocketDestination.UNLOCK,
                        account = summary,
                        accountMigrationRequired = false,
                        importForm = ImportForm(),
                        pin = "",
                        qr = null,
                        qrStage = null,
                        message = if (current.pinProtection == PinProtection.NOT_SAVED) {
                            null
                        } else {
                            "PIN сохранится после успешной авторизации."
                        },
                    )
                }
            } finally {
                password.fill('\u0000')
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
                save && (current.savedPin as? SavedPinState.Saved)?.accountId != current.account?.accountId,
            dispose = {
                buffer?.fill('\u0000')
                buffer = null
            },
        )
    }

    private fun useSavedPin() {
        if (!canSubmit()) return
        val saved = state.value.savedPin as? SavedPinState.Saved ?: return
        if (saved.accountId != state.value.account?.accountId) return
        startQr(PinSource.Saved(authenticator(AuthKind.BIOMETRIC)), false)
    }

    private fun canSubmit(): Boolean =
        !state.value.busy && !state.value.accountMigrationRequired && state.value.account != null &&
            nowMillis() >= retryAtMillis

    private fun startQr(
        source: PinSource,
        saveAfterVerification: Boolean,
        dispose: () -> Unit = {},
    ) {
        if (state.value.account == null || state.value.accountMigrationRequired) return
        val version = ++qrVersion
        val previous = qrJob
        mutableState.update { it.copy(busy = true, message = null, qr = null, qrStage = null) }
        qrJob = scope.launch {
            try {
                previous?.cancelAndJoin()
                actions.generateQr(
                    pinSource = source,
                    onPinVerified = { verified ->
                        resetAttempts()
                        val policy = effectivePinProtection()
                        if (saveAfterVerification && policy != PinProtection.NOT_SAVED) {
                            actions.savePin(verified, authenticator(AuthKind.BIOMETRIC))
                        }
                    },
                ).collect { result ->
                    if (version != qrVersion) return@collect
                    when (result) {
                        is QrGenerationState.Loading -> mutableState.update {
                            it.copy(qrStage = result.stage, qr = null)
                        }

                        is QrGenerationState.Ready -> mutableState.update {
                            it.copy(
                                destination = if (it.destination == PocketDestination.SETTINGS) {
                                    it.destination
                                } else {
                                    PocketDestination.QR
                                },
                                qr = result.qr,
                                qrStage = null,
                                busy = false,
                                message = null,
                            )
                        }

                        is QrGenerationState.Failed -> {
                            if (result.reason == QrGenerationFailure.AuthorizationRejected) startCooldown()
                            mutableState.update {
                                it.copy(
                                    destination = if (it.destination == PocketDestination.SETTINGS) {
                                        it.destination
                                    } else {
                                        PocketDestination.UNLOCK
                                    },
                                    qr = null,
                                    qrStage = null,
                                    busy = false,
                                    message = qrError(result.reason),
                                )
                            }
                        }
                    }
                }
            } catch (failure: Throwable) {
                if (failure is CancellationException) throw failure
                if (version == qrVersion) mutableState.update { it.copy(message = actionError(failure)) }
            } finally {
                dispose()
                if (version == qrVersion) mutableState.update { it.copy(busy = false, qr = null, qrStage = null) }
            }
        }
    }

    private fun openSettings() {
        if (state.value.busy || state.value.destination != PocketDestination.QR) return
        // Settings is inside the active QR session. Back resumes displaying it without another login.
        mutableState.update {
            it.copy(
                destination = PocketDestination.SETTINGS,
                pinProtection = effectivePinProtection(),
                message = null,
            )
        }
    }

    private fun applySettings() {
        val current = state.value
        val account = current.account ?: return
        runOperation {
            stopQrAndWait()
            val saved = (current.savedPin as? SavedPinState.Saved)?.takeIf { it.accountId == account.accountId }
            if (current.pinProtection == PinProtection.NOT_SAVED) {
                actions.deleteSavedPin(account.accountId)
            }
            currentCoroutineContext().ensureActive()
            pinPreferences.save(account.accountId, current.pinProtection)
            mutableState.update {
                it.copy(
                    message = if (saved == null && current.pinProtection != PinProtection.NOT_SAVED) {
                        "Настройки применены. Введите PIN, чтобы сохранить его."
                    } else {
                        "Настройки применены. Для QR снова подтвердите PIN."
                    },
                )
            }
        }
    }

    private fun beginImport(mode: ImportMode) {
        stopQr()
        clearDraft()
        mutableState.update {
            it.copy(
                destination = PocketDestination.IMPORT,
                importMode = mode,
                importForm = ImportForm(),
                pin = "",
                qr = null,
                qrStage = null,
                busy = false,
                message = null,
                confirmDelete = false,
            )
        }
    }

    private fun askDelete() {
        if (operation?.isCompleted == false) return
        stopQr()
        pendingAuth?.cancel()
        mutableState.update { it.copy(confirmDelete = true, pin = "", busy = false, qr = null, qrStage = null) }
    }

    private fun deleteAccount() {
        if (!state.value.confirmDelete) return
        mutableState.update { it.copy(confirmDelete = false) }
        runOperation {
            stopQrAndWait()
            actions.deleteAccount()
            currentCoroutineContext().ensureActive()
            pinPreferences.clear()
            resetAttempts()
            beginImport(ImportMode.NEW)
        }
    }

    private fun back() {
        if (state.value.busy) return
        when (state.value.destination) {
            PocketDestination.SETUP -> {
                val form = draft?.let { ImportForm(it.link, it.password.concatToString()) } ?: ImportForm()
                clearDraft()
                mutableState.update {
                    it.copy(destination = PocketDestination.IMPORT, importForm = form, message = null)
                }
            }

            PocketDestination.IMPORT -> if (state.value.importMode == ImportMode.REPLACE) {
                clearDraft()
                mutableState.update {
                    it.copy(destination = PocketDestination.UNLOCK, importForm = ImportForm(), message = null)
                }
            }

            PocketDestination.QR -> {
                stopQr()
                mutableState.update {
                    it.copy(destination = PocketDestination.UNLOCK, qr = null, qrStage = null, pin = "", message = null)
                }
            }

            PocketDestination.SETTINGS -> mutableState.update {
                it.copy(
                    destination = if (qrJob?.isActive == true) PocketDestination.QR else PocketDestination.UNLOCK,
                    message = null,
                )
            }

            else -> Unit
        }
    }

    /** Called on app backgrounding. Settings preserves the session only while the app stays foreground. */
    fun onBackground() {
        operationVersion++
        operation?.cancel()
        pendingAuth?.cancel()
        stopQr()
        clearDraft()
        mutableState.update {
            it.copy(
                destination = when {
                    it.destination == PocketDestination.START -> PocketDestination.START
                    it.account == null -> PocketDestination.IMPORT
                    else -> PocketDestination.UNLOCK
                },
                importMode = ImportMode.NEW,
                importForm = ImportForm(),
                pin = "",
                qr = null,
                qrStage = null,
                authPrompt = null,
                confirmDelete = false,
                busy = false,
                message = null,
            )
        }
    }

    fun dispose() {
        onBackground()
        observer?.cancel()
        timer?.cancel()
    }

    private fun runOperation(block: suspend () -> Unit) {
        if (state.value.busy || operation?.isCompleted == false) return
        val version = ++operationVersion
        mutableState.update { it.copy(busy = true, message = null) }
        operation = scope.launch {
            try {
                block()
            } catch (failure: Throwable) {
                if (failure is CancellationException) throw failure
                if (version == operationVersion) mutableState.update { it.copy(message = actionError(failure)) }
            } finally {
                if (version == operationVersion) mutableState.update { it.copy(busy = false) }
            }
        }
    }

    private fun migrateAccountStorage() {
        if (!state.value.accountMigrationRequired) return
        runOperation {
            actions.migrateAccountStorage(authenticator(AuthKind.LEGACY_PROFILE_MIGRATION))
            currentCoroutineContext().ensureActive()
            mutableState.update {
                it.copy(
                    accountMigrationRequired = false,
                    message = "Профиль обновлён. Теперь подтверждение требуется только для сохранённого PIN.",
                )
            }
        }
    }

    private fun authenticator(kind: AuthKind) = CryptoAuthenticator { cipher ->
        check(pendingAuth == null) { "Authentication is already pending" }
        val request = CompletableDeferred<Unit>()
        pendingAuth = request
        mutableState.update { it.copy(authPrompt = AuthRequest(kind, cipher)) }
        try {
            request.await()
            currentCoroutineContext().ensureActive()
        } finally {
            if (pendingAuth === request) {
                pendingAuth = null
                mutableState.update { it.copy(authPrompt = null) }
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

    private fun startCooldown() {
        val seconds = listOf(5, 15, 30, 60)[failedAttempts.coerceAtMost(3)]
        failedAttempts++
        retryAtMillis = nowMillis() + seconds * 1000L
        timer?.cancel()
        mutableState.update { it.copy(retrySeconds = seconds) }
        timer = scope.launch {
            while (true) {
                val remaining = ceil((retryAtMillis - nowMillis()).coerceAtLeast(0) / 1000.0).toInt()
                mutableState.update { it.copy(retrySeconds = remaining) }
                if (remaining == 0) break
                delay(200)
            }
        }
    }

    private fun resetAttempts() {
        failedAttempts = 0
        retryAtMillis = 0
        timer?.cancel()
        mutableState.update { it.copy(retrySeconds = 0) }
    }

    private fun stopQr() {
        qrVersion++
        qrJob?.cancel()
    }

    private suspend fun stopQrAndWait() {
        stopQr()
        qrJob?.join()
        qrJob = null
        mutableState.update { it.copy(qr = null, qrStage = null) }
    }

    private fun clearDraft() {
        draft?.password?.fill('\u0000')
        draft = null
    }

    private fun actionError(failure: Throwable): String = when (failure) {
        is AuthenticationException -> failure.reason.message()

        is DataException -> when (failure.code) {
            "WRONG_PASSPHRASE_OR_DAMAGED_LINK" -> "Неверный пароль ссылки или ссылка повреждена."
            "PASSPHRASE_MUST_BE_ASCII_12_TO_256" -> "Пароль ссылки: от 12 до 256 печатных ASCII-символов."
            "STORAGE_KEY_MISSING" -> "Ключ хранилища недоступен. Удалите секреты и импортируйте профиль заново."
            else -> "Операция не выполнена (${failure.code}). Проверьте данные и повторите попытку."
        }

        else -> "Операция не выполнена. Проверьте данные и попробуйте снова."
    }

    private fun qrError(failure: QrGenerationFailure): String = when (failure) {
        is QrGenerationFailure.Authentication -> failure.reason.message()

        QrGenerationFailure.AuthorizationRejected -> "PIN не принят. Подождите перед следующей попыткой."

        QrGenerationFailure.InvalidPin -> "PIN должен содержать четыре цифры."

        QrGenerationFailure.AlreadyRunning -> "Получение QR уже запущено."

        QrGenerationFailure.OperationFailed -> "Не удалось получить QR. Повторите попытку."

        is QrGenerationFailure.Preflight -> when (failure.reason) {
            DigitalIdPreflightFailure.ATTEMPTS_EXHAUSTED -> "Сервер не разрешает новые попытки PIN."

            DigitalIdPreflightFailure.COOLDOWN -> "Сервер просит подождать. Попробуйте позже."

            DigitalIdPreflightFailure.DEVICE_MISMATCH,
            DigitalIdPreflightFailure.TIMESTAMP_MISMATCH,
            ->
                "Данные Digital ID изменились. Импортируйте аккаунт заново."

            DigitalIdPreflightFailure.PIN_SALT_MISSING -> "В импорте нет данных для авторизации по PIN."

            DigitalIdPreflightFailure.UNSUPPORTED_PROFILE -> "Профиль Digital ID недоступен."
        }
    }

    private class ImportDraft(
        val link: String,
        val password: CharArray,
    )
}
