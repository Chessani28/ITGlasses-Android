package com.itglasses.itglassesandroid

import org.junit.Assert.assertEquals
import org.junit.Test

class PairingTonesTest {
    private fun name(id: Int): String = R.color::class.java.fields.single { it.getInt(null) == id }.name

    @Test fun dotColourOfEachTone() {
        assertEquals(mapOf(PairingTone.ACCENT to "itg_accent", PairingTone.WARNING to "itg_warning",
            PairingTone.DANGER to "itg_danger", PairingTone.NEUTRAL to "itg_text_secondary"),
            PairingTone.entries.associateWith { name(PairingTones.dot(it)) })
    }

    @Test fun borderUsesTheToneOrTheQuietOutlineWhenNeutral() {
        for (tone in PairingTone.entries) {
            val expected = if (tone == PairingTone.NEUTRAL) "itg_outline" else name(PairingTones.dot(tone))
            assertEquals(tone.name, expected, name(PairingTones.stroke(tone)))
        }
    }
}
