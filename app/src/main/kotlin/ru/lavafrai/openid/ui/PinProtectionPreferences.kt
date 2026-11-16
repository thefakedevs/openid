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

    fun isTokenProfile(accountId: Long): Boolean =
        preferences.getLong("accountId", -1) == accountId && preferences.getBoolean("tokenProfile", false)

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

    data class Snapshot(val accountId: Long, val protection: String?, val tokenProfile: Boolean)

    fun snapshot(): Snapshot? {
        val accountId = preferences.getLong("accountId", -1)
        if (accountId < 0) return null
        return Snapshot(
            accountId = accountId,
            protection = preferences.getString("protection", null),
            tokenProfile = preferences.getBoolean("tokenProfile", false),
        )
    }

    fun restore(snapshot: Snapshot) {
        preferences.edit {
            putLong("accountId", snapshot.accountId)
            putString("protection", snapshot.protection)
            putBoolean("tokenProfile", snapshot.tokenProfile)
        }
    }

    fun clear() {
        preferences.edit {
            clear()
        }
    }
}
