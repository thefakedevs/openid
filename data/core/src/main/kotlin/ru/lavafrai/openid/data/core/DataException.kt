package ru.lavafrai.openid.data.core

/** Codes are constants. Never attach response bodies, URLs, credentials or underlying exceptions. */
class DataException(val code: String) : IllegalStateException(code)

fun requireData(
    condition: Boolean,
    code: String,
) {
    if (!condition) throw DataException(code)
}
