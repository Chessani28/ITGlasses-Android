package com.itglasses.itglassesandroid

import com.itglasses.itglassesandroid.PairingAction.CLAIM
import com.itglasses.itglassesandroid.PairingAction.NONE
import com.itglasses.itglassesandroid.PairingAction.POLL
import com.itglasses.itglassesandroid.PairingAction.RECHECK
import com.itglasses.itglassesandroid.PairingAction.REGISTER
import com.itglasses.itglassesandroid.PairingAction.VERIFY
import com.itglasses.itglassesandroid.PairingEvent.CertificateError
import com.itglasses.itglassesandroid.PairingEvent.Claimed
import com.itglasses.itglassesandroid.PairingEvent.ClockError
import com.itglasses.itglassesandroid.PairingEvent.ForbiddenCall
import com.itglasses.itglassesandroid.PairingEvent.Http
import com.itglasses.itglassesandroid.PairingEvent.NetworkError
import com.itglasses.itglassesandroid.PairingEvent.Registered
import com.itglasses.itglassesandroid.PairingEvent.Status
import com.itglasses.itglassesandroid.PairingEvent.UserRetry
import com.itglasses.itglassesandroid.PairingEvent.Verified
import com.itglasses.itglassesandroid.PairingState.Blocked
import com.itglasses.itglassesandroid.PairingState.CertificateRejected
import com.itglasses.itglassesandroid.PairingState.ClockMismatch
import com.itglasses.itglassesandroid.PairingState.Connected
import com.itglasses.itglassesandroid.PairingState.Denied
import com.itglasses.itglassesandroid.PairingState.Failed
import com.itglasses.itglassesandroid.PairingState.Offline
import com.itglasses.itglassesandroid.PairingState.Pending
import com.itglasses.itglassesandroid.PairingState.RateLimited
import com.itglasses.itglassesandroid.PairingState.Reason
import com.itglasses.itglassesandroid.PairingState.Registering
import com.itglasses.itglassesandroid.PairingState.Unconfigured

/*
 * Pairing states and transitions. Pure Kotlin (no android.*, no org.json) so they run as JVM unit tests.
 *
 * The driver (MainActivity, tanda 2) runs PairingStep.action after PairingStep.delaySeconds, ONLY while the
 * app is in the foreground, and feeds the result back as a PairingEvent. The machine never deletes the
 * installation key, never clears the "managed" flag and never touches the stored shared token: the most it
 * asks for is forgetRequest (drop the request id and code, then register again with the same key).
 */

/** What the driver does next. */
internal enum class PairingAction {
    /** POST /pairing/requests, signed, with name and model unless Registering.withoutLabels. */
    REGISTER,
    /** POST /pairing/status while the request is pending. */
    POLL,
    /** POST /pairing/claim once approved. */
    CLAIM,
    /** Get a token (challenge + token) and confirm it with GET /devices/me. */
    VERIFY,
    /** POST /pairing/status (or REGISTER when there is no request id): blocked, or after a 403 on a normal call. */
    RECHECK,
    NONE,
}

internal sealed class PairingState {
    /** The APK was built without ITGLASSES_PAIRING_ORIGIN: shared-token mode, as before. */
    data object Unconfigured : PairingState()
    data class Registering(val reason: Reason = Reason.FIRST, val withoutLabels: Boolean = false) : PairingState()
    /** code: six digits for visual confirmation only. expiresAt: server epoch seconds. */
    data class Pending(val code: String, val expiresAt: Long) : PairingState()
    data object Connected : PairingState()
    /** Identity and request id are kept; rechecked every BLOCKED_SECONDS and by the button. */
    data object Blocked : PairingState()
    /** The request was denied; registers again only when the user asks. */
    data object Denied : PairingState()
    /** previous: what to show again once the server answers (e.g. the pending code). */
    data class Offline(val retryIn: Int, val attempt: Int, val retry: PairingAction, val previous: PairingState) : PairingState()
    data class RateLimited(val retryIn: Int, val retry: PairingAction, val previous: PairingState) : PairingState()
    data class CertificateRejected(val retry: PairingAction) : PairingState()
    data class ClockMismatch(val retry: PairingAction) : PairingState()
    data class Failed(val retry: PairingAction) : PairingState()

    enum class Reason { FIRST, EXPIRED, REMOVED, REQUESTED }
}

internal sealed class PairingEvent {
    data class Registered(val code: String, val expiresAt: Long, val pollInterval: Int) : PairingEvent()
    /** The "state" field returned by /pairing/status, or "active" from /pairing/requests. */
    data class Status(val state: String) : PairingEvent()
    data object Claimed : PairingEvent()
    data object Verified : PairingEvent()
    /** HTTP error returned by a pairing call (during = the action that failed). */
    data class Http(val code: Int, val during: PairingAction, val retryAfter: Int? = null) : PairingEvent()
    data class NetworkError(val during: PairingAction) : PairingEvent()
    data class CertificateError(val during: PairingAction) : PairingEvent()
    /** The clock could not be corrected: implausible Date header, or still rejected after correcting it. */
    data class ClockError(val during: PairingAction) : PairingEvent()
    /** 403 on a normal call (chat, voice): may be "blocked" or a route the phone may not use. */
    data object ForbiddenCall : PairingEvent()
    /** "Reintentar", "Comprobar de nuevo" or "Solicitar de nuevo". */
    data object UserRetry : PairingEvent()
}

/** forgetRequest: drop the request id and code only (never the key, the managed flag or the shared token). */
internal data class PairingStep(
    val state: PairingState,
    val action: PairingAction,
    val delaySeconds: Int = 0,
    val forgetRequest: Boolean = false,
)

internal object PairingMachine {
    const val POLL_SECONDS = 5
    const val BLOCKED_SECONDS = 30
    const val RETRY_AFTER_DEFAULT = 30
    const val RETRY_AFTER_MAX = 300
    private val BACKOFF = intArrayOf(5, 10, 20, 40, 60)

    /** pending: the code and expiry saved with an open request, if any. */
    fun start(configured: Boolean, hasRequest: Boolean, managed: Boolean, pending: Pending?): PairingStep = when {
        !configured -> PairingStep(Unconfigured, NONE)
        hasRequest && pending != null -> PairingStep(pending, POLL)
        hasRequest && managed -> PairingStep(Connected, VERIFY)
        hasRequest -> PairingStep(Registering(), POLL)
        else -> PairingStep(Registering(), REGISTER)
    }

    fun next(state: PairingState, event: PairingEvent): PairingStep = when (event) {
        is Registered -> PairingStep(Pending(event.code, event.expiresAt), POLL, event.pollInterval.coerceIn(1, 60))
        is Status -> status(settled(state), event.state)
        Claimed -> PairingStep(settled(state), VERIFY)
        Verified -> PairingStep(Connected, NONE)
        is Http -> http(state, event)
        is NetworkError -> offline(state, event.during)
        is CertificateError -> PairingStep(CertificateRejected(event.during), NONE)
        is ClockError -> PairingStep(ClockMismatch(event.during), NONE)
        ForbiddenCall -> PairingStep(state, if (state == Connected) RECHECK else NONE)
        UserRetry -> retry(state)
    }

    /** Offline and RateLimited are interruptions: resume from what was shown before them. */
    private fun settled(state: PairingState): PairingState = when (state) {
        is Offline -> state.previous
        is RateLimited -> state.previous
        else -> state
    }

    private fun status(state: PairingState, value: String): PairingStep = when (value) {
        "pending" -> PairingStep(state, POLL, POLL_SECONDS)
        "approved" -> PairingStep(state, CLAIM)
        "active" -> if (state == Connected) PairingStep(Connected, NONE) else PairingStep(state, VERIFY)
        "expired" -> PairingStep(Registering(Reason.EXPIRED), REGISTER, forgetRequest = true)
        "denied" -> PairingStep(Denied, NONE, forgetRequest = true)
        "blocked" -> PairingStep(Blocked, RECHECK, BLOCKED_SECONDS)
        // Anything else (e.g. "revoked", which the backend never sends) is an error, never an expiry.
        else -> PairingStep(Failed(POLL), NONE)
    }

    private fun http(state: PairingState, event: Http): PairingStep = when (event.code) {
        410 -> PairingStep(Registering(Reason.REMOVED), REGISTER, forgetRequest = true)
        403 -> PairingStep(Blocked, RECHECK, BLOCKED_SECONDS)
        429 -> {
            val wait = (event.retryAfter ?: RETRY_AFTER_DEFAULT).coerceIn(1, RETRY_AFTER_MAX)
            PairingStep(RateLimited(wait, event.during, settled(state)), event.during, wait)
        }
        409 -> if (event.during == CLAIM) PairingStep(settled(state), POLL, POLL_SECONDS)
               else PairingStep(Failed(event.during), NONE)
        422 -> if (event.during == REGISTER && state is Registering && !state.withoutLabels)
                   PairingStep(state.copy(withoutLabels = true), REGISTER)
               else PairingStep(Failed(event.during), NONE)
        in 500..599 -> offline(state, event.during)
        else -> PairingStep(Failed(event.during), NONE)
    }

    private fun offline(state: PairingState, during: PairingAction): PairingStep {
        val attempt = ((state as? Offline)?.attempt ?: 0) + 1
        val wait = BACKOFF[minOf(attempt, BACKOFF.size) - 1]
        return PairingStep(Offline(wait, attempt, during, settled(state)), during, wait)
    }

    private fun retry(state: PairingState): PairingStep = when (state) {
        Blocked -> PairingStep(Blocked, RECHECK)
        Denied -> PairingStep(Registering(Reason.REQUESTED), REGISTER)
        is Offline -> PairingStep(state, state.retry)
        is CertificateRejected -> again(state, state.retry)
        is ClockMismatch -> again(state, state.retry)
        is Failed -> again(state, state.retry)
        else -> PairingStep(state, NONE)  // RateLimited waits for Retry-After; the rest has nothing to retry.
    }

    private fun again(state: PairingState, action: PairingAction): PairingStep =
        if (action == REGISTER) PairingStep(Registering(), REGISTER) else PairingStep(state, action)
}
