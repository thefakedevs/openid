package ru.lavafrai.openid.di

import org.koin.android.ext.koin.androidContext
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module
import ru.lavafrai.openid.ui.PinProtectionPreferences
import ru.lavafrai.openid.ui.PocketActions
import ru.lavafrai.openid.ui.PocketViewModel
import ru.lavafrai.openid.ui.dfroot.DfRootRunner

val uiModule = module {
    factory {
        PocketActions(
            accountState = get(), savedPinState = get(), importAccount = get(), replaceAccount = get(),
            generateQr = get(), savePin = get(), migrateAccountStorage = get(),
            deleteSavedPin = get(), deleteAccount = get(),
            extractMaxAccount = get(), checkRootEnvironment = get(),
            runDfRoot = { suManager, report -> DfRootRunner.run(androidContext(), suManager, report) },
        )
    }
    single { PinProtectionPreferences(androidContext()) }
    viewModel { PocketViewModel(get(), get()) }
}
