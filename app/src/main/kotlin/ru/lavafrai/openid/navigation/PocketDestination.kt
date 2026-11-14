package ru.lavafrai.openid.navigation

/** Only destinations are navigation state. Passwords and PINs never become route arguments. */
enum class PocketDestination {
    START,
    METHOD,
    IMPORT,
    ROOT_SOURCE,
    SETUP,
    UNLOCK,
    QR,
    SETTINGS,
}

enum class ImportMode { NEW, REPLACE }

/** How the account reaches the device; manual is the legacy encrypted-link path. */
enum class ImportMethod { AUTO, MANUAL }

/** Which root provider performs the automatic MAX import. */
enum class RootSource { READY_ROOT, DFROOT }
