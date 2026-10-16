package ru.lavafrai.openid.models.errors

/** Stable, typed causes used for recovery. Diagnostic codes remain human-readable report details. */
enum class FailureReason(val diagnosticCode: String) {
    UNSPECIFIED("UNEXPECTED"),
    INVALID_IMPORT_LINK("IMPORT_URL_INVALID"),
    INVALID_IMPORT_PASSWORD("PASSPHRASE_MUST_BE_ASCII_12_TO_256"),
    IMPORT_DECRYPTION_FAILED("WRONG_PASSPHRASE_OR_DAMAGED_LINK"),
    STORAGE_KEY_MISSING("STORAGE_KEY_MISSING"),
    PIN_NOT_SAVED("PIN_NOT_SAVED"),
    BIOMETRIC_KEY_INVALIDATED("AUTH_KEY_INVALIDATED"),
}
