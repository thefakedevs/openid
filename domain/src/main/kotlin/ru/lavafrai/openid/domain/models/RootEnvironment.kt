package ru.lavafrai.openid.domain.models

/** Availability facts shown on the automatic import source screen. */
class RootEnvironment(
    val maxInstalled: Boolean,
    val dfRootInstalled: Boolean,
    val rootAvailable: Boolean,
) {
    override fun toString(): String =
        "RootEnvironment(maxInstalled=$maxInstalled, dfRootInstalled=$dfRootInstalled, rootAvailable=$rootAvailable)"
}
