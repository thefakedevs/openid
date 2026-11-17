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

    private val saltKey = "pin_salt::digitalId"

    private fun lengthPrefixed(bytes: ByteArray) = varint(bytes.size) + bytes

    /** MANIFEST in LevelDB log format: one FULL record holding a VersionEdit with a log number and live tables. */
    private fun manifest(
        logNumber: Long,
        vararg tables: Long,
    ): ByteArray {
        var edit = varint(2) + varint64(logNumber)
        for (table in tables) {
            edit += varint(7) + varint(0) + varint64(table) + varint64(100) +
                lengthPrefixed(byteArrayOf(1)) + lengthPrefixed(byteArrayOf(2))
        }
        val header = ByteArray(4) + byteArrayOf((edit.size and 0xFF).toByte(), (edit.size ushr 8).toByte(), 1)
        return header + edit
    }

    private fun directory(
        logNumber: Long,
        vararg files: Pair<String, ByteArray>,
        liveTables: List<Long>,
    ): List<Pair<String, ByteArray>> = listOf(
        "/db/CURRENT" to "MANIFEST-000001\n".toByteArray(),
        "/db/MANIFEST-000001" to manifest(logNumber, *liveTables.toLongArray()),
    ) + files

    private fun stateOf(files: List<Pair<String, ByteArray>>): Map<String, String> {
        val state = LocalStorageCodec.State(setOf(saltKey))
        files.forEach { (path, raw) -> state.add(path, raw) }
        return state.values()
    }

    @Test
    fun `log write newer than a table wins in either file order`() {
        val table = "/db/000005.ldb" to buildSsTable(saltKey to "old-salt")
        val log = "/db/000008.log" to entry(saltKey, "new-salt")
        assertEquals("new-salt", stateOf(directory(8, table, log, liveTables = listOf(5)))[saltKey])
        assertEquals("new-salt", stateOf(directory(8, log, table, liveTables = listOf(5)))[saltKey])
    }

    @Test
    fun `log deletion hides an older table value in either file order`() {
        val table = "/db/000005.ldb" to buildSsTable(saltKey to "old-salt")
        val tombstone = "/db/000008.log" to
            (originPrefix + saltKey.toByteArray(Charsets.US_ASCII) + byteArrayOf(0xFF.toByte()))
        assertTrue(stateOf(directory(8, table, tombstone, liveTables = listOf(5))).isEmpty())
        assertTrue(stateOf(directory(8, tombstone, table, liveTables = listOf(5))).isEmpty())
    }

    @Test
    fun `stale log below the manifest log number is ignored`() {
        val table = "/db/000005.ldb" to buildSsTable(saltKey to "new-salt")
        val stale = "/db/000003.log" to entry(saltKey, "old-salt")
        val live = "/db/000009.log" to ByteArray(0)
        assertEquals("new-salt", stateOf(directory(9, stale, table, live, liveTables = listOf(5)))[saltKey])
    }

    @Test
    fun `obsolete table missing from the manifest is ignored`() {
        val obsolete = "/db/000004.ldb" to buildSsTable(saltKey to "old-salt")
        val table = "/db/000006.ldb" to buildSsTable(saltKey to "salt")
        assertEquals("salt", stateOf(directory(8, obsolete, table, liveTables = listOf(6)))[saltKey])
    }

    @Test
    fun `state is untrusted without a readable manifest or a live table`() {
        val table = "/db/000005.ldb" to buildSsTable(saltKey to "salt")
        assertTrue(stateOf(listOf(table)).isEmpty())
        assertTrue(stateOf(directory(8, table, liveTables = listOf(5, 7))).isEmpty())
        val broken = listOf(
            "/db/CURRENT" to "MANIFEST-000001".toByteArray(),
            "/db/MANIFEST-000001" to byteArrayOf(1, 2, 3),
        )
        assertTrue(stateOf(broken + table).isEmpty())
    }

    @Test
    fun `skipped file makes the merged state untrusted`() {
        val state = LocalStorageCodec.State(setOf(saltKey))
        directory(8, "/db/000005.ldb" to buildSsTable(saltKey to "old-salt"), liveTables = listOf(5))
            .forEach { (path, raw) -> state.add(path, raw) }
        assertEquals("old-salt", state.values()[saltKey])
        state.markIncomplete()
        assertTrue(state.values().isEmpty())
    }

    @Test
    fun `corrupt live table makes the state untrusted`() {
        val good = buildSsTable(saltKey to "salt")
        val corrupt = good.copyOf().also { it[0] = 0x7F }
        assertTrue(stateOf(directory(8, "/db/000005.ldb" to corrupt, liveTables = listOf(5))).isEmpty())
    }

    @Test
    fun `sstable with overflowing entry lengths is rejected`() {
        // shared = 0, nonShared = 1, valueLen = Int.MAX_VALUE: nonShared + valueLen wraps negative in Int.
        val entry = byteArrayOf(0, 1, 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0x07, 0x41)
        val block = entry + int32LE(0) + int32LE(1)
        val indexValue = varint64(0) + varint64(block.size.toLong())
        val index = buildDataBlock(listOf(byteArrayOf(1) to indexValue))
        val indexOffset = block.size + 5
        val raw = block + byteArrayOf(0, 0, 0, 0, 0) + index + byteArrayOf(0, 0, 0, 0, 0) +
            buildFooter(0, 0, indexOffset.toLong(), index.size.toLong())
        assertTrue(LocalStorageCodec.scan(raw, setOf(saltKey)).isEmpty())
        assertEquals(null, LocalStorageCodec.scanVersioned(raw, setOf(saltKey), logNumber = 0))
    }

    private fun logRecord(
        type: Int,
        payload: ByteArray,
    ) = ByteArray(4) + byteArrayOf((payload.size and 0xFF).toByte(), (payload.size ushr 8).toByte(), type.toByte()) +
        payload

    private fun newFileEdit(number: Long) = varint(7) + varint(0) + varint64(number) + varint64(100) +
        lengthPrefixed(byteArrayOf(1)) + lengthPrefixed(byteArrayOf(2))

    @Test
    fun `manifest reassembles fragmented records and applies deletions`() {
        val edit = varint(2) + varint64(9) + newFileEdit(5) + newFileEdit(6) + varint(6) + varint(0) + varint64(5)
        val third = edit.size / 3
        val fragmented = logRecord(2, edit.copyOfRange(0, third)) +
            logRecord(3, edit.copyOfRange(third, 2 * third)) +
            logRecord(4, edit.copyOfRange(2 * third, edit.size))
        val live = LevelDbManifest.parse(fragmented)
        assertEquals(9L, live?.logNumber)
        assertEquals(setOf(6L), live?.tables)
    }

    @Test
    fun `manifest skips block tail padding and rejects truncated or unknown input`() {
        val first = logRecord(1, varint(2) + varint64(4) + newFileEdit(3))
        val padded = first + ByteArray(32_768 - first.size) + logRecord(1, newFileEdit(7))
        assertEquals(setOf(3L, 7L), LevelDbManifest.parse(padded)?.tables)
        assertEquals(null, LevelDbManifest.parse(first.copyOf(first.size - 3)))
        assertEquals(null, LevelDbManifest.parse(logRecord(1, varint(2) + varint64(4) + varint(99))))
        assertEquals(null, LevelDbManifest.parse(logRecord(1, newFileEdit(3))))
    }

    @Test
    fun `merges a real leveldb whose newer state lives in the log and ignores a stale log`() {
        val dir = Files.createTempDirectory("leveldb-state").toFile()
        try {
            val key = originPrefix + saltKey.toByteArray(Charsets.US_ASCII)
            val options = Options().createIfMissing(true).compressionType(CompressionType.NONE)

            fun files() = dir.listFiles().orEmpty().filter { it.isFile }.map {
                it.path.replace('\\', '/') to
                    it.readBytes()
            }
            Iq80DBFactory.factory.open(dir, options).use { it.put(key, byteArrayOf(0x01) + "old-salt".toByteArray()) }
            val staleLog = files().first { it.first.endsWith(".log") }
            Iq80DBFactory.factory.open(dir, options).use { it.put(key, byteArrayOf(0x01) + "new-salt".toByteArray()) }
            assertTrue(files().any { it.first.endsWith(".ldb") || it.first.endsWith(".sst") })
            assertEquals("new-salt", stateOf(files())[saltKey])
            assertEquals("new-salt", stateOf(files().reversed())[saltKey])
            // A log left behind by an interrupted cleanup must not override newer tables.
            assertEquals("new-salt", stateOf(files() + staleLog)[saltKey])

            Iq80DBFactory.factory.open(dir, options).use { it.delete(key) }
            assertTrue(stateOf(files()).isEmpty())
            assertTrue(stateOf(files().reversed()).isEmpty())
            assertTrue(stateOf(files() + staleLog).isEmpty())
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
