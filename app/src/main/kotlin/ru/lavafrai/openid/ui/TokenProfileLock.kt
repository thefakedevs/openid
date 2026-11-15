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
class TokenProfileLock(private val context: Context) {
    private val preferences = context.getSharedPreferences("token-profile-lock", Context.MODE_PRIVATE)
    private val keystore: KeyStore get() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    fun isConfigured(accountId: Long): Boolean =
        preferences.getLong("accountId", -1) == accountId && preferences.contains("hash")

    fun biometricEnabled(accountId: Long): Boolean =
        isConfigured(accountId) && preferences.getBoolean("biometric", false)

    /** The first PIN entered for a fresh account defines the lock; there is nothing to check it against. */
    fun set(
        accountId: Long,
        pin: CharArray,
        biometric: Boolean,
    ) {
        deleteKey()
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        preferences.edit {
            putLong("accountId", accountId)
            putString("salt", salt.encode())
            putString("hash", hash(pin, salt).encode())
            putBoolean("biometric", biometric)
        }
        if (biometric) createKey()
    }

    fun verify(
        accountId: Long,
        pin: CharArray,
    ): Boolean {
        if (preferences.getLong("accountId", -1) != accountId) return false
        val salt = preferences.getString("salt", null)?.decode() ?: return false
        val expected = preferences.getString("hash", null)?.decode() ?: return false
        return hash(pin, salt).contentEquals(expected)
    }

    /** Null means biometric unlock is unavailable right now (never set up, or the Keystore key was invalidated). */
    fun biometricCipher(accountId: Long): Cipher? {
        if (!biometricEnabled(accountId)) return null
        return runCatching {
            val key = keystore.getKey(ALIAS, null) as? SecretKey ?: return null
            Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key) }
        }.getOrNull()
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
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(salt)
        digest.update(ByteArray(pin.size) { pin[it].code.toByte() })
        return digest.digest()
    }

    private fun ByteArray.encode(): String = Base64.encodeToString(this, Base64.NO_WRAP)

    private fun String.decode(): ByteArray = Base64.decode(this, Base64.NO_WRAP)

    private companion object {
        const val ALIAS = "pocket.token-profile-lock"
    }
}
