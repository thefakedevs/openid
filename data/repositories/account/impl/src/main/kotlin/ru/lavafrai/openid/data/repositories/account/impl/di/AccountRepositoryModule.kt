package ru.lavafrai.openid.data.repositories.account.impl.di

import org.koin.dsl.module
import ru.lavafrai.openid.data.repositories.account.api.AccountRepository
import ru.lavafrai.openid.data.repositories.account.impl.AccountRepositoryImpl

val accountRepositoryModule = module {
    single<AccountRepository> { AccountRepositoryImpl(get()) }
}
