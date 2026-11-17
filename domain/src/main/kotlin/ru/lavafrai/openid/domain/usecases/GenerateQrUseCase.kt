package ru.lavafrai.openid.domain.usecases

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withTimeoutOrNull
import ru.lavafrai.openid.data.repositories.account.api.AccountRepository
import ru.lavafrai.openid.data.repositories.digitalid.api.DigitalIdRepository
import ru.lavafrai.openid.data.repositories.max.api.MaxSessionRepository
import ru.lavafrai.openid.domain.AccountOperationCoordinator
import ru.lavafrai.openid.domain.models.QrGenerationFailure
import ru.lavafrai.openid.domain.models.QrGenerationStage
import ru.lavafrai.openid.domain.models.QrGenerationState
import ru.lavafrai.openid.domain.models.UnlockedPin
import ru.lavafrai.openid.domain.models.VerifiedPin
import ru.lavafrai.openid.models.account.AccountLease
import ru.lavafrai.openid.models.digitalid.DigitalIdToken
import ru.lavafrai.openid.models.errors.FailureDiagnostic
import ru.lavafrai.openid.models.max.MaxClientProfile
import ru.lavafrai.openid.models.max.MaxCredentials
import ru.lavafrai.openid.models.max.WebAppLaunch
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
     * Cold flow; concurrent generation is rejected. Cancellation closes the owned lease and session.
     * null interval requests one QR. The default is a client refresh policy, not a server TTL.
     * A rendezvous channel accepts manual refresh only while waiting, never during network I/O.
     * onPinVerified may save the PIN; its argument is closed before requesting the first QR.
     */
    operator fun invoke(
        pinSource: PinSource,
        refreshInterval: Duration? = 15.seconds,
        refreshRequests: ReceiveChannel<Unit>? = null,
        onPinVerified: suspend (VerifiedPin) -> Unit = {},
    ): Flow<QrGenerationState> {
        require(refreshInterval == null || (refreshInterval.isFinite() && refreshInterval > Duration.ZERO)) {
            "QR refresh interval must be finite and positive"
        }
        return flow {
            var lastStage = QrGenerationStage.UNLOCKING_ACCOUNT
            val generation = flow<QrGenerationState> {
                val reportStage: suspend (QrGenerationStage) -> Unit = { stage ->
                    currentCoroutineContext().ensureActive()
                    lastStage = stage
                    emit(QrGenerationState.Loading(stage))
                }
                operations.generate {
                    reportStage(QrGenerationStage.UNLOCKING_ACCOUNT)
                    unlockAccount().use { lease ->
                        val authorized = authorize(lease, pinSource, onPinVerified, reportStage)
                        refreshQr(lease, authorized, refreshInterval, refreshRequests, reportStage)
                    }
                }
            }
            emitAll(
                generation.catch { failure ->
                    if (failure is CancellationException || failure is Error) throw failure
                    emit(failure.toQrFailure(lastStage))
                },
            )
        }
    }

    private suspend fun authorize(
        lease: AccountLease,
        source: PinSource,
        onPinVerified: suspend (VerifiedPin) -> Unit,
        stage: suspend (QrGenerationStage) -> Unit,
    ): AuthorizedDigitalId {
        var pin: UnlockedPin? = null
        try {
            val account = lease.account
            val captured = account.did.biometricToken
            if (captured != null && account.did.pinSalt == null) {
                // Captured-token profile: MAX already holds a valid authorization token; no PIN exists.
                val launch = openDigitalId(lease, stage)
                stage(QrGenerationStage.CHECKING_SECURITY)
                validateStatus(account.did, digitalId.getSecurityStatus(launch))
                stage(QrGenerationStage.AUTHORIZING)
                if (!digitalId.authorize(launch, captured).authorizationStatus) {
                    throw DigitalIdAuthorizationRejectedException()
                }
                return AuthorizedDigitalId(launch, captured)
            }
            // Biometrics precede network I/O; manual PIN acquisition follows the server preflight.
            if (source is PinSource.Saved) {
                stage(QrGenerationStage.GETTING_PIN)
                pin = unlockPin(lease.account.accountId, source)
            }
            val launch = openDigitalId(lease, stage)
            stage(QrGenerationStage.CHECKING_SECURITY)
            val current = lease.account
            validateStatus(current.did, digitalId.getSecurityStatus(launch))
            val unlocked = pin ?: run {
                stage(QrGenerationStage.GETTING_PIN)
                unlockPin(current.accountId, source).also { pin = it }
            }
            val token = pinToken(checkNotNull(current.did.pinSalt), unlocked)
            stage(QrGenerationStage.AUTHORIZING)
            if (!digitalId.authorize(launch, token).authorizationStatus) throw DigitalIdAuthorizationRejectedException()
            stage(QrGenerationStage.SAVING_PIN)
            VerifiedPin(lease, current, unlocked).use { onPinVerified(it) }
            return AuthorizedDigitalId(launch, token)
        } finally {
            pin?.close()
        }
    }

    private suspend fun openDigitalId(
        lease: AccountLease,
        stage: suspend (QrGenerationStage) -> Unit,
    ): WebAppLaunch {
        val account = lease.account
        val credentials = MaxCredentials(account.accountId, account.max.accessToken, account.max.deviceId)
        val profile = MaxClientProfile(
            userAgent = account.max.userAgent,
            webUserAgent = account.webUserAgent,
            tcpVersion = account.options.tcpVersion,
            fingerprint = account.options.fingerprint,
            loginUserAgent = account.options.loginUserAgent,
        )
        stage(QrGenerationStage.CONNECTING_MAX)
        return max.open(credentials, profile).use { session ->
            stage(QrGenerationStage.LOGGING_IN)
            val login = session.login()
            check(login.accountId == null || login.accountId == account.accountId) { "MAX account ID mismatch" }
            login.rotatedAccessToken?.let { token -> operations.access { accounts.updateMaxToken(lease, token) } }
            stage(QrGenerationStage.RESOLVING_BOT)
            val botId = session.resolveDigitalIdBotId(account.max.botId)
            check(botId > 0) { "Digital ID bot ID must be positive" }
            stage(QrGenerationStage.GETTING_LAUNCH)
            session.getWebAppLaunch(botId).also {
                check(it.accountId == account.accountId) { "WebApp account ID mismatch" }
            }
        }
    }

    private fun pinToken(
        salt: String,
        pin: UnlockedPin,
    ): DigitalIdToken {
        val buffer = pin.copyPin()
        return try {
            createPinToken(salt, buffer)
        } finally {
            buffer.fill('\u0000')
        }
    }

    private suspend fun FlowCollector<QrGenerationState>.refreshQr(
        lease: AccountLease,
        authorized: AuthorizedDigitalId,
        interval: Duration?,
        requests: ReceiveChannel<Unit>?,
        reportStage: suspend (QrGenerationStage) -> Unit,
    ) {
        var stage = QrGenerationStage.GENERATING_QR
        while (true) {
            reportStage(stage)
            operations.access { lease.account }
            val qr = try {
                digitalId.getUniversalQr(authorized.launch, authorized.token).also {
                    check(it.sessionId.isNotEmpty() && it.sessionId.toByteArray(Charsets.UTF_8).size <= 2000) {
                        "Invalid QR session ID"
                    }
                }
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                val diagnostic = FailureDiagnostic.from(failure)
                if (requests == null || !diagnostic.canRetryRequest) throw failure
                emit(QrGenerationState.Failed(QrGenerationFailure.OperationFailed, diagnostic, stage, true))
                requests.receive()
                stage = QrGenerationStage.REFRESHING_QR
                continue
            }
            currentCoroutineContext().ensureActive()
            emit(QrGenerationState.Ready(qr, interval))
            if (interval == null) return
            if (requests == null) delay(interval) else withTimeoutOrNull(interval) { requests.receive() }
            stage = QrGenerationStage.REFRESHING_QR
        }
    }

    private class AuthorizedDigitalId(
        val launch: WebAppLaunch,
        val token: DigitalIdToken,
    )
}
