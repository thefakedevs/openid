package ru.lavafrai.openid.data.repositories.digitalid.impl

import java.security.MessageDigest
import java.util.WeakHashMap
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import ru.lavafrai.openid.data.core.AccountCodec
import ru.lavafrai.openid.data.core.DataException
import ru.lavafrai.openid.data.core.DebugTrace
import ru.lavafrai.openid.data.core.StrictJson
import ru.lavafrai.openid.data.core.requireData
import ru.lavafrai.openid.data.repositories.digitalid.api.DigitalIdRepository
import ru.lavafrai.openid.models.digitalid.AuthorizationResult
import ru.lavafrai.openid.models.digitalid.DigitalIdToken
import ru.lavafrai.openid.models.digitalid.QrSession
import ru.lavafrai.openid.models.digitalid.SecurityStatus
import ru.lavafrai.openid.models.max.WebAppLaunch

class DigitalIdRepositoryImpl internal constructor(private val http: DigitalIdHttp) : DigitalIdRepository {
    constructor() : this(RealDigitalIdHttp())
    private val mutex = Mutex()
    private val authorizedTokens = WeakHashMap<WebAppLaunch, ByteArray>()

    private suspend fun requestData(
        launch: WebAppLaunch,
        method: String,
        path: String,
        token: DigitalIdToken? = null,
    ): Map<String, Any?> {
        token?.let { AccountCodec.requireNonEmptyString(it.value) }
        val reply = http.call(launch, method, path, token?.value)
        if (reply.status !in 200..299) {
            DebugTrace.line(
                "http $method $path status=${reply.status} body=" +
                    String(reply.body, Charsets.UTF_8).take(200),
            )
        }
        requireData(reply.status in 200..299, "DIGITAL_ID_HTTP_REJECTED_${reply.status}")
        requireData(
            reply.encoding == null || reply.encoding.equals("identity", ignoreCase = true),
            "HTTP_UNEXPECTED_CONTENT_ENCODING",
        )
        requireData(reply.body.size <= MAX_HTTP, "HTTP_RESPONSE_TOO_LARGE")
        val result = StrictJson.obj(StrictJson.parse(reply.body))
        requireData(result["status"] in setOf("success", "ok"), "DIGITAL_ID_API_STATUS_NOT_SUCCESS")
        return StrictJson.obj(result["data"])
    }

    override suspend fun getSecurityStatus(launch: WebAppLaunch): SecurityStatus = mutex.withLock {
        val data = requestData(launch, "GET", STATUS_PATH)
        // A captured-token profile has no PIN, so the service may omit attempts_left (or send null);
        // only a present value must be a valid integer. Absent means "no attempt limit applies".
        val attempts = data["attempts_left"]?.let { value ->
            val number = StrictJson.decimal(value)
            requireData(
                number % 1.0 == 0.0 && number in Int.MIN_VALUE.toDouble()..Int.MAX_VALUE.toDouble(),
                "DID_ATTEMPTS_INVALID",
            )
            number.toInt()
        }
        SecurityStatus(
            tokenLevel = AccountCodec.requireNonEmptyString(data["token_level"]),
            deviceId = AccountCodec.requireNonEmptyString(data["device_id"], 1024),
            timestamp = AccountCodec.timestamp(data["timestamp"]),
            attemptsLeft = attempts,
            cooldown = data["cooldown"]?.let(StrictJson::decimal),
        )
    }

    override suspend fun authorize(
        launch: WebAppLaunch,
        token: DigitalIdToken,
    ): AuthorizationResult = mutex.withLock {
        authorizedTokens.remove(launch)?.fill(0)
        val status = try {
            requestData(launch, "POST", AUTH_PATH, token)["authorization_status"]
        } catch (failure: DataException) {
            if (failure.code == "DIGITAL_ID_HTTP_REJECTED_400") probeRejection(launch)
            throw failure
        }
        requireData(status is Boolean, "DID_AUTHORIZATION_REPLY_INVALID")
        if (status == true) authorizedTokens[launch] = tokenDigest(token)
        AuthorizationResult(status == true)
    }

    /** Debug-build probe: repeats an authorization with a dummy token to compare rejection bodies. */
    private suspend fun probeRejection(launch: WebAppLaunch) {
        val probe = DigitalIdToken("v.3.1:" + "0".repeat(64))
        runCatching { requestData(launch, "POST", AUTH_PATH, probe) }
    }

    override suspend fun getUniversalQr(
        launch: WebAppLaunch,
        token: DigitalIdToken,
    ): QrSession = mutex.withLock {
        val suppliedDigest = tokenDigest(token)
        try {
            requireData(
                authorizedTokens[launch]?.let {
                    MessageDigest.isEqual(it, suppliedDigest)
                } == true,
                "DID_AUTHORIZATION_REQUIRED",
            )
        } finally {
            suppliedDigest.fill(0)
        }
        val sessionId =
            requestData(launch, "POST", QR_PATH, token)["session_id"] as? String
                ?: throw DataException("QR_SESSION_ID_MISSING_OR_INVALID")
        requireData(
            sessionId.isNotEmpty() && sessionId.toByteArray(Charsets.UTF_8).size <= 2000,
            "QR_SESSION_ID_MISSING_OR_INVALID",
        )
        QrSession(sessionId)
    }

    private fun tokenDigest(token: DigitalIdToken): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(token.value.toByteArray(Charsets.UTF_8))
}
