package ru.lavafrai.openid.ui

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import ru.lavafrai.openid.domain.models.QrGenerationFailure
import ru.lavafrai.openid.domain.models.QrGenerationStage
import ru.lavafrai.openid.domain.models.QrGenerationState
import ru.lavafrai.openid.domain.models.VerifiedPin
import ru.lavafrai.openid.domain.usecases.GenerateQrUseCase
import ru.lavafrai.openid.models.digitalid.QrSession
import ru.lavafrai.openid.models.errors.FailureReason
import ru.lavafrai.openid.models.pin.PinSource

data class QrSessionUiState(
    val qr: QrSession? = null,
    val stage: QrGenerationStage? = null,
    val refreshAtMillis: Long? = null,
    val refreshIntervalMillis: Long = 0,
    val opening: Boolean = false,
    val error: PocketError? = null,
    val requiresPinReplacement: Boolean = false,
)

/** Owns one QR collection and its refresh channel, independently of the visible screen. */
internal class QrSessionController(
    private val generate: GenerateQrUseCase,
    private val scope: CoroutineScope,
    private val nowMillis: () -> Long,
    private val onChange: (QrSessionUiState) -> Unit,
    private val onPinRejected: () -> Unit,
    private val recovery: (QrGenerationState.Failed, PinSource, Boolean) -> ErrorRecovery?,
) {
    private var state = QrSessionUiState()
    private var job: Job? = null
    private var version = 0
    private var refreshRequests: Channel<Unit>? = null

    fun start(
        source: PinSource,
        onPinVerified: suspend (VerifiedPin) -> Unit,
        dispose: () -> Unit,
    ) {
        val generation = ++version
        val previous = job
        val requests = Channel<Unit>(Channel.RENDEZVOUS)
        refreshRequests = requests
        publish(QrSessionUiState(opening = true))
        job = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                previous?.cancelAndJoin()
                generate(source, refreshRequests = requests, onPinVerified = onPinVerified).collect { result ->
                    if (generation == version) accept(result, source)
                }
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                if (generation == version) {
                    publish(
                        QrSessionUiState(error = failure.toPocketError(ErrorOperation.UNLOCK, ErrorRecovery.UNLOCK)),
                    )
                }
            } finally {
                dispose()
                requests.cancel()
                if (generation == version) {
                    refreshRequests = null
                    publish(state.copy(opening = false, qr = null, stage = null, refreshAtMillis = null))
                }
            }
        }
    }

    fun refresh() {
        val before = state
        if (refreshRequests?.trySend(Unit)?.isSuccess == true && state === before) {
            // An undispatched receiver may already have published a newer response.
            publish(state.copy(qr = null, refreshAtMillis = null, error = null))
        }
    }

    fun stop() {
        version++
        job?.cancel()
        refreshRequests = null
        publish(QrSessionUiState())
    }

    suspend fun stopAndWait() {
        stop()
        job?.join()
        job = null
    }

    private fun accept(
        result: QrGenerationState,
        source: PinSource,
    ) {
        when (result) {
            is QrGenerationState.Loading -> publish(
                state.copy(
                    stage = result.stage,
                    qr = null,
                    refreshAtMillis = null,
                    error = null,
                ),
            )

            is QrGenerationState.Ready -> publish(
                QrSessionUiState(
                    qr = result.qr,
                    refreshAtMillis = result.refreshInterval?.let { nowMillis() + it.inWholeMilliseconds },
                    refreshIntervalMillis = result.refreshInterval?.inWholeMilliseconds ?: 0,
                ),
            )

            is QrGenerationState.Failed -> {
                if (result.reason == QrGenerationFailure.AuthorizationRejected) onPinRejected()
                val repair = source is PinSource.Saved && result.requiresPinReplacement()
                publish(
                    QrSessionUiState(
                        error = result.toPocketError(recovery(result, source, repair)),
                        requiresPinReplacement = repair,
                    ),
                )
            }
        }
    }

    private fun publish(next: QrSessionUiState) {
        state = next
        onChange(next)
    }
}

private fun QrGenerationState.Failed.requiresPinReplacement(): Boolean {
    if (reason == QrGenerationFailure.AuthorizationRejected) return true
    if (stage != QrGenerationStage.GETTING_PIN) return false
    return diagnostic.reason in setOf(
        FailureReason.BIOMETRIC_KEY_INVALIDATED,
        FailureReason.STORAGE_KEY_MISSING,
        FailureReason.PIN_NOT_SAVED,
    )
}
