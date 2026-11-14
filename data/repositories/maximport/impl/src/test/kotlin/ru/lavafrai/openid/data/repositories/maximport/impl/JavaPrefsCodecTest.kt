package ru.lavafrai.openid.data.repositories.maximport.impl

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JavaPrefsCodecTest {
    private fun stringRecord(
        key: String,
        value: String,
    ): ByteArray {
        val keyBytes = key.toByteArray(Charsets.US_ASCII)
        val valueBytes = value.toByteArray(Charsets.US_ASCII)
        return byteArrayOf((keyBytes.size shr 8).toByte(), keyBytes.size.toByte()) + keyBytes +
            byteArrayOf(3, (valueBytes.size shr 8).toByte(), valueBytes.size.toByte()) + valueBytes
    }

    @Test
    fun `reads session records from binary prefs`() {
        val raw = stringRecord("auth.token", "synthetic-token") +
            byteArrayOf(0, 0) +
            stringRecord("device.id", "synthetic-device") +
            "noise".toByteArray()
        val records = JavaPrefsCodec.scan(raw, setOf("auth.token", "device.id"), setOf("digitalid-botid"))
        assertEquals(
            listOf(
                PrefRecord("auth.token", "synthetic-token"),
                PrefRecord("device.id", "synthetic-device"),
            ),
            records,
        )
    }

    @Test
    fun `reads numeric records`() {
        val key = "digitalid-botid".toByteArray(Charsets.US_ASCII)
        val raw = byteArrayOf(0, key.size.toByte()) + key + byteArrayOf(2) +
            byteArrayOf(0, 0, 0, 0, 0, 0, 0, 42.toByte())
        val records = JavaPrefsCodec.scan(raw, setOf("auth.token"), setOf("digitalid-botid"))
        assertEquals(listOf(PrefRecord("digitalid-botid", 42L)), records)
    }

    @Test
    fun `a string key rejects numeric records`() {
        val key = "auth.token".toByteArray(Charsets.US_ASCII)
        val raw = byteArrayOf(0, key.size.toByte()) + key + byteArrayOf(0, 1, 2, 3, 4)
        assertTrue(JavaPrefsCodec.scan(raw, setOf("auth.token"), emptySet()).isEmpty())
    }

    @Test
    fun `a numeric key rejects string records`() {
        val key = "digitalid-botid".toByteArray(Charsets.US_ASCII)
        val raw = stringRecord("digitalid-botid", "42")
        assertTrue(JavaPrefsCodec.scan(raw, emptySet(), setOf("digitalid-botid")).isEmpty())
    }

    @Test
    fun `ignores truncated records`() {
        val full = stringRecord("device.id", "abc")
        val records = JavaPrefsCodec.scan(full.copyOfRange(0, full.size - 1), setOf("device.id"), emptySet())
        assertTrue(records.isEmpty())
    }

    @Test
    fun `fragment scan finds biometry records`() {
        val raw = stringRecord("session.marker", "padding") + stringRecord("biometry.device_id::digitalId", "uuid")
        val records = JavaPrefsCodec.scanFragments(raw, setOf("biomet"))
        assertEquals(listOf(PrefRecord("biometry.device_id::digitalId", "uuid")), records)
    }

    @Test
    fun `fragment scan ignores keys without the fragment`() {
        val raw = stringRecord("auth.token", "synthetic-token")
        assertTrue(JavaPrefsCodec.scanFragments(raw, setOf("biomet")).isEmpty())
    }

    @Test
    fun `decodes cesu8 escapes`() {
        assertNull(Cesu8.decode(byteArrayOf(0xC0.toByte(), 0x01), 0, 2))
        assertEquals("\u0000", Cesu8.decode(byteArrayOf(0xC0.toByte(), 0x80.toByte()), 0, 2))
        assertNull(Cesu8.decode(byteArrayOf(0xE0.toByte()), 0, 1))
    }
}
