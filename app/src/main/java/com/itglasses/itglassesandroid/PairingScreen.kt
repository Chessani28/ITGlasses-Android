package com.itglasses.itglassesandroid

import com.itglasses.itglassesandroid.PairingState.Reason

/**
 * Text keys for the pairing card. Tanda 2b maps each one to a string in strings.xml (the exact approved
 * Spanish texts); keeping keys here lets the mapping from state to screen run as a JVM test.
 */
internal enum class PairingText {
    REGISTERING_TITLE, REGISTERING_DETAIL, EXPIRED_TITLE, REMOVED_TITLE,
    PENDING_TITLE, PENDING_DETAIL, PENDING_EXPIRES, CODE_SPOKEN,
    CONNECTED_TITLE, CONNECTED_DETAIL, CONNECTED_DETAIL_UNKNOWN,
    BLOCKED_TITLE, BLOCKED_DETAIL, DENIED_TITLE, DENIED_DETAIL,
    OFFLINE_TITLE, OFFLINE_DETAIL, RATE_LIMITED_TITLE, RATE_LIMITED_DETAIL,
    CERTIFICATE_TITLE, CERTIFICATE_DETAIL, CLOCK_TITLE, CLOCK_DETAIL,
    FAILED_TITLE, FAILED_DETAIL, UNCONFIGURED_DETAIL, CALL_CHECKING, FORBIDDEN_CALL,
    BUTTON_CHECK_AGAIN, BUTTON_REQUEST_AGAIN, BUTTON_RETRY,
}

/** What the card shows. Args fill the %1$s of their text. */
internal data class PairingView(
    val title: PairingText? = null,
    val detail: PairingText? = null,
    val detailArg: String? = null,
    val code: String? = null,
    /** Digits separated by spaces, for CODE_SPOKEN (screen reader). */
    val spokenCode: String? = null,
    val note: PairingText? = null,
    val noteArg: String? = null,
    val button: PairingText? = null,
    /** Show the URL and token fields (APK without pairing origin only). */
    val sharedTokenMode: Boolean = false,
    /** Enable Send and Voice. */
    val canChat: Boolean = false,
)

internal object PairingScreen {
    /** serverNow: ClockOffset.serverNow(), for the countdown. model: DeviceLabel.model(...) or null. */
    fun view(state: PairingState, serverNow: Long, model: String?): PairingView = when (state) {
        PairingState.Unconfigured ->
            PairingView(detail = PairingText.UNCONFIGURED_DETAIL, sharedTokenMode = true, canChat = true)
        is PairingState.Registering -> when (state.reason) {
            Reason.EXPIRED -> PairingView(title = PairingText.EXPIRED_TITLE)
            Reason.REMOVED -> PairingView(title = PairingText.REMOVED_TITLE)
            Reason.FIRST, Reason.REQUESTED ->
                PairingView(title = PairingText.REGISTERING_TITLE, detail = PairingText.REGISTERING_DETAIL)
        }
        is PairingState.Pending -> PairingView(
            title = PairingText.PENDING_TITLE,
            detail = PairingText.PENDING_DETAIL,
            code = DeviceLabel.formatCode(state.code),
            spokenCode = DeviceLabel.spokenCode(state.code),
            note = PairingText.PENDING_EXPIRES,
            noteArg = DeviceLabel.countdown(state.expiresAt - serverNow),
        )
        PairingState.Connected -> PairingView(
            title = PairingText.CONNECTED_TITLE,
            detail = if (model == null) PairingText.CONNECTED_DETAIL_UNKNOWN else PairingText.CONNECTED_DETAIL,
            detailArg = model,
            canChat = true,
        )
        PairingState.Blocked -> PairingView(PairingText.BLOCKED_TITLE, PairingText.BLOCKED_DETAIL,
            button = PairingText.BUTTON_CHECK_AGAIN)
        PairingState.Denied -> PairingView(PairingText.DENIED_TITLE, PairingText.DENIED_DETAIL,
            button = PairingText.BUTTON_REQUEST_AGAIN)
        is PairingState.Offline -> PairingView(PairingText.OFFLINE_TITLE, PairingText.OFFLINE_DETAIL,
            detailArg = state.retryIn.toString(), button = PairingText.BUTTON_RETRY)
        is PairingState.RateLimited -> PairingView(PairingText.RATE_LIMITED_TITLE, PairingText.RATE_LIMITED_DETAIL,
            detailArg = state.retryIn.toString())
        is PairingState.CertificateRejected -> PairingView(PairingText.CERTIFICATE_TITLE,
            PairingText.CERTIFICATE_DETAIL, button = PairingText.BUTTON_RETRY)
        is PairingState.ClockMismatch -> PairingView(PairingText.CLOCK_TITLE, PairingText.CLOCK_DETAIL,
            button = PairingText.BUTTON_RETRY)
        is PairingState.Failed -> PairingView(PairingText.FAILED_TITLE, PairingText.FAILED_DETAIL,
            button = PairingText.BUTTON_RETRY)
    }
}
