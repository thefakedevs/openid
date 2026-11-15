package ru.lavafrai.openid.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope

class PocketViewModel(
    actions: PocketActions,
    pinPreferences: PinProtectionPreferences,
    context: Context,
) : ViewModel() {
    val controller = PocketController(actions, viewModelScope, pinPreferences, context)

    override fun onCleared() {
        controller.dispose()
    }
}
