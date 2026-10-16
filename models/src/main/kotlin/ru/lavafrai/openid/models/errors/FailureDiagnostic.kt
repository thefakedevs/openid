package ru.lavafrai.openid.models.errors

import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.UnknownHostException
import java.util.Collections
import java.util.IdentityHashMap
import javax.net.ssl.SSLException

enum class FailureKind {
    NETWORK,
    TIMEOUT,
    TLS,
    SERVER,
    RATE_LIMIT,
    ACCESS_DENIED,
    STORAGE,
    INVALID_DATA,
    UNKNOWN,
}

interface DiagnosticFailure {
    val diagnostic: FailureDiagnostic
}

/** Safe to display/copy: no exception messages, URLs, response bodies or secret-bearing objects. */
@ConsistentCopyVisibility
data class FailureDiagnostic private constructor(
    val code: String,
    val kind: FailureKind,
    val trace: String,
    val reason: FailureReason,
) {
    val canRetryRequest: Boolean get() = kind in setOf(
        FailureKind.NETWORK,
        FailureKind.TIMEOUT,
        FailureKind.TLS,
        FailureKind.SERVER,
        FailureKind.RATE_LIMIT,
    )

    companion object {
        fun from(failure: Throwable): FailureDiagnostic =
            (failure as? DiagnosticFailure)?.diagnostic ?: capture("UNEXPECTED", FailureKind.UNKNOWN, failure)

        fun capture(
            code: String,
            fallback: FailureKind,
            failure: Throwable,
            reason: FailureReason = FailureReason.UNSPECIFIED,
        ): FailureDiagnostic {
            val causes = generateSequence(failure) { it.cause }.take(8).toList()
            val kind = when {
                causes.any { it is SSLException } -> FailureKind.TLS

                causes.any { it is InterruptedIOException } -> FailureKind.TIMEOUT

                causes.any {
                    it is UnknownHostException || it is ConnectException || it is NoRouteToHostException ||
                        it is SocketException
                } -> FailureKind.NETWORK

                causes.any { it.javaClass.name.startsWith("android.database.sqlite.") } -> FailureKind.STORAGE

                else -> fallback
            }
            val visited = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
            val trace = buildString {
                fun appendFailure(
                    value: Throwable,
                    prefix: String,
                    depth: Int,
                ) {
                    if (depth >= 8 || visited.size >= 12 || !visited.add(value)) return
                    append(prefix).appendLine(value.javaClass.name.symbol())
                    value.stackTrace.take(32).forEach { frame ->
                        append("    at ").append(frame.className.symbol()).append('.').append(frame.methodName.symbol())
                        append('(').append(frame.fileName?.symbol() ?: "Unknown Source")
                        if (frame.lineNumber >= 0) append(':').append(frame.lineNumber)
                        appendLine(")")
                    }
                    if (value.stackTrace.size > 32) appendLine("    …")
                    value.suppressed.take(2).forEach { appendFailure(it, "Suppressed: ", depth + 1) }
                    value.cause?.let { appendFailure(it, "Caused by: ", depth + 1) }
                }
                appendFailure(failure, "", 0)
            }.take(24_000)
            val safeCode = code.takeIf { it.matches(Regex("[A-Z][A-Z0-9_]{0,95}")) } ?: "UNEXPECTED"
            return FailureDiagnostic(safeCode, kind, trace, reason)
        }

        private fun String.symbol(): String = take(180).filter { it.isLetterOrDigit() || it in "_.$<>-" }
    }
}
