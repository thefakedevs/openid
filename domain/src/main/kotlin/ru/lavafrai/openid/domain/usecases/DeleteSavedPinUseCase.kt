package ru.lavafrai.openid.domain.usecases

import ru.lavafrai.openid.data.repositories.pin.api.PinRepository
import ru.lavafrai.openid.domain.AccountOperationCoordinator

class DeleteSavedPinUseCase(
    private val pins: PinRepository,
    private val operations: AccountOperationCoordinator,
) {
    /** No PIN or biometric authentication is required to forget the saved PIN. */
    suspend operator fun invoke(accountId: Long) {
        require(accountId > 0) { "Account ID must be positive" }
        operations.mutate { pins.delete(accountId) }
    }
}
