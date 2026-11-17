package ru.lavafrai.openid.ui

import android.content.Context
import androidx.core.content.edit
import java.util.UUID
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
     * A unique nonce ties [markImportCommitted] to this exact transaction so that
     * [recoverPendingImport] can distinguish a committed write from a stale one
     * even when the account id stays the same (same-user replacement).
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
            putString("txNonce", UUID.randomUUID().toString())
            remove("txCommittedNonce")
        }
    }

    /**
     * Called immediately after the account repository write succeeds, before any
     * other side effects. Copies the transaction nonce into the committed slot so
     * [recoverPendingImport] can verify this exact transaction was committed.
     */
    fun markImportCommitted() {
        val nonce = preferences.getString("txNonce", null) ?: return
        preferences.edit { putString("txCommittedNonce", nonce) }
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
            remove("txNonce")
            remove("txCommittedNonce")
        }
    }

    /**
     * On startup, detects and resolves a half-finished import transaction.
     * Finalizes only when the committed nonce matches the transaction nonce,
     * proving the account write succeeded for this specific transaction.
     */
    fun recoverPendingImport() {
        if (!preferences.contains("txAccountId")) return
        val nonce = preferences.getString("txNonce", null)
        val committedNonce = preferences.getString("txCommittedNonce", null)
        if (nonce != null && nonce == committedNonce) {
            finalizeImport()
        } else {
            preferences.edit {
                remove("txAccountId")
                remove("txProtection")
                remove("txTokenProfile")
                remove("txNonce")
                remove("txCommittedNonce")
            }
        }
    }

    fun clear() {
        preferences.edit {
            clear()
        }
    }
}
