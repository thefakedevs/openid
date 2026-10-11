package ru.lavafrai.openid.domain

import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Share one instance between all account/PIN use cases and QR collectors. */
class AccountOperationCoordinator {
    private val operations = Mutex()
    private val lifecycle = Mutex()
    private val generation = AtomicReference<Job?>(null)

    internal suspend fun <T> access(block: suspend () -> T): T = operations.withLock {
        currentCoroutineContext().ensureActive()
        block()
    }

    internal suspend fun <T> mutate(block: suspend () -> T): T {
        val caller = currentCoroutineContext().job
        check(generation.get()?.contains(caller) != true) {
            "Account mutations cannot run inside a QR callback"
        }
        return lifecycle.withLock {
            // A QR operation may hold access while waiting for biometrics. Cancel it before taking that lock.
            generation.get()?.cancelAndJoin()
            access(block)
        }
    }

    internal suspend fun <T> generate(block: suspend () -> T): T = coroutineScope {
        val job = currentCoroutineContext().job
        lifecycle.withLock {
            if (!generation.compareAndSet(null, job)) throw QrGenerationAlreadyRunningException()
        }
        try {
            block()
        } finally {
            generation.compareAndSet(job, null)
        }
    }

    private fun Job.contains(job: Job): Boolean = this === job || children.any { it.contains(job) }
}

class QrGenerationAlreadyRunningException : IllegalStateException("QR generation is already running")
