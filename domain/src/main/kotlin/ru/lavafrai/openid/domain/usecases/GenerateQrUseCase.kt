package ru.lavafrai.openid.domain.usecases

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import ru.lavafrai.openid.data.repositories.account.api.AccountRepository
import ru.lavafrai.openid.data.repositories.digitalid.api.DigitalIdRepository
import ru.lavafrai.openid.data.repositories.max.api.MaxSessionRepository
import ru.lavafrai.openid.domain.AccountOperationCoordinator
import ru.lavafrai.openid.domain.QrGenerationAlreadyRunningException
import ru.lavafrai.openid.domain.models.InvalidPinException
import ru.lavafrai.openid.domain.models.QrGenerationFailure
import ru.lavafrai.openid.domain.models.QrGenerationStage
import ru.lavafrai.openid.domain.models.QrGenerationState
import ru.lavafrai.openid.domain.models.UnlockedPin
import ru.lavafrai.openid.domain.models.VerifiedPin
import ru.lavafrai.openid.models.auth.AuthenticationException
import ru.lavafrai.openid.models.digitalid.DigitalIdToken
import ru.lavafrai.openid.models.max.MaxClientProfile
import ru.lavafrai.openid.models.max.MaxCredentials
import ru.lavafrai.openid.models.pin.PinSource

class GenerateQrUseCase(
    private val accounts: AccountRepository,
    private val max: MaxSessionRepository,
    private val digitalId: DigitalIdRepository,
    private val unlockAccount: UnlockAccountUseCase,
    private val unlockPin: UnlockPinUseCase,
    private val validateStatus: ValidateDigitalIdSecurityStatusUseCase,
    private val createPinToken: CreateDigitalIdPinTokenUseCase,
    private val operations: AccountOperationCoordinator,
) {
    /**
     * A cold flow: collect once per screen operation. Concurrent generation is rejected.
     * null refreshInterval requests one QR; the default interval is a client policy, not a server TTL.
     * onPinVerified runs once after successful authorization; SavePinUseCase may run inside it.
     * UI must clear its displayed QR when collection stops. Cancellation closes owned leases/session.
     * Repositories must perform blocking I/O off the UI thread and support coroutine cancellation.
     */
    operator fun invoke(
        pinSource: PinSource,
        refreshInterval: Duration? = 15.seconds,
        onPinVerified: suspend (VerifiedPin) -> Unit = {},
    ): Flow<QrGenerationState> {
        require(refreshInterval == null || (refreshInterval.isFinite() && refreshInterval > Duration.ZERO)) {
            "QR refresh interval must be finite and positive"
        }
        return flow<QrGenerationState> {
            suspend fun stage(value: QrGenerationStage) {
                currentCoroutineContext().ensureActive()
                emit(QrGenerationState.Loading(value))
            }

            operations.generate {
                stage(QrGenerationStage.UNLOCKING_ACCOUNT)
                unlockAccount().use { lease ->
                    var account = lease.account
                    stage(QrGenerationStage.CONNECTING_MAX)
                    val launch = max.open(
                        MaxCredentials(account.accountId, account.max.accessToken, account.max.deviceId),
                        MaxClientProfile(
                            userAgent = account.max.userAgent,
                            webUserAgent = account.webUserAgent,
                            tcpVersion = account.options.tcpVersion,
                            fingerprint = account.options.fingerprint,
                            loginUserAgent = account.options.loginUserAgent,
                        ),
                    ).use { session ->
                        stage(QrGenerationStage.LOGGING_IN)
                        val login = session.login()
                        check(login.accountId == null || login.accountId == account.accountId) {
                            "MAX account ID mismatch"
                        }
                        login.rotatedAccessToken?.let { token ->
                            operations.access { accounts.updateMaxToken(lease, token) }
                            // Token rotation replaces the lease snapshot. Bind PIN verification to that current snapshot.
                            account = lease.account
                        }
                        stage(QrGenerationStage.RESOLVING_BOT)
                        val botId = session.resolveDigitalIdBotId(account.max.botId)
                        check(botId > 0) { "Digital ID bot ID must be positive" }
                        stage(QrGenerationStage.GETTING_LAUNCH)
                        session.getWebAppLaunch(botId).also {
                            check(it.accountId == account.accountId) { "WebApp account ID mismatch" }
                        }
                    }

                    stage(QrGenerationStage.CHECKING_SECURITY)
                    validateStatus(account.did, digitalId.getSecurityStatus(launch))

                    var pin: UnlockedPin? = null
                    val token: DigitalIdToken
                    try {
                        stage(QrGenerationStage.GETTING_PIN)
                        val unlocked = unlockPin(account.accountId, pinSource)
                        pin = unlocked
                        val buffer = unlocked.copyPin()
                        token = try {
                            createPinToken(checkNotNull(account.did.pinSalt), buffer)
                        } finally {
                            buffer.fill('\u0000')
                        }
                        stage(QrGenerationStage.AUTHORIZING)
                        if (!digitalId.authorize(launch, token).authorizationStatus) {
                            throw DigitalIdAuthorizationRejectedException()
                        }
                        currentCoroutineContext().ensureActive()
                        VerifiedPin(lease, account, unlocked).use { onPinVerified(it) }
                    } finally {
                        pin?.close()
                    }

                    var first = true
                    while (true) {
                        stage(if (first) QrGenerationStage.GENERATING_QR else QrGenerationStage.REFRESHING_QR)
                        operations.access { lease.account }
                        val qr = digitalId.getUniversalQr(launch, token)
                        check(qr.sessionId.isNotEmpty() && qr.sessionId.toByteArray(Charsets.UTF_8).size <= 2000) {
                            "Invalid QR session ID"
                        }
                        currentCoroutineContext().ensureActive()
                        emit(QrGenerationState.Ready(qr))
                        if (refreshInterval == null) break
                        delay(refreshInterval)
                        first = false
                    }
                }
            }
        }.catch { failure ->
            if (failure is CancellationException || failure is Error) throw failure
            val reason = when (failure) {
                is AuthenticationException -> QrGenerationFailure.Authentication(failure.reason)
                is DigitalIdPreflightException -> QrGenerationFailure.Preflight(failure.reason)
                is InvalidPinException -> QrGenerationFailure.InvalidPin
                is DigitalIdAuthorizationRejectedException -> QrGenerationFailure.AuthorizationRejected
                is QrGenerationAlreadyRunningException -> QrGenerationFailure.AlreadyRunning
                else -> QrGenerationFailure.OperationFailed
            }
            emit(QrGenerationState.Failed(reason))
        }
    }
}

private class DigitalIdAuthorizationRejectedException : IllegalStateException("Digital ID authorization rejected")
