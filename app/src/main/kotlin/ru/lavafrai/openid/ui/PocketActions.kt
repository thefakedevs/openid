package ru.lavafrai.openid.ui

import ru.lavafrai.openid.domain.usecases.CheckRootEnvironmentUseCase
import ru.lavafrai.openid.domain.usecases.DeleteAccountUseCase
import ru.lavafrai.openid.domain.usecases.DeleteSavedPinUseCase
import ru.lavafrai.openid.domain.usecases.ExtractMaxAccountUseCase
import ru.lavafrai.openid.domain.usecases.GenerateQrUseCase
import ru.lavafrai.openid.domain.usecases.GetAccountStateUseCase
import ru.lavafrai.openid.domain.usecases.GetSavedPinStateUseCase
import ru.lavafrai.openid.domain.usecases.ImportAccountUseCase
import ru.lavafrai.openid.domain.usecases.MigrateAccountStorageUseCase
import ru.lavafrai.openid.domain.usecases.ReplaceAccountUseCase
import ru.lavafrai.openid.domain.usecases.SavePinUseCase

class PocketActions(
    val accountState: GetAccountStateUseCase,
    val savedPinState: GetSavedPinStateUseCase,
    val importAccount: ImportAccountUseCase,
    val replaceAccount: ReplaceAccountUseCase,
    val generateQr: GenerateQrUseCase,
    val savePin: SavePinUseCase,
    val migrateAccountStorage: MigrateAccountStorageUseCase,
    val deleteSavedPin: DeleteSavedPinUseCase,
    val deleteAccount: DeleteAccountUseCase,
    val extractMaxAccount: ExtractMaxAccountUseCase,
    val checkRootEnvironment: CheckRootEnvironmentUseCase,
    val runDfRoot: suspend (suManager: String, report: (String) -> Unit) -> Unit,
)
