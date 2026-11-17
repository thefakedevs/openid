package ru.lavafrai.openid.data.repositories.account.impl

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
import ru.lavafrai.openid.data.core.AccountCodec
import ru.lavafrai.openid.data.core.DataException
import ru.lavafrai.openid.data.core.SecretRecord
import ru.lavafrai.openid.data.core.SecretStorage
import ru.lavafrai.openid.data.core.StrictJson
import ru.lavafrai.openid.data.core.VaultProtection
import ru.lavafrai.openid.data.core.requireData
import ru.lavafrai.openid.data.repositories.account.api.AccountRepository
import ru.lavafrai.openid.models.account.AccountLease
import ru.lavafrai.openid.models.account.AccountState
import ru.lavafrai.openid.models.account.AccountSummary
import ru.lavafrai.openid.models.account.ImportedAccount
import ru.lavafrai.openid.models.account.ImportedMaxAccount
import ru.lavafrai.openid.models.auth.CryptoAuthenticator
import ru.lavafrai.openid.models.errors.FailureDiagnostic
import ru.lavafrai.openid.models.errors.FailureKind
import ru.lavafrai.openid.models.max.MaxAccessToken

class AccountRepositoryImpl(private val storage: SecretStorage) : AccountRepository {
    private val stateLock = Any()
    private val state = MutableStateFlow<AccountState>(AccountState.Loading)

    @Volatile
    private var record: SecretRecord? = null
    private var initializationJob: Job? = null
    private val leases = mutableSetOf<Lease>()
    override val accountState: StateFlow<AccountState> get() {
        if (state.value is AccountState.Unavailable) initialize()
        return state.asStateFlow()
    }

    init {
        initialize()
    }

    private fun initialize() = synchronized(stateLock) {
        if (initializationJob?.isActive == true) return@synchronized
        state.value = AccountState.Loading
        initializationJob = storage.scope.launch {
            try {
                storage.awaitReady()
                storage.mutex.withLock {
                    val row = storage.dao.read("account")
                    row?.let {
                        requireData(
                            it.accountId > 0 && it.protection in setOf("DEVICE_KEY", "SYSTEM_AUTH_REQUIRED"),
                            "STORAGE_METADATA_INVALID",
                        )
                    }
                    synchronized(stateLock) {
                        record = row
                        publishState()
                    }
                }
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Exception) {
                state.value =
                    AccountState.Unavailable(
                        FailureDiagnostic.capture("STORAGE_UNAVAILABLE", FailureKind.STORAGE, failure),
                    )
            }
        }
    }

    private suspend fun awaitReady() {
        initializationJob?.join()
        requireData(state.value !is AccountState.Unavailable, "STORAGE_UNAVAILABLE")
    }

    private fun summary(row: SecretRecord) = AccountSummary(row.accountId, row.tokenProfile)

    private fun publishState() {
        state.value = record?.let {
            when {
                it.protection == "SYSTEM_AUTH_REQUIRED" -> AccountState.MigrationRequired(summary(it))
                leases.isEmpty() -> AccountState.Locked(summary(it))
                else -> AccountState.Unlocked(summary(it))
            }
        } ?: AccountState.Empty
    }

    override suspend fun getSummary(): AccountSummary? {
        awaitReady()
        return synchronized(stateLock) { record?.let(::summary) }
    }

    override suspend fun save(account: ImportedAccount) = store(account, false)

    override suspend fun replace(account: ImportedAccount) = store(account, true)

    private suspend fun store(
        account: ImportedAccount,
        replace: Boolean,
    ) {
        awaitReady()
        val old = storage.mutex.withLock {
            record.also { requireData((it != null) == replace, if (replace) "ACCOUNT_MISSING" else "ACCOUNT_EXISTS") }
        }
        write(account, old)
    }

    private suspend fun write(
        account: ImportedAccount,
        old: SecretRecord?,
    ) {
        val raw = AccountCodec.encode(account)
        try {
            AccountCodec.decode(StrictJson.parse(raw))
            val next = storage.cipher.create(
                "account",
                account.accountId,
                "DEVICE_KEY",
                VaultProtection.DEVICE,
                raw,
                null,
                tokenProfile = account.did.tokenProfile,
            )
            storage.commit(
                next,
                old,
                validate = {
                    requireData(record?.alias == old?.alias, "ACCOUNT_CHANGED")
                    requireData(old == null || record?.iv?.contentEquals(old.iv) == true, "ACCOUNT_CHANGED")
                },
                publish = {
                    synchronized(stateLock) {
                        revokeLeases()
                        record = next
                        publishState()
                    }
                },
            )
        } finally {
            raw.fill(0)
        }
    }

    override suspend fun unlock(): AccountLease {
        awaitReady()
        val row = synchronized(stateLock) { record ?: throw DataException("ACCOUNT_MISSING") }
        requireData(row.protection == "DEVICE_KEY", "ACCOUNT_MIGRATION_REQUIRED")
        val secret = storage.cipher.open(row, null)
        var handedOff = false
        try {
            val account = AccountCodec.decode(StrictJson.parse(secret.raw))
            requireData(account.accountId == row.accountId, "STORAGE_METADATA_MISMATCH")
            secret.raw.fill(0)
            currentCoroutineContext().ensureActive()
            return synchronized(stateLock) {
                requireData(record?.alias == row.alias, "ACCOUNT_CHANGED")
                requireData(record?.iv?.contentEquals(row.iv) == true, "ACCOUNT_CHANGED")
                Lease(row.alias, account, secret.key).also {
                    leases += it
                    publishState()
                    handedOff = true
                }
            }
        } finally {
            if (!handedOff) secret.close()
        }
    }

    override suspend fun updateMaxToken(
        lease: AccountLease,
        token: MaxAccessToken,
    ) {
        awaitReady()
        AccountCodec.requireNonEmptyString(token.value)
        withContext(Dispatchers.IO) {
            storage.mutex.withLock {
                val ownedLease = requireOwnedLease(lease)
                val account = ownedLease.account
                val updated = ImportedAccount(
                    account.accountId,
                    ImportedMaxAccount(token, account.max.deviceId, account.max.userAgent, account.max.botId),
                    account.did,
                    account.webUserAgent,
                    account.options,
                    account.schema,
                )
                val raw = AccountCodec.encode(updated)
                try {
                    val row =
                        synchronized(stateLock) {
                            ownedLease.ensureValid()
                            storage.cipher.rewrite(checkNotNull(record), ownedLease.key, raw)
                        }
                    currentCoroutineContext().ensureActive()
                    withContext(NonCancellable) {
                        storage.dao.write(row)
                        synchronized(stateLock) {
                            record = row
                            leases.forEach { it.snapshot = updated }
                        }
                    }
                } finally {
                    raw.fill(0)
                }
            }
        }
    }

    override suspend fun migrateLegacyProtection(authenticator: CryptoAuthenticator) {
        awaitReady()
        val old = synchronized(stateLock) { record ?: throw DataException("ACCOUNT_MISSING") }
        if (old.protection == "DEVICE_KEY") return
        requireData(old.protection == "SYSTEM_AUTH_REQUIRED", "STORAGE_METADATA_INVALID")
        val secret = storage.cipher.open(old, authenticator)
        try {
            val account = AccountCodec.decode(StrictJson.parse(secret.raw))
            requireData(account.accountId == old.accountId, "STORAGE_METADATA_MISMATCH")
            write(account, old)
        } finally {
            secret.close()
        }
    }

    override suspend fun delete() {
        awaitReady()
        storage.delete(
            slot = "account",
            current = { record },
            revokeLeases = { synchronized(stateLock) { revokeLeases() } },
            publish = {
                synchronized(stateLock) {
                    record = null
                    publishState()
                }
            },
        )
    }

    private fun requireOwnedLease(lease: AccountLease): Lease = synchronized(stateLock) {
        (lease as? Lease)?.also {
            requireData(it in leases, "ACCOUNT_LEASE_INVALID")
            it.ensureValid()
        }
            ?: throw DataException("ACCOUNT_LEASE_INVALID")
    }

    private fun revokeLeases() {
        leases.toList().forEach { it.close() }
    }

    private inner class Lease(
        val generation: String,
        var snapshot: ImportedAccount?,
        val key: ByteArray,
    ) : AccountLease {
        private var closed = false

        fun ensureValid() {
            requireData(!closed && record?.alias == generation, "ACCOUNT_LEASE_REVOKED")
        }
        override val account: ImportedAccount get() = synchronized(stateLock) {
            ensureValid()
            checkNotNull(snapshot)
        }

        override fun close() = synchronized(stateLock) {
            if (!closed) {
                closed = true
                key.fill(0)
                snapshot = null
                leases -= this
                publishState()
            }
        }
    }
}
