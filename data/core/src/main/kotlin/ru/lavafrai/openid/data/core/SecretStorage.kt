package ru.lavafrai.openid.data.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** One process-wide lock for initialization and both storage repositories. Never hold it during a UI prompt. */
class SecretStorage(
    val dao: SecretDao,
    val cipher: VaultCipher,
    val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    val mutex = Mutex()
    private var initialized = false
    private val retiredKeys = RetiredKeys(cipher::deleteKey)

    suspend fun awaitReady() = mutex.withLock {
        if (!initialized) {
            cipher.removeOrphans(dao.aliases().toSet())
            initialized = true
        }
    }

    /** Owns next's key even if validation, writing or cancellation fails. */
    suspend fun commit(
        next: SecretRecord,
        previous: SecretRecord?,
        validate: () -> Unit,
        publish: () -> Unit,
    ) {
        var committed = false
        try {
            currentCoroutineContext().ensureActive()
            withContext(NonCancellable + Dispatchers.IO) {
                mutex.withLock {
                    validate()
                    dao.write(next)
                    committed = true
                    publish()
                    previous?.let(retiredKeys::retire)
                }
            }
        } finally {
            if (!committed) {
                withContext(NonCancellable + Dispatchers.IO) {
                    mutex.withLock { retiredKeys.retire(next) }
                }
            }
        }
    }

    /** Keep the record as a retry handle until both its key and database row are removed. */
    suspend fun delete(
        slot: String,
        accountId: Long? = null,
        current: () -> SecretRecord?,
        revokeLeases: () -> Unit,
        publish: () -> Unit,
    ) {
        withContext(NonCancellable + Dispatchers.IO) {
            mutex.withLock {
                if (slot == "account") retiredKeys.retry() else retiredKeys.retry(slot, accountId)
                val record = current()
                if (record != null && accountId != null && record.accountId != accountId) return@withLock
                record?.let {
                    cipher.deleteKey(it.alias)
                    revokeLeases()
                }
                dao.delete(slot)
                publish()
            }
        }
    }
}
