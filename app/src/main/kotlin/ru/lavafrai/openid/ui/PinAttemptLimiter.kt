package ru.lavafrai.openid.ui

import android.content.Context
import androidx.core.content.edit
import kotlin.math.ceil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal class PinAttemptLimiter(
    context: Context,
    private val scope: CoroutineScope,
    private val nowMillis: () -> Long,
    private val onRemaining: (Int) -> Unit,
) {
    private val prefs = context.getSharedPreferences("pin-attempt-limiter", Context.MODE_PRIVATE)
    private var attempts = prefs.getInt("attempts", 0)
    private var retryAt = prefs.getLong("retryAt", 0L)
    private var timer: Job? = null
    val canAttempt: Boolean get() = System.currentTimeMillis() >= retryAt

    init {
        val remaining = ((retryAt - System.currentTimeMillis()).coerceAtLeast(0) / 1000.0).let { ceil(it).toInt() }
        if (remaining > 0) {
            onRemaining(remaining)
            startTimer()
        }
    }

    fun rejected() {
        val seconds = listOf(5, 15, 30, 60)[attempts.coerceAtMost(3)]
        attempts = (attempts + 1).coerceAtMost(3)
        retryAt = System.currentTimeMillis() + seconds * 1000L
        persist()
        timer?.cancel()
        onRemaining(seconds)
        startTimer()
    }

    fun reset() {
        attempts = 0
        retryAt = 0
        persist()
        dispose()
        onRemaining(0)
    }

    fun dispose() {
        timer?.cancel()
    }

    private fun startTimer() {
        timer = scope.launch {
            while (true) {
                val remaining = ceil((retryAt - System.currentTimeMillis()).coerceAtLeast(0) / 1000.0).toInt()
                onRemaining(remaining)
                if (remaining == 0) break
                delay(200)
            }
        }
    }

    private fun persist() {
        prefs.edit {
            putInt("attempts", attempts)
            putLong("retryAt", retryAt)
        }
    }
}
