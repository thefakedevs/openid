package ru.lavafrai.openid.data.repositories.max.impl

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import ru.lavafrai.openid.data.core.AccountCodec
import ru.lavafrai.openid.data.core.DataException
import ru.lavafrai.openid.data.core.WebAppLaunchParser
import ru.lavafrai.openid.data.core.requireData
import ru.lavafrai.openid.data.repositories.max.api.MaxSession
import ru.lavafrai.openid.data.repositories.max.api.MaxSessionRepository
import ru.lavafrai.openid.models.max.MaxAccessToken
import ru.lavafrai.openid.models.max.MaxClientProfile
import ru.lavafrai.openid.models.max.MaxCredentials
import ru.lavafrai.openid.models.max.MaxLoginResult
import ru.lavafrai.openid.models.max.WebAppLaunch

class MaxSessionRepositoryImpl internal constructor(private val connect: suspend (Int) -> MaxTransport) :
    MaxSessionRepository {
    constructor() : this({ version ->
        val transport = MaxTcpTransport(version)
        try {
            transport.connect()
            transport
        } catch (failure: Throwable) {
            transport.close()
            throw failure
        }
    })

    override suspend fun open(
        credentials: MaxCredentials,
        clientProfile: MaxClientProfile,
    ): MaxSession {
        requireData(
            credentials.accountId > 0 && clientProfile.tcpVersion in 10..11 && !clientProfile.fingerprint &&
                !clientProfile.loginUserAgent,
            "IMPORT_LOGIN_PROFILE_UNSUPPORTED",
        )
        AccountCodec.requireNonEmptyString(credentials.accessToken.value)
        AccountCodec.requireNonEmptyString(credentials.deviceId)
        val userAgent = clientProfile.userAgent
        requireData(userAgent.deviceType == "ANDROID", "MOBILE_USER_AGENT_REQUIRED")
        listOf(userAgent.appVersion, userAgent.osVersion, userAgent.deviceName, userAgent.headerUserAgent).forEach {
            AccountCodec.requireNonEmptyString(it)
        }
        val transport = connect(clientProfile.tcpVersion)
        try {
            val init = transport.request(
                6,
                mapOf(
                    "deviceId" to credentials.deviceId,
                    "userAgent" to mapOf(
                        "deviceType" to userAgent.deviceType,
                        "appVersion" to userAgent.appVersion,
                        "osVersion" to userAgent.osVersion,
                        "deviceName" to userAgent.deviceName,
                        "headerUserAgent" to userAgent.headerUserAgent,
                    ),
                ),
            )
            currentCoroutineContext().ensureActive()
            return Session(transport, credentials, clientProfile, init)
        } catch (failure: Throwable) {
            transport.close()
            throw failure
        }
    }

    private class Session(
        private val transport: MaxTransport,
        credentials: MaxCredentials,
        private val profile: MaxClientProfile,
        init: Map<Any?, Any?>,
    ) : MaxSession {
        private var credentials: MaxCredentials? = credentials
        private var initReply = init
        private var loginReply: Map<Any?, Any?> = emptyMap()
        private var loginResult: MaxLoginResult? = null
        private var botLookupCompleted = false
        private var resolvedBotId: Long? = null
        private val mutex = Mutex()

        @Volatile
        private var closed = false

        private fun ensureOpen() {
            requireData(!closed, "MAX_SESSION_CLOSED")
        }

        override suspend fun login(): MaxLoginResult = mutex.withLock {
            ensureOpen()
            loginResult?.let { return@withLock it }
            val account = checkNotNull(credentials)
            val reply = transport.request(
                19,
                mapOf(
                    "token" to account.accessToken.value,
                    "interactive" to true,
                    "chatsCount" to 0,
                    "chatsSync" to 0,
                    "contactsSync" to 0,
                    "presenceSync" to 0,
                    "draftsSync" to 0,
                ),
            )
            ensureOpen()
            // New servers announce that the profile is delivered by LOGIN2 instead of LOGIN.
            val flags = reply["login2Flags"] as? Map<*, *>
            val extra = if (reply["profile"] == null && flags?.get("profileEnabled") == true) {
                val hash = ((reply["config"] as? Map<*, *>)?.get("hash") as? String) ?: ""
                transport.request(8, mapOf("configHash" to hash, "contactsSync" to 0, "needProfile" to true))
            } else {
                emptyMap()
            }
            ensureOpen()
            val remoteProfile =
                (reply["profile"] ?: extra["profile"]) as? Map<*, *> ?: throw DataException("MAX_LOGIN_PROFILE_MISSING")
            val contact = remoteProfile["contact"] as? Map<*, *>
            val accountId = (if (contact != null) contact["id"] else remoteProfile["id"]) as? Long
            requireData(accountId == null || accountId == account.accountId, "MAX_ACCOUNT_MISMATCH")
            val tokenAttrs = reply["tokenAttrs"] as? Map<*, *>
            val returnedToken =
                ((tokenAttrs?.get("LOGIN") as? Map<*, *>)?.get("token") as? String)?.takeIf { it.isNotEmpty() }
                    ?: reply["token"] as? String
            val token = returnedToken?.takeIf { it.isNotEmpty() && it != account.accessToken.value }
            token?.let { AccountCodec.requireNonEmptyString(it) }
            loginReply = mapOf("login" to reply, "login2" to extra)
            MaxLoginResult(
                accountId = accountId,
                rotatedAccessToken = token?.let(::MaxAccessToken),
                digitalIdBotId = findBot(initReply) ?: findBot(loginReply),
            ).also { loginResult = it }
        }

        override suspend fun resolveDigitalIdBotId(importedBotId: Long?): Long = mutex.withLock {
            ensureOpen()
            requireData(loginResult != null, "MAX_LOGIN_REQUIRED")
            importedBotId?.let {
                requireData(it > 0, "IMPORT_BOT_ID_INVALID")
                return@withLock it
            }
            findBot(initReply)?.let { return@withLock it }
            findBot(loginReply)?.let { return@withLock it }
            if (!botLookupCompleted) {
                resolvedBotId = findBot(transport.request(101, emptyMap()))
                botLookupCompleted = true
            }
            ensureOpen()
            resolvedBotId ?: throw DataException("DIGITAL_ID_BOT_ID_NOT_FOUND")
        }

        override suspend fun getWebAppLaunch(botId: Long): WebAppLaunch = mutex.withLock {
            ensureOpen()
            requireData(loginResult != null && botId > 0, "MAX_LOGIN_OR_BOT_REQUIRED")
            val url =
                transport.request(160, mapOf("botId" to botId))["url"] as? String
                    ?: throw DataException("WEBAPP_URL_MISSING")
            ensureOpen()
            WebAppLaunchParser.parse(url, checkNotNull(credentials).accountId, profile.webUserAgent)
        }

        override fun close() {
            closed = true
            transport.close()
            credentials = null
            initReply = emptyMap()
            loginReply = emptyMap()
            loginResult = null
        }
    }
}

internal fun findBot(
    value: Any?,
    depth: Int = 0,
): Long? {
    if (depth > 12) return null
    when (value) {
        is Map<*, *> -> {
            for (key in listOf("digitalid-botid", "digitalidBotid")) {
                val botId = when (val candidate = value[key]) {
                    is Long -> candidate
                    is String -> candidate.takeIf { it.all { character -> character in '0'..'9' } }?.toLongOrNull()
                    else -> null
                }
                if (botId != null && botId > 0) return botId
            }
            for (child in value.values) findBot(child, depth + 1)?.let { return it }
        }

        is List<*> -> for (child in value) findBot(child, depth + 1)?.let { return it }
    }
    return null
}
