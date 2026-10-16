package ru.lavafrai.openid.navigation

/** Only destinations are navigation state. Passwords and PINs never become route arguments. */
enum class PocketDestination {
    START,
    IMPORT,
    SETUP,
    UNLOCK,
    QR,
    SETTINGS,
}

enum class ImportMode { NEW, REPLACE }
