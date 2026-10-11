package ru.lavafrai.openid.data.repositories.digitalid.impl

import java.io.ByteArrayOutputStream
import java.net.Proxy
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import ru.lavafrai.openid.data.core.DataException
import ru.lavafrai.openid.data.core.StrictJson
import ru.lavafrai.openid.data.core.requireData
import ru.lavafrai.openid.models.max.WebAppLaunch

internal data class HttpReply(
    val status: Int,
    val encoding: String?,
    val body: ByteArray,
)

internal fun interface DigitalIdHttp {
    suspend fun call(
        launch: WebAppLaunch,
        method: String,
        path: String,
        token: String?,
    ): HttpReply
}

internal class RealDigitalIdHttp : DigitalIdHttp {
    private val client by lazy {
        OkHttpClient.Builder()
            .proxy(Proxy.NO_PROXY)
            .retryOnConnectionFailure(false)
            .followRedirects(false)
            .followSslRedirects(false)
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .callTimeout(20, TimeUnit.SECONDS)
            .protocols(listOf(Protocol.HTTP_1_1))
            .build()
    }

    internal fun request(
        launch: WebAppLaunch,
        method: String,
        path: String,
        token: String?,
    ): Request {
        requireData(
            (method == "GET" && path == STATUS_PATH && token == null) ||
                (method == "POST" && path in setOf(AUTH_PATH, QR_PATH) && token != null),
            "HTTP_ENDPOINT_NOT_ALLOWED",
        )
        requireData(
            launch.webAppData.isNotEmpty() && launch.webAppData.length <= 32768 &&
                launch.webAppData.all { it.code in 32..126 },
            "WEBAPPDATA_MISSING_OR_BAD_HEADER",
        )
        requireData(
            launch.webUserAgent.isNotEmpty() && launch.webUserAgent.length <= 4096 &&
                launch.webUserAgent.all { it.code in 32..126 },
            "WEB_USER_AGENT_INVALID",
        )
        val body = token?.let {
            StrictJson.encode(mapOf("token" to it)).toRequestBody("application/json".toMediaType())
        }
        return Request.Builder()
            .url("https://ext-api.max.ru$path")
            .header("Authorization", "#WebAppData=${launch.webAppData}")
            .header("Origin", "https://digital-id.max.ru")
            .header("Referer", "https://digital-id.max.ru/")
            .header("X-Requested-With", "ru.oneme.app")
            .header("User-Agent", launch.webUserAgent)
            .header("Accept", "application/json")
            .header("Accept-Encoding", "identity")
            .header("Connection", "close")
            .method(method, body)
            .build()
    }

    override suspend fun call(
        launch: WebAppLaunch,
        method: String,
        path: String,
        token: String?,
    ): HttpReply = coroutineScope {
        val call = client.newCall(request(launch, method, path, token))
        val worker = async(Dispatchers.IO) {
            try {
                call.execute().use { response ->
                    val body = response.body
                    requireData(body.contentLength() <= MAX_HTTP, "HTTP_RESPONSE_TOO_LARGE")
                    val bytes = ByteArrayOutputStream()
                    body.byteStream().use { stream ->
                        val buffer = ByteArray(8192)
                        while (true) {
                            val count = stream.read(buffer)
                            if (count < 0) break
                            requireData(bytes.size() + count <= MAX_HTTP, "HTTP_RESPONSE_TOO_LARGE")
                            bytes.write(buffer, 0, count)
                        }
                    }
                    HttpReply(response.code, response.header("Content-Encoding"), bytes.toByteArray())
                }
            } catch (failure: DataException) {
                currentCoroutineContext().ensureActive()
                throw failure
            } catch (_: Exception) {
                currentCoroutineContext().ensureActive()
                throw DataException("DIGITAL_ID_CONNECTION_FAILED")
            }
        }
        try {
            worker.await()
        } catch (failure: Throwable) {
            call.cancel()
            throw failure
        }
    }
}

internal const val STATUS_PATH = "/v3.1/digital-id/security-status"
internal const val AUTH_PATH = "/v3.1/digital-id/authorization"
internal const val QR_PATH = "/v3.1/digital-id/user-qr"
internal const val MAX_HTTP = 8 * 1024 * 1024
