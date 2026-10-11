package ru.lavafrai.openid.data.repositories.pin.impl.di

import org.koin.dsl.module
import ru.lavafrai.openid.data.repositories.pin.api.PinRepository
import ru.lavafrai.openid.data.repositories.pin.impl.PinRepositoryImpl

val pinRepositoryModule = module {
    single<PinRepository> { PinRepositoryImpl(get()) }
}
