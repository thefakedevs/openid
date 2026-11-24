package ru.lavafrai.openid.ui

import android.content.Context
import androidx.core.content.edit
import ru.lavafrai.openid.models.pin.PinProtection

/**
 * Only the selected PIN-protection policy and the account ID it belongs to; never the PIN,
 * credentials or the profile kind. The profile kind (token profile vs. PIN profile) lives in the
 * account record itself ([ru.lavafrai.openid.models.account.AccountSummary.tokenProfile]), written
 * atomically with the account, so it cannot drift out of sync with the saved credentials the way a
 * separate flag here once did.
 */
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
