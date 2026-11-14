package ru.lavafrai.openid.data.repositories.maximport.impl.di

import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module
import ru.lavafrai.openid.data.repositories.max.api.MaxSessionRepository
import ru.lavafrai.openid.data.repositories.maximport.api.RootImportRepository
import ru.lavafrai.openid.data.repositories.maximport.impl.MaxRootImportRepository

val rootImportRepositoryModule = module {
    single<RootImportRepository> {
        MaxRootImportRepository(androidContext(), get<MaxSessionRepository>())
    }
}
