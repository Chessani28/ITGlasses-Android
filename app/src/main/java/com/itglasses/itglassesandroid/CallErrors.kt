package com.itglasses.itglassesandroid

import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.cert.CertPathValidatorException
import java.security.cert.CertificateExpiredException
import java.security.cert.CertificateNotYetValidException
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException

/** Text keys for failed chat and voice calls (pairing mode). CallTexts maps them to strings.xml. */
internal enum class CallText {
    NO_NETWORK, NO_RESPONSE, TIMEOUT, CERTIFICATE, CLOCK,
    UNAUTHORIZED, UNAUTHORIZED_REPEATED, REMOVED, AUDIO_TOO_LONG, REJECTED, RATE_LIMITED,
    SERVER_ERROR, UNEXPECTED_CODE, FAILED, GENERATING,
}

internal sealed class CallOutcome {
    /** Show this text (arg fills its %1$s). Never an exception message or a response body. */
    data class Notice(val text: CallText, val arg: String? = null) : CallOutcome()
    /** 403: PairingController is checking /pairing/status; show its CallCheck message instead. */
    data object DeviceCheck : CallOutcome()
}

/** Non-2xx status of a voice call, thrown instead of a RuntimeException carrying the code as text. */
internal class CallHttpException(override val status: Int) : IOException("HTTP $status"), HttpFailure {
    override val retryAfter: Int? get() = null
}

/**
 * Failed call -> what the user sees. Pure Kotlin (no android.*). Thread-safe: chat and voice may fail at once.
 * A network failure never changes the pairing card: only the server (403, 410) does, through PairingController.
 */
internal class CallErrors {
    private val unauthorizedInARow = AtomicInteger(0)

    /** A call succeeded: the next 401 counts as the first one again. */
    fun onSuccess() {
        unauthorizedInARow.set(0)
    }

    /** Non-2xx response. */
    fun describe(status: Int): CallOutcome {
        if (status != 401) unauthorizedInARow.set(0)
        return when (status) {
            401 -> CallOutcome.Notice(
                if (unauthorizedInARow.incrementAndGet() >= 2) CallText.UNAUTHORIZED_REPEATED else CallText.UNAUTHORIZED)
            403 -> CallOutcome.DeviceCheck
            410 -> CallOutcome.Notice(CallText.REMOVED)
            413 -> CallOutcome.Notice(CallText.AUDIO_TOO_LONG)
            422 -> CallOutcome.Notice(CallText.REJECTED)
            429 -> CallOutcome.Notice(CallText.RATE_LIMITED)
            500 -> CallOutcome.Notice(CallText.SERVER_ERROR)
            // 502, 503, 504 and anything else: what Caddy or the backend really send is checked on the phone.
            else -> CallOutcome.Notice(CallText.UNEXPECTED_CODE, status.toString())
        }
    }

    /** Exception from a call. A CancellationException (leaving the app mid-call) is rethrown, never shown. */
    fun describe(error: Throwable): CallOutcome {
        if (error is CancellationException) throw error
        if (error is HttpFailure) return describe(error.status)
        unauthorizedInARow.set(0)
        val chain = causes(error)
        val text = when {
            chain.any(::isValidityError) -> CallText.CLOCK
            chain.any { it is SSLHandshakeException || it is SSLPeerUnverifiedException } -> CallText.CERTIFICATE
            chain.any { it is ConnectException || it is UnknownHostException || it is NoRouteToHostException } ->
                CallText.NO_NETWORK
            // OkHttp's connect timeout says "failed to connect to ..." (seen on Android 17). The wording may change
            // between versions, so any other SocketTimeoutException gets a text that covers both cases.
            // Only verifiable on a phone.
            error is SocketTimeoutException ->
                if (error.message?.startsWith("failed to connect") == true) CallText.NO_NETWORK else CallText.NO_RESPONSE
            error is InterruptedIOException -> CallText.TIMEOUT  // OkHttp callTimeout (180 s).
            else -> CallText.FAILED
        }
        return CallOutcome.Notice(text)
    }

    private fun causes(error: Throwable): List<Throwable> {
        val chain = mutableListOf<Throwable>()
        var current: Throwable? = error
        while (current != null && chain.size < 16 && chain.none { it === current }) {
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
