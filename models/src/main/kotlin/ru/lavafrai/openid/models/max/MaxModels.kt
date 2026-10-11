package ru.lavafrai.openid.models.max

class MaxAccessToken(val value: String) {
    override fun toString(): String = "MaxAccessToken(<redacted>)"
}

class MaxCredentials(
    val accountId: Long,
    val accessToken: MaxAccessToken,
    val deviceId: String,
) {
    override fun toString(): String = "MaxCredentials(accountId=$accountId, credentials=<redacted>)"
}

data class MaxUserAgent(
    val appVersion: String,
    val osVersion: String,
    val deviceName: String,
    val headerUserAgent: String,
    val deviceType: String = "ANDROID",
)

data class MaxClientProfile(
    val userAgent: MaxUserAgent,
    val webUserAgent: String,
    val tcpVersion: Int = 10,
    val fingerprint: Boolean = false,
    val loginUserAgent: Boolean = false,
)

data class MaxLoginResult(
    val accountId: Long?,
    val rotatedAccessToken: MaxAccessToken? = null,
    val digitalIdBotId: Long? = null,
)

/** Signed WebAppData is kept verbatim after a single outer URL decode. */
class WebAppLaunch(
    val url: String,
    val webAppData: String,
    val accountId: Long,
    val authDate: Long,
    val webUserAgent: String,
) {
    override fun toString(): String = "WebAppLaunch(accountId=$accountId, authDate=$authDate, launch=<redacted>)"
}
