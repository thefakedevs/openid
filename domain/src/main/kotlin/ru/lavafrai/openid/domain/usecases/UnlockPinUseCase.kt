package ru.lavafrai.openid.domain.usecases

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import ru.lavafrai.openid.data.repositories.pin.api.PinRepository
import ru.lavafrai.openid.domain.AccountOperationCoordinator
import ru.lavafrai.openid.domain.models.UnlockedPin
import ru.lavafrai.openid.domain.models.validatePin
import ru.lavafrai.openid.models.pin.PinSource

class UnlockPinUseCase(
    private val pins: PinRepository,
    private val operations: AccountOperationCoordinator,
) {
    /** Validates format only. Server acceptance is established later by GenerateQrUseCase. */
    suspend operator fun invoke(
        accountId: Long,
        source: PinSource,
    ): UnlockedPin {
        require(accountId > 0) { "Account ID must be positive" }
        val buffer = when (source) {
            is PinSource.Manual -> source.requestPin()

            is PinSource.Saved -> operations.access {
                pins.unlock(accountId, source.authenticator).use { lease ->
                    check(lease.accountId == accountId) { "Saved PIN belongs to another account" }
                    lease.copyPin()
                }
            }
        }
        try {
            currentCoroutineContext().ensureActive()
            validatePin(buffer)
            return UnlockedPin(accountId, buffer)
        } catch (failure: Throwable) {
            buffer.fill('\u0000')
            throw failure
        }
    }
}
