package ru.lavafrai.openid.domain.usecases

import ru.lavafrai.openid.data.repositories.account.api.AccountRepository
import ru.lavafrai.openid.data.repositories.importing.api.ImportRepository
import ru.lavafrai.openid.data.repositories.pin.api.PinRepository
import ru.lavafrai.openid.domain.AccountOperationCoordinator
import ru.lavafrai.openid.models.account.AccountSummary
import ru.lavafrai.openid.models.account.ImportedAccount

class ReplaceAccountUseCase(
    private val imports: ImportRepository,
    private val accounts: AccountRepository,
    private val pins: PinRepository,
    private val operations: AccountOperationCoordinator,
) {
    /**
     * Decodes before touching storage. Always forgets the previous PIN, including same-ID re-imports.
     * If replacement fails after PIN deletion, the previous account may remain without its saved PIN.
     * Separate repositories do not provide a shared transaction; a subsequent attempt can be retried.
     */
    suspend operator fun invoke(
        url: String,
        password: CharArray,
    ): AccountSummary = invoke(imports.decodeWithPasswordCopy(url, password))

    suspend operator fun invoke(account: ImportedAccount): AccountSummary = operations.mutate {
        val previous = checkNotNull(accounts.getSummary()) { "No account to replace" }
        pins.delete(previous.accountId)
        accounts.replace(account)
        AccountSummary(account.accountId)
    }
}
