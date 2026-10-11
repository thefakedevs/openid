package ru.lavafrai.openid.data.repositories.digitalid.api

import ru.lavafrai.openid.models.digitalid.AuthorizationResult
import ru.lavafrai.openid.models.digitalid.DigitalIdToken
import ru.lavafrai.openid.models.digitalid.QrSession
import ru.lavafrai.openid.models.digitalid.SecurityStatus
import ru.lavafrai.openid.models.max.WebAppLaunch

interface DigitalIdRepository {
    /**
     * Returns server state. Domain validates it against the imported account before authorizing.
     * Rejects malformed or missing fields; attemptsLeft accepts integral numeric wire values only.
     */
    suspend fun getSecurityStatus(launch: WebAppLaunch): SecurityStatus

    /** Performs one attempt with no automatic retries; a negative result must stop the flow. */
    suspend fun authorize(
        launch: WebAppLaunch,
        token: DigitalIdToken,
    ): AuthorizationResult

    /**
     * Requires successful authorization with the same launch and token; no hidden login or retries.
     * Returns a nonempty session ID as the QR payload. No server TTL is assumed.
     */
    suspend fun getUniversalQr(
        launch: WebAppLaunch,
        token: DigitalIdToken,
    ): QrSession
}
