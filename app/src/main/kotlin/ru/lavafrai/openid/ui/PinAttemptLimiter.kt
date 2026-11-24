package ru.lavafrai.openid.ui

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import androidx.core.content.edit
import kotlin.math.ceil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal class PinAttemptLimiter(
    context: Context,
    private val scope: CoroutineScope,
    private val nowMillis: () -> Long = SystemClock::elapsedRealtime,
    private val onRemaining: (Int) -> Unit,
) {
    private val prefs = context.getSharedPreferences("pin-attempt-limiter", Context.MODE_PRIVATE)
    private val bootCount = runCatching {
        Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1)
    }.getOrDefault(-1)
    private var attempts = prefs.getInt("attempts", 0).coerceIn(0, delays.lastIndex)
    private var retryAt = prefs.getLong("retryAtElapsed", 0L)
    private var retryDelaySeconds = prefs.getInt("retryDelaySeconds", delays[attempts])
        .coerceIn(delays.first(), delays.last())
    private var timer: Job? = null
    val canAttempt: Boolean get() = nowMillis() >= retryAt

    init {
        if (prefs.getLong("retryAt", 0L) > 0L ||
            (retryAt > 0L && (bootCount < 0 || prefs.getInt("bootCount", -1) != bootCount))
        ) {
            // Elapsed time has a different origin after reboot. Restore at most one penalty interval.
            retryAt = nowMillis() + retryDelaySeconds * 1000L
            persist()
        }
        if (retryAt > 0L) startTimer()
    }

    fun rejected() {
        retryDelaySeconds = delays[attempts]
        attempts = (attempts + 1).coerceAtMost(delays.lastIndex)
        retryAt = nowMillis() + retryDelaySeconds * 1000L
        persist()
        timer?.cancel()
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
        onRemaining(remainingSeconds())
        timer = scope.launch {
            while (true) {
                val remaining = remainingSeconds()
                onRemaining(remaining)
                if (remaining == 0) {
                    retryAt = 0
                    persist()
                    break
                }
                delay(200)
            }
        }
    }

    private fun remainingSeconds(): Int = ceil((retryAt - nowMillis()).coerceAtLeast(0) / 1000.0).toInt()

    private fun persist() {
        prefs.edit {
            putInt("attempts", attempts)
            putLong("retryAtElapsed", retryAt)
            putInt("bootCount", bootCount)
            remove("retryAt")
            if (retryAt == 0L) remove("retryDelaySeconds") else putInt("retryDelaySeconds", retryDelaySeconds)
        }
    }

    private companion object {
        val delays = listOf(5, 15, 30, 60)
    }
}
