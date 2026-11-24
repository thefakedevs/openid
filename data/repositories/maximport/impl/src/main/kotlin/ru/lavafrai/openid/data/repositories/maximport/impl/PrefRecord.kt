package ru.lavafrai.openid.data.repositories.maximport.impl

/** A preference entry read from MAX local storage; the value is a String or a Long. */
internal data class PrefRecord(
    val key: String,
    val value: Any,
) {
    override fun toString(): String = "PrefRecord(key=$key, value=<redacted>)"
}
