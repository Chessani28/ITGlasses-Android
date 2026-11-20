package com.itglasses.itglassesandroid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceLevelTest {
    private fun constant(value: Int, n: Int = 160) = ShortArray(n) { value.toShort() }

    @Test fun silenceAndEmptyBlocksAreZero() {
        assertEquals(0f, VoiceLevel.of(ShortArray(160)), 0f)
        assertEquals(0f, VoiceLevel.of(ShortArray(0)), 0f)
        assertEquals(0f, VoiceLevel.of(constant(1000), 0), 0f)
        assertEquals(0f, VoiceLevel.of(constant(1000), -3), 0f)
    }

    @Test fun fullScaleIsOne() {
        assertEquals(1f, VoiceLevel.of(constant(Short.MIN_VALUE.toInt())), 0f)
        assertEquals(1f, VoiceLevel.of(ShortArray(160) { if (it % 2 == 0) Short.MAX_VALUE else Short.MIN_VALUE }), 0.001f)
    }

    @Test fun decibelsMapLinearlyAboveTheFloor() {
        assertEquals(0.4f, VoiceLevel.of(constant(1036)), 0.005f)    // ≈ −30 dBFS
        assertEquals(0.8f, VoiceLevel.of(constant(10362)), 0.005f)   // ≈ −10 dBFS
        assertEquals(0f, VoiceLevel.of(constant(100)), 0f)           // ≈ −50,3 dBFS: below the floor
    }

    @Test fun onlyTheSamplesReadCount() {
        val block = ShortArray(320) { if (it < 160) 10362 else 0 }
        assertEquals(VoiceLevel.of(constant(10362)), VoiceLevel.of(block, 160), 0f)
        assertEquals(VoiceLevel.of(block), VoiceLevel.of(block, 9999), 0f)  // Clamped to the block size.
    }

    @Test fun smoothingRisesFastAndFallsSlowly() {
        assertEquals(0.5f, VoiceLevel.smooth(0f, 1f, animated = true), 0.0001f)
        assertEquals(0.85f, VoiceLevel.smooth(1f, 0f, animated = true), 0.0001f)
        assertEquals(0.6f, VoiceLevel.smooth(0.6f, 0.6f, animated = true), 0f)
    }

    @Test fun withAnimationsOffTheLevelIsShownDirectly() {
        assertEquals(1f, VoiceLevel.smooth(0f, 1f, animated = false), 0f)
        assertEquals(0f, VoiceLevel.smooth(1f, 0f, animated = false), 0f)
    }

    @Test fun meterKeepsTheLastBlockAndResets() {
        val meter = VoiceMeter()
        meter.onSamples(constant(10362), 160)
        assertTrue(meter.level > 0.79f)
        meter.onSamples(ShortArray(160), 160)
        assertEquals(0f, meter.level, 0f)
        meter.onSamples(constant(10362), 160)
        meter.reset()
        assertEquals(0f, meter.level, 0f)
    }

    @Test fun historyKeepsTheLastLevelsOldestFirst() {
        val history = LevelHistory(5)
        assertEquals(List(5) { 0f }, history.snapshot())
        history.push(0.2f); history.push(1.5f)
        assertEquals(listOf(0f, 0f, 0f, 0.2f, 1f), history.snapshot())  // Clamped to 0..1.
        for (i in 1..6) history.push(i / 10f)
        assertEquals(listOf(0.2f, 0.3f, 0.4f, 0.5f, 0.6f), history.snapshot())
        history.clear()
        assertEquals(List(5) { 0f }, history.snapshot())
    }
}
