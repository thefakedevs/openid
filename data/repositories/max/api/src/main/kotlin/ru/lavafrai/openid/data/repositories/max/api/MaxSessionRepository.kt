package ru.lavafrai.openid.data.repositories.max.api

import ru.lavafrai.openid.models.max.MaxClientProfile
import ru.lavafrai.openid.models.max.MaxCredentials

interface MaxSessionRepository {
    /** Opens a caller-owned connection and completes MAX initialization; login remains explicit. */
    suspend fun open(
        credentials: MaxCredentials,
        clientProfile: MaxClientProfile,
    ): MaxSession
}
