package ru.lavafrai.openid.domain.usecases

import ru.lavafrai.openid.data.repositories.account.api.AccountRepository
import ru.lavafrai.openid.domain.AccountOperationCoordinator
import ru.lavafrai.openid.models.auth.CryptoAuthenticator

class MigrateAccountStorageUseCase(
    private val accounts: AccountRepository,
    private val operations: AccountOperationCoordinator,
) {
    suspend operator fun invoke(authenticator: CryptoAuthenticator) = operations.mutate {
        accounts.migrateLegacyProtection(authenticator)
    }
}
