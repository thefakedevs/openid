package ru.lavafrai.openid.domain.usecases

import ru.lavafrai.openid.data.repositories.account.api.AccountRepository
import ru.lavafrai.openid.data.repositories.importing.api.ImportRepository
import ru.lavafrai.openid.domain.AccountOperationCoordinator
import ru.lavafrai.openid.models.account.AccountSummary

class ImportAccountUseCase(
    private val imports: ImportRepository,
    private val accounts: AccountRepository,
    private val operations: AccountOperationCoordinator,
) {
    /** Copies and clears the password copy. The caller remains responsible for its original buffer. */
    suspend operator fun invoke(
        url: String,
        password: CharArray,
    ): AccountSummary {
        val account = imports.decodeWithPasswordCopy(url, password)
        return operations.access {
            check(accounts.getSummary() == null) { "An account already exists; use replacement" }
            accounts.save(account)
            AccountSummary(account.accountId)
        }
    }
}
