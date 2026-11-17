package com.itglasses.itglassesandroid

/** CallText key -> string in res/values/strings.xml (checked by CallTextsTest). */
internal object CallTexts {
    fun of(text: CallText): Int = when (text) {
        CallText.NO_NETWORK -> R.string.call_no_network
        CallText.NO_RESPONSE -> R.string.call_no_response
        CallText.TIMEOUT -> R.string.call_timeout
        CallText.CERTIFICATE -> R.string.call_certificate
        CallText.CLOCK -> R.string.call_clock
        CallText.UNAUTHORIZED -> R.string.call_unauthorized
        CallText.UNAUTHORIZED_REPEATED -> R.string.call_unauthorized_repeated
        CallText.REMOVED -> R.string.call_removed
        CallText.AUDIO_TOO_LONG -> R.string.call_audio_too_long
        CallText.REJECTED -> R.string.call_rejected
        CallText.RATE_LIMITED -> R.string.call_rate_limited
        CallText.SERVER_ERROR -> R.string.call_server_error
        CallText.UNEXPECTED_CODE -> R.string.call_unexpected_code
        CallText.FAILED -> R.string.call_failed
        CallText.GENERATING -> R.string.call_generating
    }
}
