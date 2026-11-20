package com.itglasses.itglassesandroid

import com.itglasses.itglassesandroid.PairingAction.POLL
import com.itglasses.itglassesandroid.PairingAction.REGISTER
import com.itglasses.itglassesandroid.PairingAction.VERIFY
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.GeneralSecurityException
import java.security.cert.CertPathValidatorException
import java.security.cert.CertificateException
import java.security.cert.CertificateExpiredException
import java.security.cert.CertificateNotYetValidException
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException

class PairingErrorsTest {
    private class HttpError(override val status: Int, override val retryAfter: Int? = null) :
        IOException("HTTP $status"), HttpFailure

    private fun handshake(cause: Throwable) = SSLHandshakeException("handshake").apply { initCause(cause) }
    private fun event(error: Throwable, clockImplausible: Boolean = false) =
        PairingErrors.event(error, POLL, clockImplausible)

    @Test fun httpFailuresKeepTheirStatusAndLimitRetryAfter() {
        assertEquals(PairingEvent.Http(403, POLL, null), event(HttpError(403)))
        assertEquals(PairingEvent.Http(429, POLL, 300), event(HttpError(429, 900)))
        assertEquals(PairingEvent.Http(429, POLL, 1), event(HttpError(429, 0)))
    }

    @Test fun unauthorizedIsAClockErrorOnlyWhenTheClockIsImplausible() {
        assertEquals(PairingEvent.ClockError(POLL), event(HttpError(401), clockImplausible = true))
        assertEquals(PairingEvent.Http(401, POLL, null), event(HttpError(401), clockImplausible = false))
        assertEquals(PairingEvent.Http(403, POLL, null), event(HttpError(403), clockImplausible = true))
    }

    @Test fun expiredCertificateInTheCauseChainIsAClockError() {
        // As Android reports it: SSLHandshakeException <- CertificateException <- CertificateExpiredException.
        val expired = CertificateExpiredException("NotAfter: Sat Nov 13 00:00:00 GMT 2027")
        val error = handshake(CertificateException("java.security.cert.CertPathValidatorException", expired))
        assertEquals(PairingEvent.ClockError(POLL), event(error))
    }

    @Test fun notYetValidCertificateInTheCauseChainIsAClockError() {
        // Phone clock before 13/11/2026, when the pilot CA becomes valid.
        val notYet = CertificateNotYetValidException("NotBefore: Fri Nov 13 00:00:00 GMT 2026")
        val error = handshake(CertificateException(CertPathValidatorException("validity", notYet)))
        assertEquals(PairingEvent.ClockError(REGISTER), PairingErrors.event(error, REGISTER, false))
    }

    @Test fun certPathValidityReasonsAreClockErrors() {
        for (reason in listOf(CertPathValidatorException.BasicReason.EXPIRED, CertPathValidatorException.BasicReason.NOT_YET_VALID)) {
            val error = handshake(CertificateException(CertPathValidatorException("validity", null, null, -1, reason)))
            assertEquals(reason.name, PairingEvent.ClockError(POLL), event(error))
        }
    }

    @Test fun untrustedCertificateIsACertificateError() {
        val untrusted = handshake(CertificateException(CertPathValidatorException("Trust anchor for certification path not found.")))
        assertEquals(PairingEvent.CertificateError(POLL), event(untrusted))
        assertEquals(PairingEvent.CertificateError(POLL), event(SSLPeerUnverifiedException("Hostname 172.16.5.143 not verified")))
    }

    @Test fun otherIoErrorsAreNetworkErrors() {
        for (error in listOf(ConnectException("refused"), SocketTimeoutException("timeout"),
                UnknownHostException("host"), SSLException("Connection reset"), IOException("closed"))) {
            assertEquals(error.toString(), PairingEvent.NetworkError(POLL), event(error))
        }
    }

    @Test fun unexpectedErrorsEndInFailed() {
        assertEquals(PairingEvent.Http(0, VERIFY), PairingErrors.event(IllegalStateException("bad json"), VERIFY, false))
        assertEquals(PairingEvent.Http(0, VERIFY), PairingErrors.event(GeneralSecurityException("keystore"), VERIFY, false))
    }

    @Test fun causeCyclesDoNotLoop() {
        val first = IOException("first")
        val second = IOException("second")
        first.initCause(second)
        second.initCause(first)
        assertEquals(PairingEvent.NetworkError(POLL), event(first))
    }

    @Test fun retryAfterHeaderIsParsedAndLimited() {
        assertEquals(42, PairingErrors.retryAfter("42"))
        assertEquals(7, PairingErrors.retryAfter(" 7 "))
        assertEquals(1, PairingErrors.retryAfter("0"))
        assertEquals(300, PairingErrors.retryAfter("100000"))
        assertEquals(300, PairingErrors.retryAfter("99999999999999999999999"))
        for (value in listOf(null, "", "-5", "1.5", "Wed, 21 Oct 2015 07:28:00 GMT")) {
            assertNull(value, PairingErrors.retryAfter(value))
        }
    }
}
