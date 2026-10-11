package ru.lavafrai.openid.data.repositories.max.impl.di

import org.koin.dsl.module
import ru.lavafrai.openid.data.repositories.max.api.MaxSessionRepository
import ru.lavafrai.openid.data.repositories.max.impl.MaxSessionRepositoryImpl

val maxSessionRepositoryModule = module {
    single<MaxSessionRepository> { MaxSessionRepositoryImpl() }
}
