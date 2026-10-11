package ru.lavafrai.openid.navigation

/** Only destinations are navigation state. Passwords and PINs never become route arguments. */
enum class PocketDestination(val title: String) {
    START("ID Pocket"),
    IMPORT("Импорт аккаунта"),
    SETUP("Способ входа"),
    UNLOCK("Разблокировка"),
    QR("Ваш QR"),
    SETTINGS("Настройки"),
}

enum class ImportMode { NEW, REPLACE }
