package ru.lavafrai.openid

import android.app.Application
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin
import ru.lavafrai.openid.di.appModule

class PocketApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        startKoin {
            androidContext(this@PocketApplication)
            modules(appModule)
        }
    }
}
