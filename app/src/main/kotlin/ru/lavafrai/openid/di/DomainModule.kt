package ru.lavafrai.openid.di

import org.koin.dsl.module
import ru.lavafrai.openid.domain.AccountOperationCoordinator
import ru.lavafrai.openid.domain.usecases.CheckRootEnvironmentUseCase
import ru.lavafrai.openid.domain.usecases.CreateDigitalIdPinTokenUseCase
import ru.lavafrai.openid.domain.usecases.DeleteAccountUseCase
import ru.lavafrai.openid.domain.usecases.DeleteSavedPinUseCase
import ru.lavafrai.openid.domain.usecases.ExtractMaxAccountUseCase
import ru.lavafrai.openid.domain.usecases.GenerateQrUseCase
import ru.lavafrai.openid.domain.usecases.GetAccountStateUseCase
import ru.lavafrai.openid.domain.usecases.GetSavedPinStateUseCase
import ru.lavafrai.openid.domain.usecases.ImportAccountUseCase
import ru.lavafrai.openid.domain.usecases.MigrateAccountStorageUseCase
import ru.lavafrai.openid.domain.usecases.ReplaceAccountUseCase
import ru.lavafrai.openid.domain.usecases.ReturnToAppUseCase
import ru.lavafrai.openid.domain.usecases.SavePinUseCase
import ru.lavafrai.openid.domain.usecases.UnlockAccountUseCase
import ru.lavafrai.openid.domain.usecases.UnlockPinUseCase
import ru.lavafrai.openid.domain.usecases.ValidateDigitalIdSecurityStatusUseCase

val domainModule = module {
    single { AccountOperationCoordinator() }
    factory { CreateDigitalIdPinTokenUseCase() }
    factory { ValidateDigitalIdSecurityStatusUseCase() }
    factory { ImportAccountUseCase(get(), get(), get()) }
    factory { GetAccountStateUseCase(get()) }
    factory { UnlockAccountUseCase(get(), get()) }
    factory { SavePinUseCase(get(), get()) }
    factory { UnlockPinUseCase(get(), get()) }
    factory { GetSavedPinStateUseCase(get()) }
    factory { DeleteSavedPinUseCase(get(), get()) }
    factory { MigrateAccountStorageUseCase(get(), get()) }
    factory { ReplaceAccountUseCase(get(), get(), get(), get()) }
    factory { DeleteAccountUseCase(get(), get(), get()) }
    factory { GenerateQrUseCase(get(), get(), get(), get(), get(), get(), get(), get()) }
    factory { ExtractMaxAccountUseCase(get()) }
    factory { CheckRootEnvironmentUseCase(get()) }
    factory { ReturnToAppUseCase(get()) }
}
