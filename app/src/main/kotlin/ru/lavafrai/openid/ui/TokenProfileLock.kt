package ru.lavafrai.openid.ui

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.core.content.edit
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import ru.lavafrai.openid.data.core.DataException

/**
 * A device-local PIN lock for "token profile" accounts: root imports that only captured a bare
 * MAX authorization token (see [ru.lavafrai.openid.models.account.ImportedDigitalId.tokenProfile]),
 * with no PIN-salt material to drive the real Digital ID PIN flow. [GenerateQrUseCase] never
 * checks a PIN for such an account - the server already accepts the captured token on its own -
 * so without this, opening the QR needs no confirmation at all once the phone itself is unlocked.
 * This stands in for that missing check, entirely on-device: the PIN is chosen by the user, never
 * sent anywhere, and compared by salted hash. Deliberately independent of [PinRepository] and its
 * server-verified-PIN invariant, so it cannot affect the real PIN flow used by normal accounts.
 */
class TokenProfileLock(
    private val context: Context,
    private val workDispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    private val preferences = context.getSharedPreferences("token-profile-lock", Context.MODE_PRIVATE)
    private val keystore: KeyStore get() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    fun isConfigured(accountId: Long): Boolean =
        preferences.getLong("accountId", -1) == accountId && preferences.contains("hash")

    fun biometricEnabled(accountId: Long): Boolean =
        isConfigured(accountId) && preferences.getBoolean("biometric", false)

    /** The first PIN entered for a fresh account defines the lock; there is nothing to check it against. */
    suspend fun set(
        accountId: Long,
        pin: CharArray,
        biometric: Boolean,
    ): Boolean {
        // PBKDF2 is deliberately slow: keep it (and the preference writes) off the caller's thread.
        // NonCancellable is the OUTER context and the dispatcher switch is inside it: returning from a
        // dispatcher switch into an already cancelled caller would throw and drop the finished result,
        // while the same-dispatcher NonCancellable wrapper hands it back. Once the lock is written the
        // caller must learn it, or a cancelled first-time setup leaves a lock the UI does not know about.
        return withContext(NonCancellable) {
            withContext(workDispatcher) {
                deleteKey()
                val salt = ByteArray(SALT_BYTES).also { SecureRandom().nextBytes(it) }
                val hash = hash(pin, salt)
                val biometricReady = biometric && runCatching { createKey() }.isSuccess
                preferences.edit {
                    putLong("accountId", accountId)
                    putString("salt", salt.encode())
                    putString("hash", hash.encode())
                    putBoolean("biometric", biometricReady)
                }
                biometricReady
            }
        }
    }

    /**
     * True when [pin] matches. A stored lock that cannot be read back (missing or malformed salt or
     * hash) throws instead of returning false: it must neither open access nor be mistaken for a
     * first-time setup, so the caller reports a storage error and the account stays locked.
     * Not cancellable once started, so a wrong guess is always reported back and can be counted.
     */
    suspend fun verify(
        accountId: Long,
        pin: CharArray,
    ): Boolean = withContext(NonCancellable) {
        withContext(workDispatcher) {
            if (preferences.getLong("accountId", -1) != accountId) return@withContext false
            val salt = stored("salt", SALT_BYTES)
            val expected = stored("hash", HASH_BYTES)
            MessageDigest.isEqual(hash(pin, salt), expected)
        }
    }

    private fun stored(
        name: String,
        size: Int,
    ): ByteArray {
        val bytes = try {
            preferences.getString(name, null)?.decode()
        } catch (_: IllegalArgumentException) {
            null
        }
        if (bytes == null || bytes.size != size) throw DataException("STORAGE_TOKEN_LOCK_INVALID")
        return bytes
    }

    /** Null means biometric unlock is unavailable right now (never set up, or the Keystore key was invalidated). */
    fun biometricCipher(accountId: Long): Cipher? {
        if (!biometricEnabled(accountId)) return null
        return runCatching {
            val key = keystore.getKey(ALIAS, null) as? SecretKey ?: return null
            Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key) }
        }.getOrNull()
    }

    fun enableBiometric(accountId: Long): Boolean {
        if (!isConfigured(accountId) || runCatching { createKey() }.isFailure) return false
        preferences.edit { putBoolean("biometric", true) }
        return true
    }

    /** The Keystore key was invalidated (e.g. biometric enrollment changed); fall back to the typed PIN. */
    fun disableBiometric(accountId: Long) {
        if (preferences.getLong("accountId", -1) != accountId) return
        preferences.edit { putBoolean("biometric", false) }
        deleteKey()
    }

    fun clear() {
        preferences.edit { clear() }
        deleteKey()
    }

    private fun createKey() {
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        val builder = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setUserAuthenticationRequired(true)
            .setInvalidatedByBiometricEnrollment(true)
        if (Build.VERSION.SDK_INT >= 30) {
            builder.setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
        } else {
            @Suppress("DEPRECATION")
            builder.setUserAuthenticationValidityDurationSeconds(-1)
        }
        generator.init(builder.build())
        generator.generateKey()
    }

    private fun deleteKey() {
        runCatching { keystore.deleteEntry(ALIAS) }
    }

    private fun hash(
        pin: CharArray,
        salt: ByteArray,
    ): ByteArray {
        val spec = PBEKeySpec(pin, salt, PBKDF2_ITERATIONS, 256)
        return try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    private fun ByteArray.encode(): String = Base64.encodeToString(this, Base64.NO_WRAP)

    private fun String.decode(): ByteArray = Base64.decode(this, Base64.NO_WRAP)

    private companion object {
        const val ALIAS = "pocket.token-profile-lock"
        const val PBKDF2_ITERATIONS = 120_000
        const val SALT_BYTES = 16
        const val HASH_BYTES = 32
    }
}
