package com.itglasses.itglassesandroid

import com.itglasses.itglassesandroid.ClockOffset.Observation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Locale
import java.util.TimeZone

class ClockOffsetTest {
    private val rfcExample = "Sun, 06 Nov 1994 08:49:37 GMT"
    private val rfcEpoch = 784_111_777L
    private fun localMillis(secondsBehindServer: Long) = (rfcEpoch - secondsBehindServer) * 1000 + 500

    @Test fun parsesHttpDates() {
        assertEquals(rfcEpoch, ClockOffset.parseHttpDate(rfcExample))
        assertEquals(1_794_932_400L, ClockOffset.parseHttpDate("Tue, 17 Nov 2026 16:20:00 GMT"))
    }

    @Test fun parsingIgnoresThePhoneLanguageAndTimeZone() {
        // "Tue" and "Nov" are English in HTTP; a phone in es-MX would say "mar." and "nov.".
        val locale = Locale.getDefault()
        val zone = TimeZone.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("es-MX"))
            TimeZone.setDefault(TimeZone.getTimeZone("America/Mexico_City"))
            assertEquals(1_794_909_600L, ClockOffset.parseHttpDate("Tue, 17 Nov 2026 10:00:00 GMT"))
        } finally {
            Locale.setDefault(locale)
            TimeZone.setDefault(zone)
        }
    }

    @Test fun rejectsMissingOrMalformedDates() {
        for (value in listOf(null, "", "   ", "yesterday", "$rfcExample extra", "06 Nov 1994", "Sun, 06 Nov 1994")) {
            assertNull(value, ClockOffset.parseHttpDate(value))
        }
    }

    @Test fun phoneBehindTheServerIsCorrected() {
        val clock = ClockOffset()
        assertEquals(Observation.ADJUSTED, clock.observe(rfcExample, localMillis(120)))
        assertEquals(120L, clock.seconds)
        assertEquals(rfcEpoch, clock.serverNow(localMillis(120)))
    }

    @Test fun phoneAheadOfTheServerIsCorrected() {
        val clock = ClockOffset()
        assertEquals(Observation.ADJUSTED, clock.observe(rfcExample, localMillis(-200)))
        assertEquals(-200L, clock.seconds)
    }

    @Test fun smallDifferencesAreIgnored() {
        val clock = ClockOffset()
        assertEquals(Observation.UNCHANGED, clock.observe(rfcExample, localMillis(3)))
        assertEquals(0L, clock.seconds)
    }

    @Test fun implausibleSkewIsRejectedAndKeepsTheOffset() {
        val clock = ClockOffset()
        clock.observe(rfcExample, localMillis(120))
        assertEquals(Observation.IMPLAUSIBLE, clock.observe(rfcExample, localMillis(3 * 86_400)))
        assertEquals(120L, clock.seconds)
    }

    @Test fun missingHeaderKeepsTheOffset() {
        val clock = ClockOffset()
        clock.observe(rfcExample, localMillis(120))
        assertEquals(Observation.MISSING, clock.observe(null, localMillis(0)))
        assertEquals(120L, clock.seconds)
    }

    @Test fun returningToTheRightTimeResetsTheOffset() {
        val clock = ClockOffset()
        clock.observe(rfcExample, localMillis(120))
        assertEquals(Observation.ADJUSTED, clock.observe(rfcExample, localMillis(2)))
        assertEquals(0L, clock.seconds)
    }
}
