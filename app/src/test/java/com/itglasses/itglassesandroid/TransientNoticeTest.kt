package com.itglasses.itglassesandroid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TransientNoticeTest {
    private var now = 1_000_000L
    private val notice = TransientNotice(8_000) { now }

    @Test fun noticeLastsEightSeconds() {
        notice.show("Sin conexión")
        now += 7_999
        assertEquals("Sin conexión", notice.current())
        now += 1
        assertNull(notice.current())
    }

    @Test fun newerNoticeRestartsTheTimeAndAnOlderTimerDoesNotClearIt() {
        val first = notice.show("Sin conexión")
        now += 5_000
        val second = notice.show("El asistente tardó demasiado")
        now += 5_000  // 10 s after the first, 5 s after the second.
        assertFalse(notice.expire(first))
        assertEquals("El asistente tardó demasiado", notice.current())
        now += 3_000
        assertTrue(notice.expire(second))
        assertNull(notice.current())
    }

    @Test fun nothingIsShownBeforeTheFirstNotice() {
        assertNull(notice.current())
        assertEquals(8_000L, notice.durationMs)
    }
}
