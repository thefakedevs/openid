package ru.lavafrai.openid.data.repositories.max.api

import ru.lavafrai.openid.models.max.MaxLoginResult
import ru.lavafrai.openid.models.max.WebAppLaunch

interface MaxSession : AutoCloseable {
    /**
     * Performs one login and caches its result. Loads the profile through LOGIN2 when announced by the server.
     * Requires a profile; rejects a reported account ID mismatch.
     * A rotated token is returned to the caller; this session never persists it.
     */
    suspend fun login(): MaxLoginResult

    /**
     * After login: imported hint, cached init reply, cached login reply, then the lookup request.
     * Returns a positive bot ID; fails if none can be resolved. Invalid imported hints are rejected.
     */
    suspend fun resolveDigitalIdBotId(importedBotId: Long? = null): Long

    /** Requires login and a positive bot ID. Validates HTTPS digital-id.max.ru, signed data and account ID. */
    suspend fun getWebAppLaunch(botId: Long): WebAppLaunch

    /** Idempotently closes the connection; subsequent operations fail. */
    override fun close()
}
