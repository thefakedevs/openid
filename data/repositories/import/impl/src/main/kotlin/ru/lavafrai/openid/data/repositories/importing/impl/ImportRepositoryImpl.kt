package ru.lavafrai.openid.data.repositories.importing.impl

import java.util.Base64
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import ru.lavafrai.openid.data.core.AccountCodec
import ru.lavafrai.openid.data.core.DataException
import ru.lavafrai.openid.data.core.StrictJson
import ru.lavafrai.openid.data.core.requireData
import ru.lavafrai.openid.data.repositories.importing.api.ImportRepository
import ru.lavafrai.openid.models.account.ImportedAccount

class ImportRepositoryImpl : ImportRepository {
    override suspend fun decode(
        url: String,
        password: CharArray,
    ): ImportedAccount = withContext(Dispatchers.Default) {
        requireData(
            url.startsWith(PREFIX) && url.length <= MAX_SIZE && url.all {
                it.code in 33..126
            },
            "IMPORT_URL_INVALID",
        )
        requireData(
            password.size in 12..256 && password.all {
                it.code in 32..126
            },
            "PASSPHRASE_MUST_BE_ASCII_12_TO_256",
        )
        val envelopeBytes = decodeBase64Url(url.substring(PREFIX.length))
        val envelope = try {
            StrictJson.obj(StrictJson.parse(envelopeBytes))
        } finally {
            envelopeBytes.fill(0)
        }
        requireData(
            StrictJson.integer(envelope["v"]) == 1L && envelope["kdf"] == "PBKDF2-HMAC-SHA256" &&
                StrictJson.integer(envelope["iterations"]) == 310_000L,
            "IMPORT_ENVELOPE_UNSUPPORTED",
        )
        val salt = decodeBase64Url(envelope["salt"])
        val iv = decodeBase64Url(envelope["iv"])
        val ciphertext = decodeBase64Url(envelope["ciphertext"])
        requireData(
            salt.size == 16 && iv.size == 12 && ciphertext.size in 16..MAX_SIZE,
            "IMPORT_CIPHER_PARAMETERS_INVALID",
        )
        val keySpec = PBEKeySpec(password, salt, 310_000, 256)
        var key: ByteArray? = null
        var plaintext: ByteArray? = null
        try {
            currentCoroutineContext().ensureActive()
            key = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                .generateSecret(keySpec)
                .encoded
            currentCoroutineContext().ensureActive()
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
            cipher.updateAAD("didpocket-import-v1".toByteArray(Charsets.US_ASCII))
            plaintext = try {
                cipher.doFinal(ciphertext)
            } catch (_: AEADBadTagException) {
                throw DataException("WRONG_PASSPHRASE_OR_DAMAGED_LINK")
            }
            currentCoroutineContext().ensureActive()
            val payload = StrictJson.obj(StrictJson.parse(plaintext))
            requireData(StrictJson.integer(payload["schema"]) == 1L, "IMPORT_SCHEMA_UNRECOGNIZED")
            AccountCodec.decode(payload["account"])
        } finally {
            keySpec.clearPassword()
            key?.fill(0)
            plaintext?.fill(0)
            ciphertext.fill(0)
            salt.fill(0)
            iv.fill(0)
        }
    }

    private fun decodeBase64Url(value: Any?): ByteArray {
        val text = value as? String ?: throw DataException("IMPORT_BASE64_INVALID")
        requireData(
            text.length <= MAX_SIZE &&
                text.all {
                    it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '-' || it == '_'
                },
            "IMPORT_BASE64_INVALID",
        )
        return try {
            Base64.getUrlDecoder().decode(text)
        } catch (_: IllegalArgumentException) {
            throw DataException("IMPORT_BASE64_INVALID")
        }
    }

    private companion object {
        const val PREFIX = "didpocket://import/v1#"
        const val MAX_SIZE = 2_800_000
    }
}
