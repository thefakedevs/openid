package ru.lavafrai.openid.domain.usecases

import ru.lavafrai.openid.data.repositories.maximport.api.RootImportRepository

/** Brings the app back from MAX once the import no longer needs the user there. Best effort. */
class ReturnToAppUseCase(private val rootImport: RootImportRepository) {
    suspend operator fun invoke() = rootImport.returnToApp()
}
