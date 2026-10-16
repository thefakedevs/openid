package ru.lavafrai.openid.ui

import android.content.Context
import android.content.ContextWrapper
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import ru.lavafrai.openid.R
import ru.lavafrai.openid.data.core.VaultAuthentication
import ru.lavafrai.openid.data.core.VaultProtection
import ru.lavafrai.openid.models.auth.AuthenticationFailure
import ru.lavafrai.openid.models.errors.FailureDiagnostic
import ru.lavafrai.openid.models.errors.FailureKind

@Composable
fun SystemAuthentication(
    request: AuthRequest,
    dispatch: (PocketAction) -> Unit,
) {
    val context = LocalContext.current
    val mode = if (request.kind == AuthKind.LEGACY_PROFILE_MIGRATION) {
        VaultProtection.LEGACY_SYSTEM_AUTH
    } else {
        VaultProtection.BIOMETRIC
    }
    val allowed = VaultAuthentication.allowedAuthenticators(mode)
    val title = stringResource(request.kind.titleRes)
    val subtitle = stringResource(
        if (allowed and DEVICE_CREDENTIAL != 0) R.string.auth_device_subtitle else R.string.auth_biometric_subtitle,
    )
    val cancel = stringResource(R.string.action_cancel)
    DisposableEffect(request, context, title, subtitle, cancel) {
        val activity = context.activity()
        val unavailable = VaultAuthentication.unavailable(context, mode)
        if (activity == null || unavailable != null) {
            dispatch(PocketAction.AuthFailed(request, unavailable ?: AuthenticationFailure.FAILED))
            onDispose {}
        } else {
            var finished = false
            val prompt =
                BiometricPrompt(
                    activity,
                    ContextCompat.getMainExecutor(context),
                    object : BiometricPrompt.AuthenticationCallback() {
                        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                            if (finished) return
                            finished = true
                            if (result.cryptoObject?.cipher === request.cipher) {
                                dispatch(PocketAction.ConfirmAuth(request))
                            } else {
                                dispatch(PocketAction.AuthFailed(request, AuthenticationFailure.NOT_AUTHENTICATED))
                            }
                        }

                        override fun onAuthenticationError(
                            errorCode: Int,
                            errString: CharSequence,
                        ) {
                            if (finished) return
                            finished = true
                            when (errorCode) {
                                BiometricPrompt.ERROR_CANCELED,
                                BiometricPrompt.ERROR_USER_CANCELED,
                                BiometricPrompt.ERROR_NEGATIVE_BUTTON,
                                -> dispatch(PocketAction.CancelAuth(request))

                                else -> dispatch(PocketAction.AuthFailed(request, errorCode.failure()))
                            }
                        }
                    },
                )
            val info = BiometricPrompt.PromptInfo.Builder()
                .setTitle(title)
                .setSubtitle(subtitle)
                .setAllowedAuthenticators(allowed)
            if (allowed and DEVICE_CREDENTIAL == 0) info.setNegativeButtonText(cancel)
            try {
                prompt.authenticate(info.build(), BiometricPrompt.CryptoObject(request.cipher))
            } catch (failure: Exception) {
                finished = true
                dispatch(
                    PocketAction.AuthFailed(
                        request,
                        AuthenticationFailure.FAILED,
                        FailureDiagnostic.capture("AUTH_PROMPT_FAILED", FailureKind.STORAGE, failure),
                    ),
                )
            }
            onDispose {
                // Do not cancel a successful crypto operation while storage is consuming its cipher.
                if (!finished) {
                    finished = true
                    prompt.cancelAuthentication()
                }
            }
        }
    }
}

private fun Int.failure(): AuthenticationFailure = when (this) {
    BiometricPrompt.ERROR_HW_NOT_PRESENT -> AuthenticationFailure.NO_BIOMETRIC_HARDWARE
    BiometricPrompt.ERROR_NO_BIOMETRICS -> AuthenticationFailure.NOT_ENROLLED
    BiometricPrompt.ERROR_NO_DEVICE_CREDENTIAL -> AuthenticationFailure.NO_SCREEN_LOCK
    BiometricPrompt.ERROR_LOCKOUT, BiometricPrompt.ERROR_LOCKOUT_PERMANENT -> AuthenticationFailure.LOCKED_OUT
    BiometricPrompt.ERROR_SECURITY_UPDATE_REQUIRED -> AuthenticationFailure.SECURITY_UPDATE_REQUIRED
    BiometricPrompt.ERROR_HW_UNAVAILABLE, BiometricPrompt.ERROR_TIMEOUT -> AuthenticationFailure.TEMPORARILY_UNAVAILABLE
    else -> AuthenticationFailure.FAILED
}

private fun Context.activity(): FragmentActivity? = when (this) {
    is FragmentActivity -> this
    is ContextWrapper -> baseContext.activity()
    else -> null
}
