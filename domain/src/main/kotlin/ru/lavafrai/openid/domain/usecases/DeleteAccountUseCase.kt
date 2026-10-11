package ru.lavafrai.openid.domain.usecases

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import ru.lavafrai.openid.data.repositories.account.api.AccountRepository
import ru.lavafrai.openid.data.repositories.pin.api.PinRepository
import ru.lavafrai.openid.domain.AccountOperationCoordinator
import ru.lavafrai.openid.models.pin.SavedPinState

class DeleteAccountUseCase(
    private val accounts: AccountRepository,
    private val pins: PinRepository,
    private val operations: AccountOperationCoordinator,
) {
    /** Finishes deletion once started; repository errors propagate and the operation is retryable. */
    suspend operator fun invoke() {
        operations.mutate {
            val accountId = accounts.getSummary()?.accountId
            val pinState = pins.pinState.first { it != SavedPinState.Loading }
            val savedPinId = (pinState as? SavedPinState.Saved)?.accountId
            withContext(NonCancellable) {
                accountId?.let { pins.delete(it) }
                if (savedPinId != null && savedPinId != accountId) pins.delete(savedPinId)
                accounts.delete()
            }
        }
    }
}
