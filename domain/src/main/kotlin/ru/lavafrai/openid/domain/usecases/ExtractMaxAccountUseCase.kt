package ru.lavafrai.openid.domain.usecases

import ru.lavafrai.openid.data.repositories.maximport.api.RootImportRepository
import ru.lavafrai.openid.data.repositories.maximport.api.RootImportStage
import ru.lavafrai.openid.models.account.ImportedAccount

/** Extracts the account from local MAX storage through root; nothing is persisted yet. */
class ExtractMaxAccountUseCase(private val rootImport: RootImportRepository) {
    suspend operator fun invoke(onStage: (RootImportStage) -> Unit = {}): ImportedAccount = rootImport.extract(onStage)
}
