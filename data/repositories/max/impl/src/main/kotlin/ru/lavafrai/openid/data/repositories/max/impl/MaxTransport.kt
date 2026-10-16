package ru.lavafrai.openid.data.repositories.max.impl

import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import ru.lavafrai.openid.data.core.DataException
import ru.lavafrai.openid.data.core.requireData

internal interface MaxTransport : AutoCloseable {
    suspend fun request(
        opcode: Int,
        payload: Map<String, Any?>,
    ): Map<Any?, Any?>
}

/** TLS verification/hostname checks use the platform trust store. One request at a time; cancellation closes the socket. */
internal class MaxTcpTransport(
    private val version: Int,
    initialSocket: Socket? = null,
) : MaxTransport {
    @Volatile
    private var socket: Socket? = initialSocket

    @Volatile
    private var closed = false
    private var sequence = 0
    private val mutex = Mutex()

    suspend fun connect() = cancellableIo {
        requireData(version in 10..11, "MAX_TCP_PROTOCOL_VERSION")
        val rawSocket = Socket().also { socket = it }
        if (closed) {
            rawSocket.close()
            throw DataException("MAX_TCP_CLOSED")
        }
        rawSocket.connect(InetSocketAddress("api.oneme.ru", 443), 20_000)
        requireData(!closed, "MAX_TCP_CLOSED")
        val tls = (SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket(
            rawSocket,
            "api.oneme.ru",
            443,
            true,
        ) as SSLSocket
        socket = tls
        tls.sslParameters = tls.sslParameters.apply { endpointIdentificationAlgorithm = "HTTPS" }
        tls.soTimeout = 20_000
        tls.startHandshake()
        requireData(!closed, "MAX_TCP_CLOSED")
    }

    override suspend fun request(
        opcode: Int,
        payload: Map<String, Any?>,
    ): Map<Any?, Any?> = mutex.withLock {
        cancellableIo {
            val connection = socket ?: throw DataException("MAX_TCP_CLOSED")
            requireData(!closed, "MAX_TCP_CLOSED")
            val payloadBytes = MaxWire.pack(payload)
            sequence = (sequence + 1) and 255
            val requestSequence = sequence
            val header = ByteBuffer.allocate(10)
                .put(version.toByte())
                .putShort(0)
                .put(requestSequence.toByte())
                .putShort(opcode.toShort())
                .putInt(payloadBytes.size)
                .array()
            connection.getOutputStream().apply {
                write(header)
                write(payloadBytes)
                flush()
            }
            val deadline = System.nanoTime() + 20_000_000_000L
            while (true) {
                val response = ByteBuffer.wrap(readExact(10, deadline))
                val wireVersion = response.get().toInt() and 255
                val command = response.short.toInt() and 65535
                val responseSequence = response.get().toInt() and 255
                val responseOpcode = response.short.toInt() and 65535
                val sizeAndCompression = response.int
                val compression = sizeAndCompression ushr 24
                val length = sizeAndCompression and 0xffffff
                requireData(wireVersion in 10..11, "MAX_TCP_PROTOCOL_VERSION")
                requireData(command in setOf(0, 256, 512, 768), "MAX_TCP_COMMAND_UNKNOWN")
                requireData(length <= 8 * 1024 * 1024, "MAX_TCP_RESPONSE_TOO_LARGE")
                val body = MaxWire.unpack(readExact(length, deadline), compression)
                if (responseOpcode != opcode || responseSequence != requestSequence ||
                    command !in setOf(256, 768)
                ) {
                    continue
                }
                requireData(command != 768 && !truthy(body["error"]), "MAX_SERVER_REJECTED_OPCODE_$opcode")
                return@cancellableIo body
            }
            @Suppress("UNREACHABLE_CODE")
            throw DataException("MAX_TCP_TIMEOUT")
        }
    }

    private fun readExact(
        count: Int,
        deadline: Long,
    ): ByteArray {
        val connection = socket ?: throw DataException("MAX_TCP_CLOSED")
        val data = ByteArray(count)
        var offset = 0
        while (offset < count) {
            val remaining = (deadline - System.nanoTime()) / 1_000_000
            requireData(remaining > 0, "MAX_TCP_TIMEOUT")
            connection.soTimeout = remaining.coerceAtMost(20_000).toInt().coerceAtLeast(1)
            val read = connection.getInputStream().read(data, offset, count - offset)
            requireData(read > 0, "MAX_TCP_CLOSED")
            offset += read
        }
        return data
    }

    private suspend fun <T> cancellableIo(block: () -> T): T = coroutineScope {
        val worker = async(Dispatchers.IO) {
            try {
                block()
            } catch (failure: DataException) {
                currentCoroutineContext().ensureActive()
                throw failure
            } catch (failure: Exception) {
                currentCoroutineContext().ensureActive()
                throw DataException("MAX_TCP_CONNECTION_FAILED", failure)
            }
        }
        try {
            worker.await()
        } catch (failure: Throwable) {
            close()
            throw failure
        }
    }

    override fun close() {
        closed = true
        runCatching { socket?.close() }
        socket = null
    }

    private fun truthy(value: Any?) = when (value) {
        null, false, "" -> false
        is Number -> value.toDouble() != 0.0
        is Map<*, *> -> value.isNotEmpty()
        is List<*> -> value.isNotEmpty()
        is ByteArray -> value.isNotEmpty()
        else -> true
    }
}
