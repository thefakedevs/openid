package ru.lavafrai.openid.data.repositories.maximport.impl

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.iq80.leveldb.CompressionType
import org.iq80.leveldb.Options
import org.iq80.leveldb.impl.Iq80DBFactory

class LocalStorageCodecTest {
    private companion object {
        const val TABLE_MAGIC_NUMBER = -0x24b88adb747f04a9L // 0xdb4775248b80fb57
    }

    private val originPrefix: ByteArray =
        byteArrayOf(0x5F) + "https://digital-id.max.ru".toByteArray(Charsets.US_ASCII) + byteArrayOf(0x00, 0x01)

    private fun entry(
        key: String,
        value: String,
    ): ByteArray {
        val keyBytes = originPrefix + key.toByteArray(Charsets.US_ASCII)
        val payload = byteArrayOf(0x01) + value.toByteArray(Charsets.ISO_8859_1)
        return keyBytes + varint(payload.size) + payload
    }

    private fun varint(value: Int): ByteArray {
        var remaining = value
        val bytes = mutableListOf<Byte>()
        while (true) {
            var byte = remaining and 0x7F
            remaining = remaining shr 7
            if (remaining != 0) byte = byte or 0x80
            bytes += byte.toByte()
            if (remaining == 0) return bytes.toByteArray()
        }
    }

    @Test
    fun `reads latin1 values`() {
        val raw = byteArrayOf(1, 2, 3) + entry("pin_salt::digitalId", "the-salt") + byteArrayOf(9, 9)
        val values = LocalStorageCodec.scan(raw, setOf("pin_salt::digitalId"))
        assertEquals("the-salt", values["pin_salt::digitalId"])
    }

    @Test
    fun `reads utf16le values`() {
        val keyBytes = originPrefix + "creation_timestamp::digitalId".toByteArray(Charsets.US_ASCII)
        val text = "1735689600000"
        val payload = byteArrayOf(0x00) + text.toByteArray(Charsets.UTF_16LE)
        val raw = keyBytes + varint(payload.size) + payload
        val values = LocalStorageCodec.scan(raw, setOf("creation_timestamp::digitalId"))
        assertEquals(text, values["creation_timestamp::digitalId"])
    }

    @Test
    fun `unknown keys are not found`() {
        val raw = entry("pin_salt::digitalId", "the-salt")
        assertTrue(LocalStorageCodec.scan(raw, setOf("other::key")).isEmpty())
    }

    @Test
    fun `garbage does not break the scan`() {
        val raw = ByteArray(512) { (it * 31 % 251).toByte() }
        assertTrue(LocalStorageCodec.scan(raw, setOf("pin_salt::digitalId")).isEmpty())
    }

    @Test
    fun `later put overwrites earlier put`() {
        val raw = entry("pin_salt::digitalId", "old-salt") + entry("pin_salt::digitalId", "new-salt")
        val values = LocalStorageCodec.scan(raw, setOf("pin_salt::digitalId"))
        assertEquals("new-salt", values["pin_salt::digitalId"])
    }

    @Test
    fun `key after last value is treated as deleted`() {
        val putRecord = entry("pin_salt::digitalId", "old-salt")
        val tombstone = originPrefix + "pin_salt::digitalId".toByteArray(Charsets.US_ASCII) + byteArrayOf(0xFF.toByte())
        val raw = putRecord + tombstone
        assertTrue(LocalStorageCodec.scan(raw, setOf("pin_salt::digitalId")).isEmpty())
    }

    @Test
    fun `reads values from an uncompressed sstable`() {
        val raw = buildSsTable("pin_salt::digitalId" to "the-salt")
        val values = LocalStorageCodec.scan(raw, setOf("pin_salt::digitalId"))
        assertEquals("the-salt", values["pin_salt::digitalId"])
    }

    @Test
    fun `reads multiple keys from sstable`() {
        val raw = buildSsTable(
            "creation_timestamp::digitalId" to "1735689600000",
            "pin_salt::digitalId" to "the-salt",
        )
        val values = LocalStorageCodec.scan(raw, setOf("pin_salt::digitalId", "creation_timestamp::digitalId"))
        assertEquals("the-salt", values["pin_salt::digitalId"])
        assertEquals("1735689600000", values["creation_timestamp::digitalId"])
    }

    @Test
    fun `sstable delete entry is not returned`() {
        val raw = buildSsTableWithDelete("pin_salt::digitalId")
        assertTrue(LocalStorageCodec.scan(raw, setOf("pin_salt::digitalId")).isEmpty())
    }

    @Test
    fun `sstable newer delete suppresses older put`() {
        val raw = buildSsTablePutThenDelete("pin_salt::digitalId", "stale-salt")
        assertTrue(LocalStorageCodec.scan(raw, setOf("pin_salt::digitalId")).isEmpty())
    }

    @Test
    fun `sstable footer magic matches the leveldb format`() {
        val footer = buildSsTable("pin_salt::digitalId" to "x").takeLast(8).map { it.toInt() and 0xFF }
        assertEquals(listOf(0x57, 0xfb, 0x80, 0x8b, 0x24, 0x75, 0x47, 0xdb), footer)
    }

    @Test
    fun `reads a real sstable written by leveldb`() {
        val dir = Files.createTempDirectory("leveldb-sst").toFile()
        try {
            val key = originPrefix + "pin_salt::digitalId".toByteArray(Charsets.US_ASCII)
            val value = byteArrayOf(0x01) + "real-salt".toByteArray(Charsets.ISO_8859_1)
            val options = Options().createIfMissing(true).compressionType(CompressionType.NONE)
            Iq80DBFactory.factory.open(dir, options).use { db -> db.put(key, value) }
            // Reopening replays the log into a level-0 table file, so the entry ends up in a real
            // SSTable rather than only in the write-ahead log.
            Iq80DBFactory.factory.open(dir, options).close()
            val tables = dir.listFiles { file -> file.name.endsWith(".sst") || file.name.endsWith(".ldb") }
                .orEmpty()
                .filter { it.length() > 0 }
            assertTrue(tables.isNotEmpty(), "leveldb did not produce a table file")
            val values = tables
                .map { LocalStorageCodec.scan(it.readBytes(), setOf("pin_salt::digitalId")) }
                .firstOrNull { it.isNotEmpty() }
            assertEquals("real-salt", values?.get("pin_salt::digitalId"))
        } finally {
            dir.deleteRecursively()
        }
    }

    /** Builds a minimal SSTable with uncompressed data/index blocks and a valid footer. */
    private fun buildSsTable(vararg entries: Pair<String, String>): ByteArray {
        val sorted = entries.sortedBy { it.first }
        val dataBlock = buildDataBlock(
            sorted.map { (key, value) ->
                val userKey = originPrefix + key.toByteArray(Charsets.US_ASCII)
                val internalKey = userKey + packSequenceType(1L, 1)
                val payload = byteArrayOf(0x01) + value.toByteArray(Charsets.ISO_8859_1)
                internalKey to payload
            },
        )
        val dataBlockOffset = 0
        val dataBlockSize = dataBlock.size
        val indexBlock = buildDataBlock(
            listOf(
                dataBlock.let {
                    val lastUserKey = originPrefix + sorted.last().first.toByteArray(Charsets.US_ASCII)
                    val separatorKey = lastUserKey + packSequenceType(1L, 1)
                    separatorKey to (varint64(dataBlockOffset.toLong()) + varint64(dataBlockSize.toLong()))
                },
            ),
        )
        val indexBlockOffset = dataBlockSize + 5
        val indexBlockSize = indexBlock.size
        val footer = buildFooter(0, 0, indexBlockOffset.toLong(), indexBlockSize.toLong())
        return dataBlock + byteArrayOf(0x00, 0, 0, 0, 0) +
            indexBlock + byteArrayOf(0x00, 0, 0, 0, 0) +
            footer
    }

    private fun buildSsTableWithDelete(key: String): ByteArray {
        val userKey = originPrefix + key.toByteArray(Charsets.US_ASCII)
        val internalKey = userKey + packSequenceType(1L, 0)
        val dataBlock = buildDataBlock(listOf(internalKey to ByteArray(0)))
        val dataBlockOffset = 0
        val dataBlockSize = dataBlock.size
        val separatorKey = userKey + packSequenceType(1L, 0)
        val indexBlock = buildDataBlock(
            listOf(
                separatorKey to (varint64(dataBlockOffset.toLong()) + varint64(dataBlockSize.toLong())),
            ),
        )
        val indexBlockOffset = dataBlockSize + 5
        val indexBlockSize = indexBlock.size
        val footer = buildFooter(0, 0, indexBlockOffset.toLong(), indexBlockSize.toLong())
        return dataBlock + byteArrayOf(0x00, 0, 0, 0, 0) +
            indexBlock + byteArrayOf(0x00, 0, 0, 0, 0) +
            footer
    }

    /** Same user key twice, newest first: a deletion at seq 2 ahead of the older put at seq 1. */
    private fun buildSsTablePutThenDelete(
        key: String,
        value: String,
    ): ByteArray {
        val userKey = originPrefix + key.toByteArray(Charsets.US_ASCII)
        val deleteKey = userKey + packSequenceType(2L, 0)
        val putKey = userKey + packSequenceType(1L, 1)
        val putPayload = byteArrayOf(0x01) + value.toByteArray(Charsets.ISO_8859_1)
        val dataBlock = buildDataBlock(listOf(deleteKey to ByteArray(0), putKey to putPayload))
        val dataBlockOffset = 0
        val dataBlockSize = dataBlock.size
        val separatorKey = userKey + packSequenceType(2L, 0)
        val indexBlock = buildDataBlock(
            listOf(
                separatorKey to (varint64(dataBlockOffset.toLong()) + varint64(dataBlockSize.toLong())),
            ),
        )
        val indexBlockOffset = dataBlockSize + 5
        val indexBlockSize = indexBlock.size
        val footer = buildFooter(0, 0, indexBlockOffset.toLong(), indexBlockSize.toLong())
        return dataBlock + byteArrayOf(0x00, 0, 0, 0, 0) +
            indexBlock + byteArrayOf(0x00, 0, 0, 0, 0) +
            footer
    }

    private fun buildDataBlock(entries: List<Pair<ByteArray, ByteArray>>): ByteArray {
        val buf = mutableListOf<Byte>()
        val restarts = mutableListOf<Int>()
        var prevKey = ByteArray(0)
        for ((i, pair) in entries.withIndex()) {
            val (key, value) = pair
            restarts += buf.size
            buf += varint(0).toList()
            buf += varint(key.size).toList()
            buf += varint(value.size).toList()
            buf += key.toList()
            buf += value.toList()
            prevKey = key
        }
        for (r in restarts) {
            buf += int32LE(r).toList()
        }
        buf += int32LE(restarts.size).toList()
        return buf.toByteArray()
    }

    private fun buildFooter(
        metaOffset: Long,
        metaSize: Long,
        indexOffset: Long,
        indexSize: Long,
    ): ByteArray {
        val handles = varint64(metaOffset) + varint64(metaSize) + varint64(indexOffset) + varint64(indexSize)
        val padding = ByteArray(40 - handles.size)
        // kTableMagicNumber from leveldb/doc/table_format.md, stored little-endian; spelled out
        // independently of the implementation's constant so a wrong constant cannot pass the tests.
        val magic = ByteArray(8) { i -> ((TABLE_MAGIC_NUMBER ushr (i * 8)) and 0xFF).toByte() }
        return handles + padding + magic
    }

    private fun packSequenceType(
        sequence: Long,
        type: Int,
    ): ByteArray {
        val packed = (sequence shl 8) or type.toLong()
        return ByteArray(8) { i -> ((packed ushr (i * 8)) and 0xFF).toByte() }
    }

    private fun varint64(value: Long): ByteArray {
        var remaining = value
        val bytes = mutableListOf<Byte>()
        while (true) {
            var byte = (remaining and 0x7F).toInt()
            remaining = remaining ushr 7
            if (remaining != 0L) byte = byte or 0x80
            bytes += byte.toByte()
            if (remaining == 0L) return bytes.toByteArray()
        }
    }

    private fun int32LE(value: Int): ByteArray = byteArrayOf(
        (value and 0xFF).toByte(),
        ((value ushr 8) and 0xFF).toByte(),
        ((value ushr 16) and 0xFF).toByte(),
        ((value ushr 24) and 0xFF).toByte(),
    )
}

class RawFileScannerTest {
    @Test
    fun `extracts the envelope after the key`() {
        val raw = (
            "prefix bytes \u0000\u0007" + "pin_salt::digitalId" + "\u0000{\"data\":\"abc\",\"ttl\":0}" +
                "tail"
            ).toByteArray(Charsets.UTF_8)
        assertEquals(listOf("""{"data":"abc","ttl":0}"""), RawFileScanner.envelopes(raw, "pin_salt::digitalId"))
    }

    @Test
    fun `braces inside strings do not break balance`() {
        val raw = ("pin_salt::digitalId" + "{\"data\":\"a}b\",\"ttl\":0}rest").toByteArray(Charsets.UTF_8)
        assertEquals(
            listOf("""{"data":"a}b","ttl":0}"""),
            RawFileScanner.envelopes(raw, "pin_salt::digitalId"),
        )
    }

    @Test
    fun `missing key yields nothing`() {
        val raw = "{\"data\":\"abc\"}".toByteArray(Charsets.UTF_8)
        assertTrue(RawFileScanner.envelopes(raw, "pin_salt::digitalId").isEmpty())
    }
}
