package com.itglasses.itglassesandroid

import com.itglasses.itglassesandroid.PairingEvent.CertificateError
import com.itglasses.itglassesandroid.PairingEvent.ClockError
import com.itglasses.itglassesandroid.PairingEvent.Http
import com.itglasses.itglassesandroid.PairingEvent.NetworkError
import java.io.IOException
import java.security.cert.CertPathValidatorException
import java.security.cert.CertificateExpiredException
import java.security.cert.CertificateNotYetValidException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException

/** An HTTP error from a pairing call. PairingError (DevicePairing.kt) implements it in tanda 2b. */
internal interface HttpFailure {
    val status: Int
    val retryAfter: Int?
}

/** Exceptions from a pairing call -> PairingEvent. Pure Kotlin (no android.*). */
internal object PairingErrors {
    private const val MAX_CAUSES = 16

    /**
     * clockImplausible: the last Date header was more than 24 h away from the phone clock.
     * A certificate that is "expired" or "not yet valid" usually means the phone clock is wrong (the pilot CA is
     * valid from 13/11/2026 to 13/11/2027), so it is reported as a clock error, not as an untrusted certificate.
     */
    fun event(error: Throwable, during: PairingAction, clockImplausible: Boolean): PairingEvent {
        val chain = causes(error)
        return when {
            error is HttpFailure ->
                if (error.status == 401 && clockImplausible) ClockError(during)
                else Http(error.status, during, error.retryAfter?.coerceIn(1, PairingMachine.RETRY_AFTER_MAX))
            chain.any(::isValidityError) -> ClockError(during)
            chain.any { it is SSLHandshakeException || it is SSLPeerUnverifiedException } -> CertificateError(during)
            error is IOException -> NetworkError(during)
            else -> Http(0, during)  // Unexpected reply or key error: ends in Failed with a retry button.
        }
    }

    /** Retry-After in delta-seconds, limited to 1..300; an HTTP-date or anything else gives null (default wait). */
    fun retryAfter(header: String?): Int? {
        val text = header?.trim() ?: return null
        if (text.isEmpty() || !text.all { it in '0'..'9' }) return null
        val seconds = text.toLongOrNull() ?: Long.MAX_VALUE
        return seconds.coerceIn(1L, PairingMachine.RETRY_AFTER_MAX.toLong()).toInt()
    }

    private fun causes(error: Throwable): List<Throwable> {
        val chain = mutableListOf<Throwable>()
        var current: Throwable? = error
        while (current != null && chain.size < MAX_CAUSES && chain.none { it === current }) {
            chain.add(current)
            current = current.cause
        }
        return chain
    }

    private fun isValidityError(error: Throwable): Boolean =
        error is CertificateExpiredException || error is CertificateNotYetValidException ||
            (error is CertPathValidatorException &&
                (error.reason == CertPathValidatorException.BasicReason.EXPIRED ||
                    error.reason == CertPathValidatorException.BasicReason.NOT_YET_VALID))
}
