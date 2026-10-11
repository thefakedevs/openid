package ru.lavafrai.openid.data.repositories.importing.impl.di

import org.koin.dsl.module
import ru.lavafrai.openid.data.repositories.importing.api.ImportRepository
import ru.lavafrai.openid.data.repositories.importing.impl.ImportRepositoryImpl

val importRepositoryModule = module {
    single<ImportRepository> { ImportRepositoryImpl() }
}
