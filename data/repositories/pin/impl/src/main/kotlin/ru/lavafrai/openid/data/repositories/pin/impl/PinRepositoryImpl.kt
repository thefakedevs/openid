package ru.lavafrai.openid.data.repositories.pin.impl

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import ru.lavafrai.openid.data.core.DataException
import ru.lavafrai.openid.data.core.SecretRecord
import ru.lavafrai.openid.data.core.SecretStorage
import ru.lavafrai.openid.data.core.VaultProtection
import ru.lavafrai.openid.data.core.requireData
import ru.lavafrai.openid.data.repositories.pin.api.PinRepository
import ru.lavafrai.openid.models.auth.CryptoAuthenticator
import ru.lavafrai.openid.models.pin.PinLease
import ru.lavafrai.openid.models.pin.SavedPinState

class PinRepositoryImpl(private val storage: SecretStorage) : PinRepository {
    private val stateLock = Any()
    private val state = MutableStateFlow<SavedPinState>(SavedPinState.Loading)

    @Volatile
    private var record: SecretRecord? = null
    private var initializationJob: Job? = null
    private val leases = mutableSetOf<Lease>()
    override val pinState: StateFlow<SavedPinState> get() {
        if (state.value == SavedPinState.Unavailable) initialize()
        return state.asStateFlow()
    }

    init {
        initialize()
    }

    private fun initialize() = synchronized(stateLock) {
        if (initializationJob?.isActive == true) return@synchronized
        initializationJob = storage.scope.launch {
            try {
                storage.awaitReady()
                storage.mutex.withLock {
                    var row = storage.dao.read("pin")
                    row?.let {
                        requireData(
                            it.accountId > 0 && it.protection in setOf("DEVICE_KEY", "BIOMETRIC"),
                            "STORAGE_METADATA_INVALID",
                        )
                    }
                    // An old automatically accessible PIN must never be exposed by the new API.
                    if (row?.protection == "DEVICE_KEY") {
                        val legacy = row
                        withContext(NonCancellable) {
                            storage.dao.delete("pin")
                            storage.cipher.deleteKey(legacy.alias)
                        }
                        row = null
                    }
                    synchronized(stateLock) {
                        record = row
                        publishState()
                    }
                }
            } catch (failure: CancellationException) {
                throw failure
            } catch (_: Exception) {
                state.value = SavedPinState.Unavailable
            }
        }
    }

    private suspend fun awaitReady() {
        initializationJob?.join()
        requireData(state.value != SavedPinState.Unavailable, "STORAGE_UNAVAILABLE")
    }

    private fun publishState() {
        state.value = record?.let { SavedPinState.Saved(it.accountId) } ?: SavedPinState.NotSaved
    }

    override suspend fun save(
        accountId: Long,
        pin: CharArray,
        authenticator: CryptoAuthenticator,
    ) {
        requireData(accountId > 0, "ACCOUNT_ID_MISSING")
        requireData(pin.size == 4 && pin.all { it in '0'..'9' }, "PIN_INVALID")
        awaitReady()
        val old = synchronized(stateLock) { record }
        val raw = ByteArray(4) { pin[it].code.toByte() }
        var next: SecretRecord? = null
        var committed = false
        try {
            next = storage.cipher.create("pin", accountId, "BIOMETRIC", VaultProtection.BIOMETRIC, raw, authenticator)
            currentCoroutineContext().ensureActive()
            withContext(NonCancellable + Dispatchers.IO) {
                storage.mutex.withLock {
                    requireData(record?.alias == old?.alias, "PIN_CHANGED")
                    storage.dao.write(next)
                    synchronized(stateLock) {
                        leases.toList().forEach { it.close() }
                        record = next
                        publishState()
                    }
                    committed = true
                    old?.let { storage.cipher.deleteKey(it.alias) }
                }
            }
        } finally {
            raw.fill(0)
            if (!committed) {
                next?.let {
                    withContext(NonCancellable + Dispatchers.IO) { storage.cipher.deleteKey(it.alias) }
                }
            }
        }
    }

    override suspend fun unlock(
        accountId: Long,
        authenticator: CryptoAuthenticator,
    ): PinLease {
        awaitReady()
        val row = synchronized(stateLock) { record ?: throw DataException("PIN_NOT_SAVED") }
        requireData(row.accountId == accountId, "PIN_ACCOUNT_MISMATCH")
        requireData(row.protection == "BIOMETRIC", "STORAGE_PROTECTION_INVALID")
        val secret = storage.cipher.open(row, authenticator)
        try {
            requireData(secret.raw.size == 4 && secret.raw.all { it.toInt() in 48..57 }, "PIN_INVALID")
            currentCoroutineContext().ensureActive()
            return synchronized(stateLock) {
                requireData(record?.alias == row.alias, "PIN_CHANGED")
                Lease(accountId, row.alias, CharArray(4) { secret.raw[it].toInt().toChar() }).also { leases += it }
            }
        } finally {
            secret.close()
        }
    }

    override suspend fun delete(accountId: Long) {
        awaitReady()
        withContext(NonCancellable + Dispatchers.IO) {
            storage.mutex.withLock {
                val old = record ?: return@withLock
                if (old.accountId != accountId) return@withLock
                storage.dao.delete("pin")
                synchronized(stateLock) {
                    leases.toList().forEach { it.close() }
                    record = null
                    publishState()
                }
                storage.cipher.deleteKey(old.alias)
            }
        }
    }

    private inner class Lease(
        override val accountId: Long,
        val generation: String,
        private val pin: CharArray,
    ) : PinLease {
        private var closed = false

        fun ensureValid() {
            requireData(!closed && record?.alias == generation, "PIN_LEASE_REVOKED")
        }

        override fun copyPin(): CharArray = synchronized(stateLock) {
            ensureValid()
            pin.copyOf()
        }

        override fun close() = synchronized(stateLock) {
            if (!closed) {
                closed = true
                pin.fill('\u0000')
                leases -= this
            }
        }
    }
}
