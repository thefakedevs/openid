package ru.lavafrai.openid.ui

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import ru.lavafrai.openid.models.auth.AuthenticationException
import ru.lavafrai.openid.models.auth.CryptoAuthenticator

internal class AuthenticationCoordinator(private val onPrompt: (AuthRequest?) -> Unit) {
    private var prompt: AuthRequest? = null
    private var pending: CompletableDeferred<Unit>? = null

    fun confirm(request: AuthRequest) {
        if (prompt === request) pending?.complete(Unit)
    }

    fun fail(
        request: AuthRequest,
        failure: AuthenticationException,
    ) {
        if (prompt === request) pending?.completeExceptionally(failure)
    }

    fun cancel() {
        pending?.cancel()
    }

    fun authenticator(kind: AuthKind) = CryptoAuthenticator { cipher ->
        check(pending == null) { "Authentication is already pending" }
        val completion = CompletableDeferred<Unit>()
        pending = completion
        prompt = AuthRequest(kind, cipher).also(onPrompt)
        try {
            completion.await()
            currentCoroutineContext().ensureActive()
        } finally {
            if (pending === completion) {
                pending = null
                prompt = null
                onPrompt(null)
            }
        }
    }
}
