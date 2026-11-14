package ru.lavafrai.openid.domain.usecases

import ru.lavafrai.openid.data.repositories.maximport.api.RootImportRepository
import ru.lavafrai.openid.domain.models.RootEnvironment

/** Probes root access and installed packages for the automatic import source screen. */
class CheckRootEnvironmentUseCase(private val rootImport: RootImportRepository) {
    suspend operator fun invoke(): RootEnvironment = RootEnvironment(
        maxInstalled = rootImport.isMaxInstalled(),
        dfRootInstalled = rootImport.isDfRootInstalled(),
        rootAvailable = rootImport.checkRootAccess(),
    )
}
