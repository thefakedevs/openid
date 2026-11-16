package ru.lavafrai.openid.ui

import android.content.Context
import androidx.core.content.edit
import ru.lavafrai.openid.models.pin.PinProtection

/** Only the selected policy, the account ID and the profile kind; never the PIN or credentials. */
class PinProtectionPreferences(context: Context) {
    private val preferences = context.getSharedPreferences("pin-protection-policy", Context.MODE_PRIVATE)

    fun get(accountId: Long): PinProtection? {
        if (preferences.getLong("accountId", -1) != accountId) return null
        val value = preferences.getString("protection", null)
        if (value == "DEVICE_KEY") {
            save(accountId, PinProtection.NOT_SAVED)
            return PinProtection.NOT_SAVED
        }
        return PinProtection.entries.firstOrNull { it.name == value }
    }

    fun isTokenProfile(accountId: Long): Boolean = preferences.getLong("accountId", -1) == accountId &&
        preferences.getBoolean("tokenProfile", false)

    fun save(
        accountId: Long,
        protection: PinProtection,
        tokenProfile: Boolean = preferences.getLong("accountId", -1) == accountId &&
            preferences.getBoolean("tokenProfile", false),
    ) {
        preferences.edit {
            putLong("accountId", accountId)
            putString("protection", protection.name)
            putBoolean("tokenProfile", tokenProfile)
        }
    }

    /**
     * Write-ahead phase: records the values a pending import intends to commit.
     * The actual [save] happens only after the account repository confirms the write
     * via [finalizeImport], so a crash or failure before that leaves the current
     * preferences intact.
     */
    fun prepareImport(
        accountId: Long,
        protection: PinProtection,
        tokenProfile: Boolean,
    ) {
        preferences.edit {
            putLong("txAccountId", accountId)
            putString("txProtection", protection.name)
            putBoolean("txTokenProfile", tokenProfile)
            putBoolean("txCommitted", false)
        }
    }

    /**
     * Called immediately after the account repository write succeeds, before any
     * other side effects. Marks the pending transaction so [recoverPendingImport]
     * can finalize it if the process dies before [finalizeImport].
     */
    fun markImportCommitted() {
        preferences.edit { putBoolean("txCommitted", true) }
    }

    /**
     * Applies the pending values as the current preferences and clears the
     * transaction keys. Must be called after [markImportCommitted].
     */
    fun finalizeImport() {
        val txAccountId = preferences.getLong("txAccountId", -1)
        if (txAccountId < 0) return
        val protection = preferences.getString("txProtection", null)
        val tokenProfile = preferences.getBoolean("txTokenProfile", false)
        preferences.edit {
            putLong("accountId", txAccountId)
            putString("protection", protection)
            putBoolean("tokenProfile", tokenProfile)
            remove("txAccountId")
            remove("txProtection")
            remove("txTokenProfile")
            remove("txCommitted")
        }
    }

    /**
     * On startup, detects and resolves a half-finished import transaction.
     * If the account write had already succeeded ([markImportCommitted] was called),
     * the pending preferences are applied; otherwise they are discarded.
     */
    fun recoverPendingImport() {
        if (!preferences.contains("txAccountId")) return
        if (preferences.getBoolean("txCommitted", false)) {
            finalizeImport()
        } else {
            preferences.edit {
                remove("txAccountId")
                remove("txProtection")
                remove("txTokenProfile")
                remove("txCommitted")
            }
        }
    }

    fun clear() {
        preferences.edit {
            clear()
        }
    }
}
