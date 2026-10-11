package ru.lavafrai.openid.data.repositories.digitalid.impl.di

import org.koin.dsl.module
import ru.lavafrai.openid.data.repositories.digitalid.api.DigitalIdRepository
import ru.lavafrai.openid.data.repositories.digitalid.impl.DigitalIdRepositoryImpl

val digitalIdRepositoryModule = module {
    single<DigitalIdRepository> { DigitalIdRepositoryImpl() }
}
