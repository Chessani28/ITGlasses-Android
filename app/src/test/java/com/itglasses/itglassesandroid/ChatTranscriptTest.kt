package com.itglasses.itglassesandroid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatTranscriptTest {
    @Test fun firstEntryHasNoLeadingSeparator() {
        assertEquals("Tú: hola\nITGlasses: hola", ChatTranscript.append("", "Tú: hola\nITGlasses: hola"))
    }

    @Test fun entriesAreSeparatedByBlankLine() {
        assertEquals("A\n\nB", ChatTranscript.append("A", "B"))
    }

    @Test fun oldestEntriesAreDroppedWhenTooLong() {
        var text = ""
        for (i in 1..50) text = ChatTranscript.append(text, "mensaje $i " + "x".repeat(40), maxChars = 300)
        assertTrue(text.length <= 300)
        assertTrue(text.endsWith("mensaje 50 " + "x".repeat(40)))
        assertTrue(!text.contains("mensaje 1 "))
    }

    @Test fun newestEntryIsKeptEvenIfLongerThanLimit() {
        val long = "y".repeat(500)
        assertEquals(long, ChatTranscript.append("A\n\nB", long, maxChars = 100))
    }
}
