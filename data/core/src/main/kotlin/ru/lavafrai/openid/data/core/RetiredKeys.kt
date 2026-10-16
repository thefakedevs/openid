package ru.lavafrai.openid.data.core

import kotlinx.coroutines.CancellationException

/** Access only under the storage mutex. After process death, awaitReady removes the same orphan keys. */
internal class RetiredKeys(private val deleteKey: (String) -> Unit) {
    private data class Owner(
        val slot: String,
        val accountId: Long,
    )

    private val pending = mutableMapOf<String, Owner>()

    fun retire(record: SecretRecord) {
        pending[record.alias] = Owner(record.slot, record.accountId)
        try {
            remove(record.alias)
        } catch (failure: Exception) {
            if (failure is CancellationException) throw failure
            // Cleanup cannot turn an already committed replacement into a failed write.
        }
    }

    fun retry(
        slot: String? = null,
        accountId: Long? = null,
    ) {
        pending.filterValues { (slot == null || it.slot == slot) && (accountId == null || it.accountId == accountId) }
            .keys.toList().forEach(::remove)
    }

    private fun remove(alias: String) {
        deleteKey(alias)
        pending.remove(alias)
    }
}
