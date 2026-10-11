package ru.lavafrai.openid.domain.usecases

import kotlinx.coroutines.flow.StateFlow
import ru.lavafrai.openid.data.repositories.pin.api.PinRepository
import ru.lavafrai.openid.models.pin.SavedPinState

class GetSavedPinStateUseCase(private val pins: PinRepository) {
    operator fun invoke(): StateFlow<SavedPinState> = pins.pinState
}
