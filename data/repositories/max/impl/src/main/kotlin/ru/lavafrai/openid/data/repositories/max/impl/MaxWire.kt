package ru.lavafrai.openid.data.repositories.max.impl

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import net.jpountz.lz4.LZ4Factory
import org.msgpack.core.MessagePack
import org.msgpack.core.MessagePacker
import org.msgpack.core.MessageUnpacker
import org.msgpack.value.ValueType
import ru.lavafrai.openid.data.core.DataException
import ru.lavafrai.openid.data.core.requireData

internal object MaxWire {
    private const val MAX_DECOMPRESSED_BYTES = 16 * 1024 * 1024

    fun pack(value: Map<String, Any?>): ByteArray = MessagePack.newDefaultBufferPacker().use { packer ->
        write(packer, value)
        packer.toByteArray().also { requireData(it.size <= 8 * 1024 * 1024, "MAX_TCP_REQUEST_TOO_LARGE") }
    }

    private fun write(
        packer: MessagePacker,
        value: Any?,
    ) {
        when (value) {
            null -> packer.packNil()

            is Boolean -> packer.packBoolean(value)

            is String -> packer.packString(value)

            is Int -> packer.packInt(value)

            is Long -> packer.packLong(value)

            is Map<*, *> -> {
                packer.packMapHeader(value.size)
                value.forEach { (key, entryValue) ->
                    write(packer, key)
                    write(packer, entryValue)
                }
            }

            else -> throw DataException("MAX_TCP_REQUEST_INVALID")
        }
    }

    fun unpack(
        bytes: ByteArray,
        compression: Int,
    ): Map<Any?, Any?> {
        try {
            val data = if (compression == 0) {
                bytes
            } else {
                val output = ByteArray(MAX_DECOMPRESSED_BYTES)
                val count = LZ4Factory.safeInstance().safeDecompressor().decompress(
                    bytes,
                    0,
                    bytes.size,
                    output,
                    0,
                    MAX_DECOMPRESSED_BYTES,
                )
                output.copyOf(count).also { output.fill(0) }
            }
            requireData(data.size <= MAX_DECOMPRESSED_BYTES, "TCP_RESPONSE_TOO_LARGE")
            return MessagePack.newDefaultUnpacker(data).use { unpacker ->
                val value = Reader(unpacker).read(0)
                requireData(!unpacker.hasNext(), "TCP_RESPONSE_DECODE_FAILED")
                @Suppress("UNCHECKED_CAST")
                value as? Map<Any?, Any?> ?: throw DataException("TCP_RESPONSE_NOT_OBJECT")
            }
        } catch (failure: DataException) {
            throw failure
        } catch (failure: Exception) {
            throw DataException("TCP_RESPONSE_DECODE_FAILED", failure)
        }
    }

    private class Reader(private val unpacker: MessageUnpacker) {
        private var nodeCount = 0

        fun read(depth: Int): Any? {
            requireData(depth <= 32 && ++nodeCount <= 500_000, "TCP_RESPONSE_TOO_COMPLEX")
            return when (unpacker.nextFormat.valueType) {
                ValueType.NIL -> {
                    unpacker.unpackNil()
                    null
                }

                ValueType.BOOLEAN -> unpacker.unpackBoolean()

                ValueType.INTEGER -> unpacker.unpackLong()

                ValueType.FLOAT -> unpacker.unpackDouble().also {
                    requireData(it.isFinite(), "TCP_RESPONSE_INVALID_NUMBER")
                }

                ValueType.STRING -> {
                    val size = unpacker.unpackRawStringHeader()
                    requireData(size in 0..MAX_DECOMPRESSED_BYTES, "TCP_RESPONSE_TOO_LARGE")
                    Charsets.UTF_8.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(unpacker.readPayload(size)))
                        .toString()
                }

                ValueType.BINARY -> {
                    val size = unpacker.unpackBinaryHeader()
                    requireData(size in 0..MAX_DECOMPRESSED_BYTES, "TCP_RESPONSE_TOO_LARGE")
                    unpacker.readPayload(size)
                }

                ValueType.ARRAY -> {
                    val size = unpacker.unpackArrayHeader()
                    requireData(size in 0..500_000, "TCP_RESPONSE_TOO_COMPLEX")
                    List(size) { read(depth + 1) }
                }

                ValueType.MAP -> {
                    val size = unpacker.unpackMapHeader()
                    requireData(size in 0..250_000, "TCP_RESPONSE_TOO_COMPLEX")
                    val result = linkedMapOf<Any?, Any?>()
                    repeat(size) {
                        val key = read(depth + 1)
                        result[key] = read(depth + 1)
                    }
                    result
                }

                ValueType.EXTENSION -> {
                    val header = unpacker.unpackExtensionTypeHeader()
                    requireData(header.length in 0..MAX_DECOMPRESSED_BYTES, "TCP_RESPONSE_TOO_LARGE")
                    unpacker.readPayload(header.length)
                }
            }
        }
    }
}
