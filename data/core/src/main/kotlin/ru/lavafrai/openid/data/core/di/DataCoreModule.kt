package ru.lavafrai.openid.data.core.di

import androidx.room.Room
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module
import ru.lavafrai.openid.data.core.SecretDatabase
import ru.lavafrai.openid.data.core.SecretStorage
import ru.lavafrai.openid.data.core.VaultCipher

val dataCoreModule = module {
    single { Room.databaseBuilder(androidContext(), SecretDatabase::class.java, "pocket-secrets.db").build() }
    single { VaultCipher(androidContext()) }
    single { SecretStorage(get<SecretDatabase>().secrets(), get()) }
}
