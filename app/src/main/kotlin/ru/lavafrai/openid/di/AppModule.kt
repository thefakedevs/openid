package ru.lavafrai.openid.di

import org.koin.dsl.module
import ru.lavafrai.openid.data.core.di.dataCoreModule
import ru.lavafrai.openid.data.repositories.account.impl.di.accountRepositoryModule
import ru.lavafrai.openid.data.repositories.digitalid.impl.di.digitalIdRepositoryModule
import ru.lavafrai.openid.data.repositories.importing.impl.di.importRepositoryModule
import ru.lavafrai.openid.data.repositories.max.impl.di.maxSessionRepositoryModule
import ru.lavafrai.openid.data.repositories.pin.impl.di.pinRepositoryModule

val appModule = module {
    includes(
        domainModule,
        dataCoreModule,
        importRepositoryModule,
        accountRepositoryModule,
        pinRepositoryModule,
        maxSessionRepositoryModule,
        digitalIdRepositoryModule,
        uiModule,
    )
}
