package ru.lavafrai.openid.ui

import kotlin.math.ceil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal class PinAttemptLimiter(
    private val scope: CoroutineScope,
    private val nowMillis: () -> Long,
    private val onRemaining: (Int) -> Unit,
) {
    private var attempts = 0
    private var retryAt = 0L
    private var timer: Job? = null
    val canAttempt: Boolean get() = nowMillis() >= retryAt

    fun rejected() {
        val seconds = listOf(5, 15, 30, 60)[attempts.coerceAtMost(3)]
        attempts = (attempts + 1).coerceAtMost(3)
        retryAt = nowMillis() + seconds * 1000L
        timer?.cancel()
        onRemaining(seconds)
        timer = scope.launch {
            while (true) {
                val remaining = ceil((retryAt - nowMillis()).coerceAtLeast(0) / 1000.0).toInt()
                onRemaining(remaining)
                if (remaining == 0) break
                delay(200)
            }
        }
    }

    fun reset() {
        attempts = 0
        retryAt = 0
        dispose()
        onRemaining(0)
    }

    fun dispose() {
        timer?.cancel()
    }
}
