package com.itglasses.itglassesandroid

/** PairingTone -> colours in res/values/colors.xml (checked by PairingTonesTest). */
internal object PairingTones {
    /** Status dot. */
    fun dot(tone: PairingTone): Int = when (tone) {
        PairingTone.ACCENT -> R.color.itg_accent
        PairingTone.WARNING -> R.color.itg_warning
        PairingTone.DANGER -> R.color.itg_danger
        PairingTone.NEUTRAL -> R.color.itg_text_secondary
    }

    /** Card border: the tone's colour, or the quiet outline when neutral. */
    fun stroke(tone: PairingTone): Int = if (tone == PairingTone.NEUTRAL) R.color.itg_outline else dot(tone)
}
