package ru.lavafrai.openid.data.core

import android.app.KeyguardManager
import android.content.Context
import android.os.Build
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import ru.lavafrai.openid.models.auth.AuthenticationFailure

/** The prompt and Keystore must agree on which authenticators can authorize the cipher. */
object VaultAuthentication {
    fun allowedAuthenticators(protectionMode: VaultProtection): Int = BIOMETRIC_STRONG or
        if (protectionMode == VaultProtection.LEGACY_SYSTEM_AUTH &&
            Build.VERSION.SDK_INT >= 30
        ) {
            DEVICE_CREDENTIAL
        } else {
            0
        }

    fun unavailable(
        context: Context,
        protectionMode: VaultProtection,
    ): AuthenticationFailure? {
        if (protectionMode == VaultProtection.DEVICE) return null
        if (!context.getSystemService(KeyguardManager::class.java).isDeviceSecure) {
            return AuthenticationFailure.NO_SCREEN_LOCK
        }
        return when (BiometricManager.from(context).canAuthenticate(allowedAuthenticators(protectionMode))) {
            BiometricManager.BIOMETRIC_SUCCESS -> null
            BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE -> AuthenticationFailure.NO_BIOMETRIC_HARDWARE
            BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> AuthenticationFailure.NOT_ENROLLED
            BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE -> AuthenticationFailure.TEMPORARILY_UNAVAILABLE
            BiometricManager.BIOMETRIC_ERROR_SECURITY_UPDATE_REQUIRED -> AuthenticationFailure.SECURITY_UPDATE_REQUIRED
            BiometricManager.BIOMETRIC_ERROR_UNSUPPORTED -> AuthenticationFailure.UNSUPPORTED
            else -> AuthenticationFailure.TEMPORARILY_UNAVAILABLE
        }
    }
}
