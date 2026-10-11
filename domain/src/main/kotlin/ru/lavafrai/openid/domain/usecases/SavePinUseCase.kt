package ru.lavafrai.openid.domain.usecases

import ru.lavafrai.openid.data.repositories.pin.api.PinRepository
import ru.lavafrai.openid.domain.AccountOperationCoordinator
import ru.lavafrai.openid.domain.models.VerifiedPin
import ru.lavafrai.openid.domain.models.validatePin
import ru.lavafrai.openid.models.auth.CryptoAuthenticator

class SavePinUseCase(
    private val pins: PinRepository,
    private val operations: AccountOperationCoordinator,
) {
    /** Only a server-verified PIN can be persisted, always with biometric protection. */
    suspend operator fun invoke(
        pin: VerifiedPin,
        authenticator: CryptoAuthenticator,
    ) {
        operations.access {
            val buffer = pin.copyPin()
            try {
                validatePin(buffer)
                pins.save(pin.accountId, buffer, authenticator)
            } finally {
                buffer.fill('\u0000')
            }
        }
    }
}
