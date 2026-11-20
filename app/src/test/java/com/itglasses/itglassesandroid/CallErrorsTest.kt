package com.itglasses.itglassesandroid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.cert.CertPathValidatorException
import java.security.cert.CertificateException
import java.security.cert.CertificateExpiredException
import java.security.cert.CertificateNotYetValidException
import java.util.concurrent.CancellationException
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException

class CallErrorsTest {
    private val errors = CallErrors()
    private fun notice(text: CallText, arg: String? = null) = CallOutcome.Notice(text, arg)
    private fun handshake(cause: Throwable) = SSLHandshakeException("handshake").apply { initCause(cause) }

    @Test fun networkFailuresSayNoConnection() {
        for (error in listOf(ConnectException("refused"), UnknownHostException("172.16.5.143"),
                NoRouteToHostException("no route"),
                SocketTimeoutException("failed to connect to /172.16.5.143 (port 8443) from /10.0.0.2 (port 40000) after 10000ms"),
                IOException("wrapped").apply { initCause(ConnectException("refused")) })) {
            assertEquals(error.toString(), notice(CallText.NO_NETWORK), errors.describe(error))
        }
    }

    @Test fun otherSocketTimeoutsGetTheTextThatCoversBothCases() {
        // Fallback for a wording that differs between Android versions; only verifiable on a phone.
        for (error in listOf(SocketTimeoutException("timeout"), SocketTimeoutException(null),
                SocketTimeoutException("connect timed out"), SocketTimeoutException("Read timed out"))) {
            assertEquals(error.toString(), notice(CallText.NO_RESPONSE), errors.describe(error))
        }
    }

    @Test fun callTimeoutSaysTheAssistantTookTooLong() {
        assertEquals(notice(CallText.TIMEOUT), errors.describe(InterruptedIOException("timeout")))
    }

    @Test fun certificateValidityIsAClockProblemAndOtherTlsFailuresAreCertificateProblems() {
        assertEquals(notice(CallText.CLOCK),
            errors.describe(handshake(CertificateException("chain", CertificateExpiredException("NotAfter 2027")))))
        assertEquals(notice(CallText.CLOCK),
            errors.describe(handshake(CertificateException(CertificateNotYetValidException("NotBefore 2026")))))
        assertEquals(notice(CallText.CLOCK), errors.describe(handshake(CertificateException(
            CertPathValidatorException("v", null, null, -1, CertPathValidatorException.BasicReason.EXPIRED)))))
        assertEquals(notice(CallText.CERTIFICATE),
            errors.describe(handshake(CertificateException(CertPathValidatorException("Trust anchor not found")))))
        assertEquals(notice(CallText.CERTIFICATE), errors.describe(SSLPeerUnverifiedException("hostname")))
    }

    @Test fun anythingElseIsAGenericFailure() {
        for (error in listOf(SSLException("Connection reset"), IOException("closed"), IllegalStateException("bad json"))) {
            assertEquals(error.toString(), notice(CallText.FAILED), errors.describe(error))
        }
    }

    @Test fun knownStatusesHaveTheirTextAndTheRestShowTheCode() {
        assertEquals(CallOutcome.DeviceCheck, errors.describe(403))
        assertEquals(notice(CallText.REMOVED), errors.describe(410))
        assertEquals(notice(CallText.AUDIO_TOO_LONG), errors.describe(413))
        assertEquals(notice(CallText.REJECTED), errors.describe(422))
        assertEquals(notice(CallText.RATE_LIMITED), errors.describe(429))
        assertEquals(notice(CallText.SERVER_ERROR), errors.describe(500))
        for (status in listOf(400, 404, 409, 502, 503, 504)) {
            assertEquals(notice(CallText.UNEXPECTED_CODE, status.toString()), errors.describe(status))
        }
    }

    @Test fun httpFailuresThrownByCallsUseTheirStatus() {
        assertEquals(notice(CallText.REMOVED), errors.describe(CallHttpException(410)))
        assertEquals(CallOutcome.DeviceCheck, errors.describe(PairingError(403)))
    }

    @Test fun secondUnauthorizedInARowSuggestsCheckingTheClock() {
        assertEquals(notice(CallText.UNAUTHORIZED), errors.describe(401))
        assertEquals(notice(CallText.UNAUTHORIZED_REPEATED), errors.describe(CallHttpException(401)))
        assertEquals(notice(CallText.UNAUTHORIZED_REPEATED), errors.describe(401))
        errors.onSuccess()
        assertEquals(notice(CallText.UNAUTHORIZED), errors.describe(401))
        errors.describe(500)
        assertEquals(notice(CallText.UNAUTHORIZED), errors.describe(401))
        errors.describe(ConnectException("refused"))
        assertEquals(notice(CallText.UNAUTHORIZED), errors.describe(401))
    }

    @Test fun cancellationIsRethrownAndNeverShown() {
        val cancelled = CancellationException("left the app")
        assertEquals(cancelled, assertThrows(CancellationException::class.java) { errors.describe(cancelled) })
        // kotlinx.coroutines.CancellationException is the same class (a typealias).
        assertThrows(CancellationException::class.java) { errors.describe(kotlinx.coroutines.CancellationException("x")) }
    }

    @Test fun noticesNeverCarryExceptionMessagesOrBodies() {
        val secret = "token-123 {\"detail\":\"secret body\"}"
        for (error in listOf(IOException(secret), SocketTimeoutException(secret), IllegalStateException(secret),
                handshake(CertificateException(secret)))) {
            val outcome = errors.describe(error)
            assertFalse(outcome.toString(), outcome.toString().contains("token-123"))
            assertEquals(null, (outcome as CallOutcome.Notice).arg)
        }
    }

    @Test fun causeCyclesDoNotLoop() {
        val first = IOException("first")
        val second = IOException("second")
        first.initCause(second)
        second.initCause(first)
        assertEquals(notice(CallText.FAILED), errors.describe(first))
    }
}
