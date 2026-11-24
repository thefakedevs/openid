package ru.lavafrai.openid.domain.usecases

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlinx.coroutines.runBlocking
import ru.lavafrai.openid.domain.models.QrGenerationFailure
import ru.lavafrai.openid.domain.models.QrGenerationStage
import ru.lavafrai.openid.models.errors.DiagnosticFailure
import ru.lavafrai.openid.models.errors.FailureDiagnostic
import ru.lavafrai.openid.models.errors.FailureKind

class DigitalIdAuthorizationTest {
    @Test
    fun `successful authorization works for both profile types`() = runBlocking {
        requireDigitalIdAuthorization(capturedTokenProfile = false) { true }
        requireDigitalIdAuthorization(capturedTokenProfile = true) { true }
    }

    @Test
    fun `server rejection distinguishes a token from a PIN`() = runBlocking {
        val rejectedPin = assertFailsWith<DigitalIdAuthorizationRejectedException> {
            requireDigitalIdAuthorization(capturedTokenProfile = false) { false }
        }
        val rejectedToken = assertFailsWith<CapturedTokenRejectedException> {
            requireDigitalIdAuthorization(capturedTokenProfile = true) { false }
        }
        assertEquals(
            QrGenerationFailure.AuthorizationRejected,
            rejectedPin.toQrFailure(QrGenerationStage.AUTHORIZING).reason,
        )
        assertEquals(
            QrGenerationFailure.CapturedTokenRejected,
            rejectedToken.toQrFailure(QrGenerationStage.AUTHORIZING).reason,
        )
    }

    @Test
    fun `HTTP 400 is a token rejection only for the captured token profile`() = runBlocking {
        val badRequest = HttpFailure(400)
        val pinFailure = assertFailsWith<HttpFailure> {
            requireDigitalIdAuthorization(capturedTokenProfile = false) { throw badRequest }
        }
        val rejectedToken = assertFailsWith<CapturedTokenRejectedException> {
            requireDigitalIdAuthorization(capturedTokenProfile = true) { throw badRequest }
        }
        assertSame(badRequest, pinFailure)
        assertSame(badRequest, rejectedToken.cause)
    }

    @Test
    fun `transient server errors retain original diagnostics`() = runBlocking {
        val unavailable = HttpFailure(503)
        val failure = assertFailsWith<HttpFailure> {
            requireDigitalIdAuthorization(capturedTokenProfile = true) { throw unavailable }
        }
        assertSame(unavailable, failure)
    }

    private class HttpFailure(status: Int) :
        Exception(),
        DiagnosticFailure {
        override val diagnostic = FailureDiagnostic.capture(
            "DIGITAL_ID_HTTP_REJECTED_$status",
            FailureKind.SERVER,
            this,
        )
    }
}
