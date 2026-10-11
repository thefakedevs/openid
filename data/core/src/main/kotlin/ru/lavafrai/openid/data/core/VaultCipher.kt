package ru.lavafrai.openid.data.core

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.security.keystore.UserNotAuthenticatedException
import java.security.KeyStore
import java.security.SecureRandom
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import ru.lavafrai.openid.models.auth.AuthenticationException
import ru.lavafrai.openid.models.auth.AuthenticationFailure
import ru.lavafrai.openid.models.auth.CryptoAuthenticator

enum class VaultProtection {
    DEVICE,
    BIOMETRIC,
    LEGACY_SYSTEM_AUTH,
}

/** Per-use Keystore key wraps a random data key; an open lease can persist token rotation without another prompt. */
class VaultCipher(private val context: Context) {
    private val keystore: KeyStore get() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private fun associatedData(record: SecretRecord) =
        "pocket-v1|${record.slot}|${record.accountId}|${record.protection}|${record.alias}".toByteArray(Charsets.UTF_8)

    suspend fun create(
        slot: String,
        accountId: Long,
        protection: String,
        protectionMode: VaultProtection,
        raw: ByteArray,
        authenticator: CryptoAuthenticator?,
    ): SecretRecord {
        requireData(
            (slot == "account" && protection == "DEVICE_KEY" && protectionMode == VaultProtection.DEVICE) ||
                (slot == "pin" && protection == "BIOMETRIC" && protectionMode == VaultProtection.BIOMETRIC),
            "STORAGE_PROTECTION_INVALID",
        )
        VaultAuthentication.unavailable(context, protectionMode)?.let { throw AuthenticationException(it) }
        val alias = "pocket.$slot.${UUID.randomUUID()}"
        val key = ByteArray(32).also { SecureRandom().nextBytes(it) }
        var committed = false
        try {
            val cipher = withContext(Dispatchers.IO) {
                val builder = KeyGenParameterSpec.Builder(
                    alias,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setKeySize(256)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                if (protectionMode == VaultProtection.BIOMETRIC) {
                    builder.setUserAuthenticationRequired(true)
                    if (Build.VERSION.SDK_INT >= 30) {
                        builder.setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
                    } else {
                        @Suppress("DEPRECATION")
                        builder.setUserAuthenticationValidityDurationSeconds(-1)
                    }
                    builder.setInvalidatedByBiometricEnrollment(true)
                }
                val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
                generator.init(builder.build())
                Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, generator.generateKey()) }
            }
            if (protectionMode != VaultProtection.DEVICE) checkNotNull(authenticator).authenticate(cipher)
            val metadata = SecretRecord(
                slot = slot,
                accountId = accountId,
                protection = protection,
                alias = alias,
                wrappedKey = byteArrayOf(),
                wrappingIv = cipher.iv,
                ciphertext = byteArrayOf(),
                iv = byteArrayOf(),
            )
            val record = withContext(Dispatchers.Default) {
                cipher.updateAAD(associatedData(metadata))
                val wrapped = cipher.doFinal(key)
                val (iv, encrypted) = encryptData(metadata, key, raw)
                metadata.copy(wrappedKey = wrapped, ciphertext = encrypted, iv = iv)
            }
            committed = true
            return record
        } catch (failure: Exception) {
            throw authenticationFailure(failure)
        } finally {
            key.fill(0)
            if (!committed) withContext(NonCancellable + Dispatchers.IO) { deleteKey(alias) }
        }
    }

    suspend fun open(
        record: SecretRecord,
        authenticator: CryptoAuthenticator?,
    ): OpenSecret {
        val protectionMode = when {
            record.slot == "account" && record.protection == "SYSTEM_AUTH_REQUIRED" ->
                VaultProtection.LEGACY_SYSTEM_AUTH

            record.slot == "account" && record.protection == "DEVICE_KEY" -> VaultProtection.DEVICE

            record.slot == "pin" && record.protection == "BIOMETRIC" -> VaultProtection.BIOMETRIC

            else -> throw DataException("STORAGE_PROTECTION_INVALID")
        }
        requireData(protectionMode == VaultProtection.DEVICE || authenticator != null, "STORAGE_AUTHENTICATOR_REQUIRED")
        VaultAuthentication.unavailable(context, protectionMode)?.let { throw AuthenticationException(it) }
        return try {
            openAuthenticated(record, authenticator)
        } catch (failure: Exception) {
            throw authenticationFailure(failure)
        }
    }

    private suspend fun openAuthenticated(
        record: SecretRecord,
        authenticator: CryptoAuthenticator?,
    ): OpenSecret {
        val cipher = withContext(Dispatchers.IO) {
            val key = keystore.getKey(record.alias, null) as? SecretKey ?: throw DataException("STORAGE_KEY_MISSING")
            Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, record.wrappingIv))
            }
        }
        authenticator?.authenticate(cipher)
        var opened: OpenSecret? = null
        try {
            return withContext(Dispatchers.Default) {
                cipher.updateAAD(associatedData(record))
                val key = cipher.doFinal(record.wrappedKey)
                try {
                    requireData(key.size == 32, "STORAGE_KEY_INVALID")
                    val data = Cipher.getInstance("AES/GCM/NoPadding")
                    data.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, record.iv))
                    data.updateAAD(associatedData(record))
                    OpenSecret(key, data.doFinal(record.ciphertext)).also { opened = it }
                } catch (failure: Throwable) {
                    key.fill(0)
                    throw failure
                }
            }
        } catch (failure: Throwable) {
            opened?.close()
            throw failure
        }
    }

    private fun authenticationFailure(failure: Exception): Exception {
        val causes = generateSequence<Throwable>(failure) { it.cause }.take(10).toList()
        return when {
            causes.any { it is KeyPermanentlyInvalidatedException } ->
                AuthenticationException(AuthenticationFailure.KEY_INVALIDATED)

            causes.any { it is UserNotAuthenticatedException } ->
                AuthenticationException(AuthenticationFailure.NOT_AUTHENTICATED)

            else -> failure
        }
    }

    fun rewrite(
        record: SecretRecord,
        key: ByteArray,
        raw: ByteArray,
    ): SecretRecord {
        val (iv, data) = encryptData(record, key, raw)
        return record.copy(iv = iv, ciphertext = data)
    }

    private fun encryptData(
        record: SecretRecord,
        key: ByteArray,
        raw: ByteArray,
    ): Pair<ByteArray, ByteArray> {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"))
        cipher.updateAAD(associatedData(record))
        return cipher.iv to cipher.doFinal(raw)
    }

    fun deleteKey(alias: String) {
        requireData(alias.startsWith("pocket."), "STORAGE_ALIAS_INVALID")
        keystore.deleteEntry(alias)
    }

    /** Remove keys abandoned by a killed/cancelled import. Only our namespace is touched. */
    fun removeOrphans(liveAliases: Set<String>) {
        val store = keystore
        store.aliases().toList().filter { it.startsWith("pocket.") && it !in liveAliases }.forEach(store::deleteEntry)
    }

    class OpenSecret(
        val key: ByteArray,
        val raw: ByteArray,
    ) : AutoCloseable {
        override fun close() {
            key.fill(0)
            raw.fill(0)
        }
    }
}
