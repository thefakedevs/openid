package ru.lavafrai.openid.data.repositories.maximport.impl

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * [buildReturnToAppCommand] is the one piece of [MaxRootImportRepository.returnToApp] testable
 * without a device: the rest (resolving this app's own component and Android user id) needs real
 * `PackageManager`/`Process` APIs.
 */
class MaxRootImportRepositoryCommandTest {
    @Test
    fun `targets the caller's own Android profile, not the foreground user`() {
        // A work-profile OpenID (user 10) must not fall back to `am start`'s default of the
        // foreground user (often 0), or it opens the wrong copy of the app - or none at all.
        assertEquals(
            "am start -a android.intent.action.MAIN -c android.intent.category.LAUNCHER " +
                "-n ru.lavafrai.openid.debug/ru.lavafrai.openid.MainActivity --user 10",
            buildReturnToAppCommand("ru.lavafrai.openid.debug/ru.lavafrai.openid.MainActivity", userId = 10),
        )
    }

    @Test
    fun `targets the primary profile when the app runs there`() {
        assertEquals(
            "am start -a android.intent.action.MAIN -c android.intent.category.LAUNCHER " +
                "-n ru.lavafrai.openid/ru.lavafrai.openid.MainActivity --user 0",
            buildReturnToAppCommand("ru.lavafrai.openid/ru.lavafrai.openid.MainActivity", userId = 0),
        )
    }
}
