package ru.lavafrai.openid.ui

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Serial screen actions. A durable import finishes reconciling UI policy after storage commits. */
internal class ScreenOperations(
    private val scope: CoroutineScope,
    private val onStarted: () -> Unit,
    private val onFailure: (PocketError) -> Unit,
    private val onFinished: () -> Unit,
) {
    private var job: Job? = null
    private var version = 0
    private var durable = false
    val active: Boolean get() = job?.isCompleted == false
    val committing: Boolean get() = durable && active

    fun run(
        operation: ErrorOperation,
        recovery: ErrorRecovery,
        durable: Boolean = false,
        block: suspend () -> Unit,
    ) {
        if (active) return
        val generation = ++version
        this.durable = durable
        onStarted()
        job = scope.launch {
            try {
                if (durable) withContext(NonCancellable) { block() } else block()
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                if (generation == version) onFailure(failure.toPocketError(operation, recovery))
            } finally {
                if (generation == version) {
                    this@ScreenOperations.durable = false
                    onFinished()
                }
            }
        }
    }

    fun onBackground() {
        if (!committing) {
            version++
            job?.cancel()
        }
    }
}
