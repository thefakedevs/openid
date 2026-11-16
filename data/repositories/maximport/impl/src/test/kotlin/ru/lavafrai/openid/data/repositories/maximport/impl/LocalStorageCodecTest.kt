package ru.lavafrai.openid.data.repositories.maximport.impl

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LocalStorageCodecTest {
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
