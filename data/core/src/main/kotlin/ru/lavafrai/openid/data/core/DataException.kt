package ru.lavafrai.openid.data.core

import ru.lavafrai.openid.models.errors.DiagnosticFailure
import ru.lavafrai.openid.models.errors.FailureDiagnostic
import ru.lavafrai.openid.models.errors.FailureKind
import ru.lavafrai.openid.models.errors.FailureReason

/** Retains only a sanitized snapshot of the original failure, never its raw message or cause. */
class DataException(
    val code: String,
    failure: Throwable? = null,
) : IllegalStateException(code),
    DiagnosticFailure {
    constructor(reason: FailureReason, failure: Throwable? = null) : this(reason.diagnosticCode, failure)

    override val diagnostic = FailureDiagnostic.capture(
        code,
        code.failureKind(),
        failure ?: this,
        FailureReason.entries.firstOrNull { it.diagnosticCode == code } ?: FailureReason.UNSPECIFIED,
    )
}

private fun String.failureKind(): FailureKind = when {
    endsWith("_TIMEOUT") -> FailureKind.TIMEOUT

    endsWith("_CONNECTION_FAILED") || this == "MAX_TCP_CLOSED" -> FailureKind.NETWORK

    this == "DIGITAL_ID_HTTP_REJECTED_429" -> FailureKind.RATE_LIMIT

    this in setOf("DIGITAL_ID_HTTP_REJECTED_401", "DIGITAL_ID_HTTP_REJECTED_403", "DID_AUTHORIZATION_REQUIRED") ->
        FailureKind.ACCESS_DENIED

    startsWith("DIGITAL_ID_HTTP_REJECTED_5") || startsWith("MAX_SERVER_REJECTED_") -> FailureKind.SERVER

    startsWith("STORAGE_") || endsWith("_LEASE_REVOKED") -> FailureKind.STORAGE

    else -> FailureKind.INVALID_DATA
}

fun requireData(
    condition: Boolean,
    code: String,
) {
    if (!condition) throw DataException(code)
}

fun requireData(
    condition: Boolean,
    reason: FailureReason,
) {
    if (!condition) throw DataException(reason)
}
