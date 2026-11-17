package com.itglasses.itglassesandroid

/** PairingText key -> string in res/values/strings.xml (checked by PairingTextsTest). */
internal object PairingTexts {
    fun of(text: PairingText): Int = when (text) {
        PairingText.REGISTERING_TITLE -> R.string.pairing_registering_title
        PairingText.REGISTERING_DETAIL -> R.string.pairing_registering_detail
        PairingText.EXPIRED_TITLE -> R.string.pairing_expired_title
        PairingText.REMOVED_TITLE -> R.string.pairing_removed_title
        PairingText.PENDING_TITLE -> R.string.pairing_pending_title
        PairingText.PENDING_DETAIL -> R.string.pairing_pending_detail
        PairingText.PENDING_EXPIRES -> R.string.pairing_pending_expires
        PairingText.CODE_SPOKEN -> R.string.pairing_code_spoken
        PairingText.CONNECTED_TITLE -> R.string.pairing_connected_title
        PairingText.CONNECTED_DETAIL -> R.string.pairing_connected_detail
        PairingText.CONNECTED_DETAIL_UNKNOWN -> R.string.pairing_connected_detail_unknown
        PairingText.BLOCKED_TITLE -> R.string.pairing_blocked_title
        PairingText.BLOCKED_DETAIL -> R.string.pairing_blocked_detail
        PairingText.DENIED_TITLE -> R.string.pairing_denied_title
        PairingText.DENIED_DETAIL -> R.string.pairing_denied_detail
        PairingText.OFFLINE_TITLE -> R.string.pairing_offline_title
        PairingText.OFFLINE_DETAIL -> R.string.pairing_offline_detail
        PairingText.RATE_LIMITED_TITLE -> R.string.pairing_rate_limited_title
        PairingText.RATE_LIMITED_DETAIL -> R.string.pairing_rate_limited_detail
        PairingText.CERTIFICATE_TITLE -> R.string.pairing_certificate_title
        PairingText.CERTIFICATE_DETAIL -> R.string.pairing_certificate_detail
        PairingText.CLOCK_TITLE -> R.string.pairing_clock_title
        PairingText.CLOCK_DETAIL -> R.string.pairing_clock_detail
        PairingText.FAILED_TITLE -> R.string.pairing_failed_title
        PairingText.FAILED_DETAIL -> R.string.pairing_failed_detail
        PairingText.UNCONFIGURED_DETAIL -> R.string.pairing_unconfigured_detail
        PairingText.CALL_CHECKING -> R.string.pairing_call_checking
        PairingText.FORBIDDEN_CALL -> R.string.pairing_forbidden_call
        PairingText.BUTTON_CHECK_AGAIN -> R.string.pairing_button_check_again
        PairingText.BUTTON_REQUEST_AGAIN -> R.string.pairing_button_request_again
        PairingText.BUTTON_RETRY -> R.string.pairing_button_retry
    }
}
