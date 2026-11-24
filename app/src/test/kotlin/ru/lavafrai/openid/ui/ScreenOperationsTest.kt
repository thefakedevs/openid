package ru.lavafrai.openid.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

class ScreenOperationsTest {
    @Test
    fun `background-safe import can be cancelled and restarted`() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val finished = CompletableDeferred<Unit>()
        val restarted = CompletableDeferred<Unit>()
        val failures = mutableListOf<PocketError>()
        var finishedCount = 0
        val operations = ScreenOperations(
            scope = this,
            onStarted = {},
            onFailure = { failures += it },
            onFinished = {
                finishedCount++
                if (finishedCount == 1) finished.complete(Unit) else restarted.complete(Unit)
            },
        )

        operations.run(ErrorOperation.ROOT_IMPORT, ErrorRecovery.ROOT_IMPORT, survivesBackground = true) {
            started.complete(Unit)
            awaitCancellation()
        }
        withTimeout(2_000) { started.await() }
        operations.onBackground()
        assertTrue(operations.committing)

        operations.cancelActive()
        withTimeout(2_000) { finished.await() }
        assertFalse(operations.active)
        assertFalse(operations.committing)
        assertTrue(failures.isEmpty())

        operations.run(ErrorOperation.ROOT_IMPORT, ErrorRecovery.ROOT_IMPORT, survivesBackground = true) {}
        withTimeout(2_000) { restarted.await() }
        assertEquals(2, finishedCount)
        assertTrue(failures.isEmpty())
    }
}
