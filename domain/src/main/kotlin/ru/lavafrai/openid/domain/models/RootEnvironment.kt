package ru.lavafrai.openid.domain.models

/** Availability facts shown on the automatic import source screen. */
class RootEnvironment(
    val maxInstalled: Boolean,
    val rootAvailable: Boolean,
    val suManagers: List<String>,
) {
    override fun toString(): String =
        "RootEnvironment(maxInstalled=$maxInstalled, rootAvailable=$rootAvailable, suManagers=${suManagers.size})"
}
