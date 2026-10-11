package ru.lavafrai.openid.data.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** One process-wide lock for initialization and both storage repositories. Never hold it during a UI prompt. */
class SecretStorage(
    val database: SecretDatabase,
    val cipher: VaultCipher,
) {
    val mutex = Mutex()
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var initialized = false

    suspend fun awaitReady() = mutex.withLock {
        if (!initialized) {
            cipher.removeOrphans(database.secrets().aliases().toSet())
            initialized = true
        }
    }
    val dao get() = database.secrets()
}
