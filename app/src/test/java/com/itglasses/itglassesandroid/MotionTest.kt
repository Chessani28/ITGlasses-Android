package com.itglasses.itglassesandroid

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MotionTest {
    private fun fail(): Nothing = throw AssertionError("This source must not be read on this API level")

    @Test fun api26AndLaterAskTheSystemAndIgnoreTheScale() {
        for (sdk in listOf(26, 30, 34, 36)) {
            assertTrue(Motion.enabledFrom(sdk, { true }, { fail() }))
            assertFalse(Motion.enabledFrom(sdk, { false }, { fail() }))
        }
    }

    @Test fun api24And25UseTheDurationScale() {
        for (sdk in listOf(24, 25)) {
            assertFalse(Motion.enabledFrom(sdk, { fail() }, { 0f }))
            assertTrue(Motion.enabledFrom(sdk, { fail() }, { 0.5f }))
            assertTrue(Motion.enabledFrom(sdk, { fail() }, { 1f }))
        }
    }

    @Test fun aNegativeScaleCountsAsOff() {
        assertFalse(Motion.enabledFrom(24, { fail() }, { -1f }))
    }
}
