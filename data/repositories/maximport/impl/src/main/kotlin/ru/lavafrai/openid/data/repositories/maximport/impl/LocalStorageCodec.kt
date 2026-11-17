package ru.lavafrai.openid.data.repositories.maximport.impl

import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * Scans Chromium WebView levelDB files for mini-app local storage entries. Supports both
 * the write-ahead log format (Put/Delete records in WriteBatch encoding) and the SSTable
 * format (data blocks with shared-prefix-compressed keys, Snappy or no compression).
 *
 * The stored key is `_` + origin + 0x00 + 0x01 + key; the payload is an encoding byte
 * (0x00 for UTF-16LE, 0x01 for Latin-1) followed by the text.
 */
internal object LocalStorageCodec {
    private const val MAX_VALUE_LENGTH = 65_536
    private const val MAX_BLOCK_SIZE = 4 * 1024 * 1024

    private val SSTABLE_MAGIC = byteArrayOf(
        0x57,
        0xfb.toByte(),
        0x80.toByte(),
        0x8b.toByte(),
        0x24,
        0x75,
        0x47,
        0xdb.toByte(),
    )

    fun scan(
        raw: ByteArray,
        keys: Set<String>,
        origin: String = "https://digital-id.max.ru",
    ): Map<String, String> {
        val originPrefix = byteArrayOf(0x5F) + origin.toByteArray(Charsets.US_ASCII) + byteArrayOf(0x00, 0x01)
        val found = mutableMapOf<String, String>()
        // An SSTable file and a WAL/log file are distinct, self-contained formats. An SSTable
        // resolves each key authoritatively (to a value or to a tombstone), so the raw log scan
        // must NOT also run over it: doing so would let the byte-level search resurrect a key the
        // table marked deleted. Only a file without an SSTable footer is treated as a WAL log.
        if (isSsTable(raw)) scanSsTable(raw, originPrefix, keys, found) else scanLog(raw, originPrefix, keys, found)
        return found
    }

    // ---- WAL / log scan (original implementation) ----

    private fun scanLog(
        raw: ByteArray,
        originPrefix: ByteArray,
        keys: Set<String>,
        found: MutableMap<String, String>,
    ) {
        for (key in keys) {
            if (key in found) continue
            val pattern = originPrefix + key.toByteArray(Charsets.US_ASCII)
            var index = raw.indexOf(pattern, 0)
            var lastValuePosition = -1
            var lastMatchPosition = -1
            var lastValue: String? = null
            while (index >= 0) {
                val value = readLogValue(raw, index + pattern.size)
                lastMatchPosition = index
                if (value != null) {
                    lastValuePosition = index
                    lastValue = value
                }
                index = raw.indexOf(pattern, index + 1)
            }
            if (lastValue != null && lastValuePosition == lastMatchPosition) {
                found[key] = lastValue
            }
        }
    }

    private fun readLogValue(
        raw: ByteArray,
        start: Int,
    ): String? {
        var position = start
        var length = 0
        var shift = 0
        while (shift < 5) {
            if (position >= raw.size) return null
            val byte = raw[position].toInt() and 0xFF
            position++
            length = length or ((byte and 0x7F) shl (7 * shift))
            if (byte and 0x80 == 0) break
            shift++
        }
        if (shift >= 5 || length <= 0 || length > MAX_VALUE_LENGTH || position + length > raw.size) return null
        return decodePayload(raw, position, length)
    }

    // ---- SSTable scan ----

    private fun isSsTable(raw: ByteArray): Boolean {
        if (raw.size < 48) return false
        val offset = raw.size - 8
        return SSTABLE_MAGIC.indices.all { raw[offset + it] == SSTABLE_MAGIC[it] }
    }

    private fun scanSsTable(
        raw: ByteArray,
        originPrefix: ByteArray,
        keys: Set<String>,
        found: MutableMap<String, String>,
    ) {
        val remaining = keys - found.keys
        if (remaining.isEmpty()) return
        val targetPatterns = remaining.associateWith { originPrefix + it.toByteArray(Charsets.US_ASCII) }
        val footerStart = raw.size - 48

        var pos = footerStart
        val (_, ml1) = readVarLong(raw, pos) ?: return
        pos += ml1
        val (_, ml2) = readVarLong(raw, pos) ?: return
        pos += ml2
        val (indexOffset, il1) = readVarLong(raw, pos) ?: return
        pos += il1
        val (indexSize, _) = readVarLong(raw, pos) ?: return
        val idxOff = indexOffset.toInt()
        val idxSz = indexSize.toInt()
        if (idxOff < 0 || idxSz < 4 || idxOff.toLong() + idxSz > footerStart) return

        val dataBlocks = mutableListOf<Pair<Long, Long>>()
        parseBlock(readBlock(raw, idxOff, idxSz)) { _, value ->
            var vp = 0
            val (off, l1) = readVarLong(value, vp) ?: return@parseBlock
            vp += l1
            val (sz, _) = readVarLong(value, vp) ?: return@parseBlock
            dataBlocks += off to sz
        }

        // Within an SSTable, entries are ordered by user key ascending and, for equal user keys,
        // by sequence number descending (newest first). The first entry seen for a user key is
        // therefore the current one: if it is a deletion (type 0) the key is gone and no older Put
        // may be returned; if it is a Put (type 1) its value wins. `resolved` locks that decision
        // on first sighting so a later, older record for the same key cannot override it.
        val resolved = mutableSetOf<String>()
        for ((blockOff, blockSz) in dataBlocks) {
            val off = blockOff.toInt()
            val sz = blockSz.toInt()
            if (off < 0 || sz < 4 || blockOff + blockSz > footerStart) continue
            val block = readBlock(raw, off, sz) ?: continue
            parseBlock(block) { key, value ->
                if (key.size < 8) return@parseBlock
                val typeTag = key[key.size - 8].toInt() and 0xFF
                val userKeyLen = key.size - 8
                for ((name, pattern) in targetPatterns) {
                    if (name in resolved || name in found) continue
                    if (userKeyLen == pattern.size && prefixMatches(key, pattern)) {
                        resolved += name
                        if (typeTag == 1) decodePayload(value, 0, value.size)?.let { found[name] = it }
                    }
                }
            }
        }
    }

    private fun readBlock(
        raw: ByteArray,
        offset: Int,
        size: Int,
    ): ByteArray? {
        if (size < 4 || offset + size >= raw.size) return null
        val compression = raw[offset + size].toInt() and 0xFF
        return when (compression) {
            0x00 -> raw.copyOfRange(offset, offset + size)
            0x01 -> snappyDecompress(raw, offset, size)
            else -> null
        }
    }

    private fun parseBlock(
        block: ByteArray?,
        visitor: (key: ByteArray, value: ByteArray) -> Unit,
    ) {
        if (block == null || block.size < 4) return
        val numRestarts = readInt32LE(block, block.size - 4)
        if (numRestarts < 0 || numRestarts > block.size / 4) return
        val entriesEnd = block.size - 4 - numRestarts * 4
        if (entriesEnd < 0) return

        var pos = 0
        var currentKey = ByteArray(0)
        while (pos < entriesEnd) {
            val (shared, sl) = readVarInt(block, pos) ?: return
            pos += sl
            val (nonShared, nsl) = readVarInt(block, pos) ?: return
            pos += nsl
            val (valueLen, vl) = readVarInt(block, pos) ?: return
            pos += vl
            if (shared < 0 || nonShared < 0 || valueLen < 0) return
            if (shared > currentKey.size) return
            if (pos + nonShared + valueLen > entriesEnd) return

            val key = ByteArray(shared + nonShared)
            if (shared > 0) System.arraycopy(currentKey, 0, key, 0, shared)
            System.arraycopy(block, pos, key, shared, nonShared)
            pos += nonShared
            val value = block.copyOfRange(pos, pos + valueLen)
            pos += valueLen
            currentKey = key
            visitor(key, value)
        }
    }

    // ---- Shared value decoding ----

    private fun decodePayload(
        raw: ByteArray,
        start: Int,
        length: Int,
    ): String? {
        if (length <= 0 || start + length > raw.size) return null
        val encoding = raw[start].toInt() and 0xFF
        return when (encoding) {
            0x00 -> if ((length - 1) % 2 == 0) {
                decode(raw.copyOfRange(start + 1, start + length), Charsets.UTF_16LE)
            } else {
                null
            }

            0x01 -> decode(raw.copyOfRange(start + 1, start + length), Charsets.ISO_8859_1)

            else -> null
        }?.takeIf { it.isNotEmpty() }
    }

    private fun decode(
        bytes: ByteArray,
        charset: Charset,
    ): String? = try {
        charset.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (_: Exception) {
        null
    }

    // ---- Snappy decompressor ----

    private fun snappyDecompress(
        input: ByteArray,
        start: Int,
        length: Int,
    ): ByteArray? {
        val end = start + length
        var pos = start
        var uncompressedLen = 0
        var shift = 0
        while (pos < end && shift < 32) {
            val b = input[pos].toInt() and 0xFF
            pos++
            uncompressedLen = uncompressedLen or ((b and 0x7F) shl shift)
            if (b and 0x80 == 0) break
            shift += 7
        }
        if (uncompressedLen <= 0 || uncompressedLen > MAX_BLOCK_SIZE) return null
        val output = ByteArray(uncompressedLen)
        var out = 0
        while (pos < end && out < uncompressedLen) {
            val tag = input[pos].toInt() and 0xFF
            pos++
            when (tag and 0x03) {
                0 -> {
                    var litLen = (tag ushr 2) + 1
                    when {
                        litLen <= 60 -> {}

                        litLen == 61 -> {
                            if (pos >= end) return null
                            litLen = (input[pos].toInt() and 0xFF) + 1
                            pos++
                        }

                        litLen == 62 -> {
                            if (pos + 1 >= end) return null
                            litLen = leU16(input, pos) + 1
                            pos += 2
                        }

                        litLen == 63 -> {
                            if (pos + 2 >= end) return null
                            litLen = leU24(input, pos) + 1
                            pos += 3
                        }

                        else -> {
                            if (pos + 3 >= end) return null
                            litLen = readInt32LE(input, pos) + 1
                            pos += 4
                        }
                    }
                    if (litLen <= 0 || pos + litLen > end || out + litLen > uncompressedLen) return null
                    System.arraycopy(input, pos, output, out, litLen)
                    pos += litLen
                    out += litLen
                }

                1 -> {
                    val len = ((tag ushr 2) and 7) + 4
                    if (pos >= end) return null
                    val offset = ((tag and 0xE0) ushr 5 shl 8) or (input[pos].toInt() and 0xFF)
                    pos++
                    if (offset == 0 || offset > out || out + len > uncompressedLen) return null
                    for (i in 0 until len) output[out + i] = output[out - offset + i]
                    out += len
                }

                2 -> {
                    val len = (tag ushr 2) + 1
                    if (pos + 1 >= end) return null
                    val offset = leU16(input, pos)
                    pos += 2
                    if (offset == 0 || offset > out || out + len > uncompressedLen) return null
                    for (i in 0 until len) output[out + i] = output[out - offset + i]
                    out += len
                }

                3 -> {
                    val len = (tag ushr 2) + 1
                    if (pos + 3 >= end) return null
                    val offset = readInt32LE(input, pos)
                    pos += 4
                    if (offset <= 0 || offset > out || out + len > uncompressedLen) return null
                    for (i in 0 until len) output[out + i] = output[out - offset + i]
                    out += len
                }
            }
        }
        return if (out == uncompressedLen) output else null
    }

    // ---- Helpers ----

    private fun readVarLong(
        raw: ByteArray,
        start: Int,
    ): Pair<Long, Int>? {
        var result = 0L
        var shift = 0
        var pos = start
        while (shift < 63 && pos < raw.size) {
            val byte = raw[pos].toInt() and 0xFF
            pos++
            result = result or ((byte.toLong() and 0x7F) shl shift)
            if (byte and 0x80 == 0) return result to (pos - start)
            shift += 7
        }
        return null
    }

    private fun readVarInt(
        raw: ByteArray,
        start: Int,
    ): Pair<Int, Int>? {
        var result = 0
        var shift = 0
        var pos = start
        while (shift < 35 && pos < raw.size) {
            val byte = raw[pos].toInt() and 0xFF
            pos++
            result = result or ((byte and 0x7F) shl shift)
            if (byte and 0x80 == 0) return result to (pos - start)
            shift += 7
        }
        return null
    }

    private fun readInt32LE(
        raw: ByteArray,
        start: Int,
    ): Int {
        if (start + 4 > raw.size) return -1
        return (raw[start].toInt() and 0xFF) or
            ((raw[start + 1].toInt() and 0xFF) shl 8) or
            ((raw[start + 2].toInt() and 0xFF) shl 16) or
            ((raw[start + 3].toInt() and 0xFF) shl 24)
    }

    private fun leU16(
        raw: ByteArray,
        start: Int,
    ): Int = (raw[start].toInt() and 0xFF) or ((raw[start + 1].toInt() and 0xFF) shl 8)

    private fun leU24(
        raw: ByteArray,
        start: Int,
    ): Int = (raw[start].toInt() and 0xFF) or
        ((raw[start + 1].toInt() and 0xFF) shl 8) or
        ((raw[start + 2].toInt() and 0xFF) shl 16)

    private fun prefixMatches(
        data: ByteArray,
        pattern: ByteArray,
    ): Boolean {
        if (data.size < pattern.size) return false
        for (i in pattern.indices) {
            if (data[i] != pattern[i]) return false
        }
        return true
    }

    private fun ByteArray.indexOf(
        pattern: ByteArray,
        from: Int,
    ): Int {
        if (pattern.isEmpty() || from < 0) return -1
        var index = from
        while (index <= size - pattern.size) {
            var offset = 0
            while (offset < pattern.size && this[index + offset] == pattern[offset]) offset++
            if (offset == pattern.size) return index
            index++
        }
        return -1
    }
}
