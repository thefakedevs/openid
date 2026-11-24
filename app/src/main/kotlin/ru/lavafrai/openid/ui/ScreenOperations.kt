package ru.lavafrai.openid.ui

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Serial screen actions. An operation that survives background keeps running (and keeps its busy
 * state) when the app is backgrounded, e.g. while the user is in MAX. It stays cancellable by the
 * owner's scope: only the short storage commit inside a block may opt out with
 * [kotlinx.coroutines.NonCancellable], so a destroyed owner never keeps extracting secrets.
 */
internal class ScreenOperations(
    private val scope: CoroutineScope,
    private val onStarted: () -> Unit,
    private val onFailure: (PocketError) -> Unit,
    private val onFinished: () -> Unit,
) {
    private var job: Job? = null
    private var version = 0
    private var survivesBackground = false
    val active: Boolean get() = job?.isCompleted == false
    val committing: Boolean get() = survivesBackground && active

    fun run(
        operation: ErrorOperation,
        recovery: ErrorRecovery?,
        survivesBackground: Boolean = false,
        block: suspend () -> Unit,
    ) {
        if (active) return
        val generation = ++version
        this.survivesBackground = survivesBackground
        onStarted()
        job = scope.launch {
            try {
                block()
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                if (generation == version) onFailure(failure.toPocketError(operation, recovery))
            } finally {
                if (generation == version) {
                    this@ScreenOperations.survivesBackground = false
                    onFinished()
                }
            }
        }
    }

    /** The owner is going away: cancel unconditionally. A commit inside NonCancellable still finishes. */
    fun cancel() {
        version++
        job?.cancel()
    }

    fun onBackground() {
        if (!committing) {
            version++
            job?.cancel()
        }
    }
}
