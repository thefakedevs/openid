package ru.lavafrai.openid.domain.usecases

import kotlinx.coroutines.flow.StateFlow
import ru.lavafrai.openid.data.repositories.account.api.AccountRepository
import ru.lavafrai.openid.models.account.AccountState
import ru.lavafrai.openid.models.account.AccountSummary

class GetAccountStateUseCase(private val accounts: AccountRepository) {
    operator fun invoke(): StateFlow<AccountState> = accounts.accountState

    suspend fun getSummary(): AccountSummary? = accounts.getSummary()
}
