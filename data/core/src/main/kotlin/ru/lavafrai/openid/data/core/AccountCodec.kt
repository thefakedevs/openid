package ru.lavafrai.openid.data.core

import ru.lavafrai.openid.models.account.ImportOptions
import ru.lavafrai.openid.models.account.ImportedAccount
import ru.lavafrai.openid.models.account.ImportedDigitalId
import ru.lavafrai.openid.models.account.ImportedMaxAccount
import ru.lavafrai.openid.models.digitalid.DigitalIdTimestamp
import ru.lavafrai.openid.models.digitalid.DigitalIdToken
import ru.lavafrai.openid.models.max.MaxAccessToken
import ru.lavafrai.openid.models.max.MaxUserAgent

/** The same schema is used inside the authenticated local ciphertext and in the import. */
object AccountCodec {
    fun decode(value: Any?): ImportedAccount {
        val accountJson = StrictJson.obj(value)
        requireData(StrictJson.integer(accountJson["schema"]) == 1L, "ACCOUNT_SCHEMA_UNRECOGNIZED")

        val accountId = StrictJson.integer(accountJson["accountId"])
        requireData(accountId > 0, "ACCOUNT_ID_MISSING")

        val maxJson = StrictJson.obj(accountJson["max"])
        val digitalIdJson = StrictJson.obj(accountJson["did"])
        val optionsJson = StrictJson.obj(accountJson["options"])
        val userAgentJson = StrictJson.obj(maxJson["userAgent"])
        requireData(userAgentJson["deviceType"] == "ANDROID", "MOBILE_USER_AGENT_REQUIRED")

        val userAgent = MaxUserAgent(
            appVersion = requireNonEmptyString(userAgentJson["appVersion"]),
            osVersion = requireNonEmptyString(userAgentJson["osVersion"]),
            deviceName = requireNonEmptyString(userAgentJson["deviceName"]),
            headerUserAgent = requireNonEmptyString(userAgentJson["headerUserAgent"]),
        )
        val pinSalt = optionalString(digitalIdJson["pinSalt"], 4096)
        val biometricToken = optionalString(digitalIdJson["biometricToken"], 32768)
        requireData(pinSalt != null, "DID_PIN_SALT_MISSING")

        val webUserAgent = requireNonEmptyString(accountJson["webUserAgent"], 4096)
        requireData(webUserAgent.all { it.code in 32..126 }, "WEB_USER_AGENT_INVALID")

        val tcpVersion = if (optionsJson.containsKey("tcpVersion")) {
            StrictJson.integer(optionsJson["tcpVersion"])
        } else {
            10L
        }
        requireData(tcpVersion == 10L || tcpVersion == 11L, "TCP_VERSION_INVALID")
        requireData(
            (if (optionsJson.containsKey("fingerprint")) optionsJson["fingerprint"] else false) == false &&
                (if (optionsJson.containsKey("loginUserAgent")) optionsJson["loginUserAgent"] else false) == false,
            "IMPORT_LOGIN_PROFILE_UNSUPPORTED",
        )

        val botId = maxJson["botId"]?.let { value ->
            StrictJson.integer(value).also { id -> requireData(id > 0, "IMPORT_BOT_ID_INVALID") }
        }
        return ImportedAccount(
            accountId = accountId,
            max = ImportedMaxAccount(
                accessToken = MaxAccessToken(requireNonEmptyString(maxJson["accessToken"])),
                deviceId = requireNonEmptyString(maxJson["deviceId"]),
                userAgent = userAgent,
                botId = botId,
            ),
            did = ImportedDigitalId(
                biometricDeviceId = requireNonEmptyString(digitalIdJson["biometricDeviceId"], 1024),
                creationTimestamp = timestamp(digitalIdJson["creationTimestamp"]),
                pinSalt = pinSalt,
                biometricToken = biometricToken?.let(::DigitalIdToken),
            ),
            webUserAgent = webUserAgent,
            options = ImportOptions(tcpVersion = tcpVersion.toInt()),
        )
    }

    fun timestamp(value: Any?): DigitalIdTimestamp = when (value) {
        is String -> DigitalIdTimestamp.Text(value)

        is StrictJson.Number -> if (value.integral) {
            DigitalIdTimestamp.Integer(value.long() ?: throw DataException("DID_TIMESTAMP_INVALID"))
        } else {
            DigitalIdTimestamp.Decimal(value.double())
        }

        else -> throw DataException("DID_TIMESTAMP_INVALID")
    }

    fun requireNonEmptyString(
        value: Any?,
        limit: Int = 32768,
    ): String {
        val text = value as? String ?: throw DataException("REQUIRED_STRING_MISSING")
        requireData(text.isNotEmpty() && text.length <= limit && '\u0000' !in text, "REQUIRED_STRING_INVALID")
        return text
    }

    fun encode(account: ImportedAccount): ByteArray {
        val timestamp = when (val creationTimestamp = account.did.creationTimestamp) {
            is DigitalIdTimestamp.Text -> creationTimestamp.value
            is DigitalIdTimestamp.Integer -> creationTimestamp.value
            is DigitalIdTimestamp.Decimal -> creationTimestamp.value
        }
        val userAgent = account.max.userAgent
        val maxJson = mapOf(
            "accessToken" to account.max.accessToken.value,
            "deviceId" to account.max.deviceId,
            "botId" to account.max.botId,
            "userAgent" to mapOf(
                "deviceType" to userAgent.deviceType,
                "appVersion" to userAgent.appVersion,
                "osVersion" to userAgent.osVersion,
                "deviceName" to userAgent.deviceName,
                "headerUserAgent" to userAgent.headerUserAgent,
            ),
        )
        val digitalIdJson = mapOf(
            "biometricDeviceId" to account.did.biometricDeviceId,
            "creationTimestamp" to timestamp,
            "pinSalt" to account.did.pinSalt,
            "biometricToken" to account.did.biometricToken?.value,
        )
        val optionsJson = mapOf(
            "tcpVersion" to account.options.tcpVersion,
            "fingerprint" to account.options.fingerprint,
            "loginUserAgent" to account.options.loginUserAgent,
        )
        return StrictJson.encode(
            mapOf(
                "schema" to account.schema,
                "accountId" to account.accountId,
                "max" to maxJson,
                "did" to digitalIdJson,
                "webUserAgent" to account.webUserAgent,
                "options" to optionsJson,
            ),
        )
    }

    private fun optionalString(
        value: Any?,
        limit: Int,
    ): String? = if (value == null || value == "") null else requireNonEmptyString(value, limit)
}
