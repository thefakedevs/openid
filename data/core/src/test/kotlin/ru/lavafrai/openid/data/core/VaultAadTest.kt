package ru.lavafrai.openid.data.core

import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith

class VaultAadTest {
    private val key = ByteArray(32).also { SecureRandom().nextBytes(it) }

    /** Exactly what the schema-1 release bound to every record. */
    private fun legacyAad(record: SecretRecord) =
        "pocket-v1|${record.slot}|${record.accountId}|${record.protection}|${record.alias}"
            .toByteArray(Charsets.UTF_8)

    private fun seal(
        aad: ByteArray,
        raw: ByteArray,
    ): Pair<ByteArray, ByteArray> {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"))
        cipher.updateAAD(aad)
        return cipher.iv to cipher.doFinal(raw)
    }

    private fun open(record: SecretRecord): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, record.iv))
        cipher.updateAAD(VaultAad.of(record))
        return cipher.doFinal(record.ciphertext)
    }

    /** A row as it looks after MIGRATION_1_2: column defaults tokenProfile = 0, cryptoVersion = 1. */
    private fun migratedRow(slot: String): SecretRecord {
        val base = SecretRecord(
            slot = slot,
            accountId = 42,
            protection = if (slot == "pin") "BIOMETRIC" else "DEVICE_KEY",
            alias = "pocket.$slot.legacy",
            wrappedKey = byteArrayOf(),
            wrappingIv = byteArrayOf(),
            ciphertext = byteArrayOf(),
            iv = byteArrayOf(),
            tokenProfile = false,
            cryptoVersion = SecretRecord.CRYPTO_V1,
        )
        val (iv, data) = seal(legacyAad(base), "secret-$slot".toByteArray())
        return base.copy(iv = iv, ciphertext = data)
    }

    @Test
    fun `legacy account and pin rows still open after the schema migration`() {
        for (slot in listOf("account", "pin")) {
            assertContentEquals("secret-$slot".toByteArray(), open(migratedRow(slot)))
        }
    }

    @Test
    fun `legacy row relabelled as current format fails authentication`() {
        val row = migratedRow("account").copy(cryptoVersion = SecretRecord.CRYPTO_V2)
        assertFailsWith<AEADBadTagException> { open(row) }
    }

    @Test
    fun `current row relabelled as legacy to drop the profile binding fails authentication`() {
        val template = migratedRow("account").copy(cryptoVersion = SecretRecord.CRYPTO_V2, tokenProfile = true)
        val (iv, data) = seal(VaultAad.of(template), "token".toByteArray())
        val sealed = template.copy(iv = iv, ciphertext = data)
        assertContentEquals("token".toByteArray(), open(sealed))
        assertFailsWith<AEADBadTagException> { open(sealed.copy(tokenProfile = false)) }
        assertFailsWith<AEADBadTagException> {
            open(sealed.copy(cryptoVersion = SecretRecord.CRYPTO_V1, tokenProfile = false))
        }
    }

    @Test
    fun `legacy row cannot claim a token profile`() {
        assertFailsWith<DataException> { VaultAad.of(migratedRow("account").copy(tokenProfile = true)) }
    }

    @Test
    fun `new rows default to the current format`() {
        val row = migratedRow("account").copy(cryptoVersion = SecretRecord.CRYPTO_CURRENT)
        assertContentEquals(
            "pocket-v2|account|42|DEVICE_KEY|pocket.account.legacy|false".toByteArray(),
            VaultAad.of(row),
        )
    }
}
