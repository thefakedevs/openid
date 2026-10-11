package ru.lavafrai.openid.ui

import android.content.Context
import androidx.core.content.edit
import ru.lavafrai.openid.models.pin.PinProtection

/** Only the selected policy and account ID; never the PIN or credentials. */
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

    fun save(
        accountId: Long,
        protection: PinProtection,
    ) {
        preferences.edit {
            putLong("accountId", accountId)
            putString("protection", protection.name)
        }
    }

    fun clear() {
        preferences.edit {
            clear()
        }
    }
}
