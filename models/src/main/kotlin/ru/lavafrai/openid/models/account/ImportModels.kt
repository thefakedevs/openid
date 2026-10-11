package ru.lavafrai.openid.models.account

import ru.lavafrai.openid.models.digitalid.DigitalIdTimestamp
import ru.lavafrai.openid.models.digitalid.DigitalIdToken
import ru.lavafrai.openid.models.max.MaxAccessToken
import ru.lavafrai.openid.models.max.MaxUserAgent

/** Decrypted account payload from the schema-1 import envelope. */
class ImportedAccount(
    val accountId: Long,
    val max: ImportedMaxAccount,
    val did: ImportedDigitalId,
    val webUserAgent: String,
    val options: ImportOptions = ImportOptions(),
    val schema: Int = 1,
) {
    override fun toString(): String = "ImportedAccount(accountId=$accountId, schema=$schema, credentials=<redacted>)"
}

class ImportedMaxAccount(
    val accessToken: MaxAccessToken,
    val deviceId: String,
    val userAgent: MaxUserAgent,
    val botId: Long? = null,
) {
    override fun toString(): String = "ImportedMaxAccount(<redacted>)"
}

class ImportedDigitalId(
    val biometricDeviceId: String,
    val creationTimestamp: DigitalIdTimestamp,
    val pinSalt: String? = null,
    val biometricToken: DigitalIdToken? = null,
) {
    override fun toString(): String = "ImportedDigitalId(<redacted>)"
}

data class ImportOptions(
    val tcpVersion: Int = 10,
    val fingerprint: Boolean = false,
    val loginUserAgent: Boolean = false,
)
