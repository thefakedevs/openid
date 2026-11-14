package ru.lavafrai.openid.ui

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import ru.lavafrai.openid.domain.usecases.ImportAccountUseCase
import ru.lavafrai.openid.domain.usecases.ReplaceAccountUseCase
import ru.lavafrai.openid.models.account.AccountSummary
import ru.lavafrai.openid.models.account.ImportedAccount
import ru.lavafrai.openid.navigation.ImportMode

/** Owns the decrypted draft and its memory-only grace period; navigation stays with the controller. */
internal class ImportFlow(
    private val importAccount: ImportAccountUseCase,
    private val replaceAccount: ReplaceAccountUseCase,
    private val scope: CoroutineScope,
    private val nowMillis: () -> Long,
    private val onExpired: () -> Unit,
) {
    private var draft: Draft? = null
    private var expiry: Job? = null
    private var expiresAt: Long? = null
    val prepared: ImportedAccount? get() = draft?.account

    suspend fun prepare(form: ImportForm) {
        val password = form.password.toCharArray()
        try {
            val account = importAccount.prepare(form.link.trim(), password)
            currentCoroutineContext().ensureActive()
            clear()
            draft = Draft(form.link.trim(), password.copyOf(), account)
        } finally {
            password.fill('\u0000')
        }
    }

    /** Holds an account extracted from MAX via root; the same memory-only grace rules apply. */
    fun hold(account: ImportedAccount) {
        clear()
        draft = Draft("", CharArray(0), account)
    }

    suspend fun commit(
        account: ImportedAccount,
        mode: ImportMode,
    ): AccountSummary = if (mode == ImportMode.REPLACE) replaceAccount(account) else importAccount(account)

    fun restoreForm(): ImportForm = draft?.let { ImportForm(it.link, it.password.concatToString()) } ?: ImportForm()

    fun onForeground() {
        if (expiresAt?.let { nowMillis() >= it } == true) expire()
        expiry?.cancel()
        expiresAt = null
    }

    fun onBackground() {
        expiry?.cancel()
        expiresAt = nowMillis() + GRACE_PERIOD_MILLIS
        expiry = scope.launch {
            delay(GRACE_PERIOD_MILLIS)
            expire()
        }
    }

    fun clear() {
        expiry?.cancel()
        expiresAt = null
        draft?.password?.fill('\u0000')
        draft = null
    }

    private fun expire() {
        clear()
        onExpired()
    }

    private class Draft(
        val link: String,
        val password: CharArray,
        val account: ImportedAccount,
    )

    private companion object {
        const val GRACE_PERIOD_MILLIS = 120_000L
    }
}
