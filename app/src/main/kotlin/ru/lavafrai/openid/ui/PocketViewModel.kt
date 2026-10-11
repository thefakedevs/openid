package ru.lavafrai.openid.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope

class PocketViewModel(
    actions: PocketActions,
    pinPreferences: PinProtectionPreferences,
) : ViewModel() {
    val controller = PocketController(actions, viewModelScope, pinPreferences)

    override fun onCleared() {
        controller.dispose()
    }
}
