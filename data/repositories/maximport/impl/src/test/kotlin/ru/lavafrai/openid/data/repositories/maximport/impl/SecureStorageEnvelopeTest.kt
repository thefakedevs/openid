package ru.lavafrai.openid.data.repositories.maximport.impl

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import ru.lavafrai.openid.data.core.StrictJson

class SecureStorageEnvelopeTest {
    private val now = 1_700_000_000_000L

    @Test
    fun `unwraps the data field`() {
        assertEquals(
            "salt-value",
            SecureStorageEnvelope.unwrap("""{"data":"salt-value","ttl":0,"timestamp":1}""", now),
        )
    }

    @Test
    fun `accepts numeric data`() {
        val value = SecureStorageEnvelope.unwrap("""{"data":1234567890}""", now)
        assertEquals(1234567890L, (value as? StrictJson.Number)?.long())
    }

    @Test
    fun `expired ttl reads as null`() {
        assertNull(
            SecureStorageEnvelope.unwrap(
                """{"data":"x","ttl":1000,"timestamp":${now - 5_000}}""",
                now,
            ),
        )
    }

    @Test
    fun `ttl inside the window survives`() {
        assertEquals(
            "x",
            SecureStorageEnvelope.unwrap("""{"data":"x","ttl":10000,"timestamp":${now - 5000}}""", now),
        )
    }

    @Test
    fun `seconds timestamps normalize to millis`() {
        val seconds = now / 1000 - 2
        assertEquals(
            "x",
            SecureStorageEnvelope.unwrap("""{"data":"x","ttl":60000,"timestamp":$seconds}""", now),
        )
    }

    @Test
    fun `invalid json reads as null`() {
        assertNull(SecureStorageEnvelope.unwrap("{broken", now))
        assertNull(SecureStorageEnvelope.unwrap("plain text", now))
        assertNull(SecureStorageEnvelope.unwrap("""{"ttl":1}""", now))
    }
}
