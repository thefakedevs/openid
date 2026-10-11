package ru.lavafrai.openid.domain.usecases

import ru.lavafrai.openid.data.repositories.account.api.AccountRepository
import ru.lavafrai.openid.domain.AccountOperationCoordinator
import ru.lavafrai.openid.models.account.AccountLease

class UnlockAccountUseCase(
    private val accounts: AccountRepository,
    private val operations: AccountOperationCoordinator,
) {
    /** The caller owns the returned lease and must close it. */
    suspend operator fun invoke(): AccountLease = operations.access {
        accounts.unlock()
    }
}
