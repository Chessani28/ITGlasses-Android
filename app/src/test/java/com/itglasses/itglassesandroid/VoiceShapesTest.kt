package com.itglasses.itglassesandroid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceShapesTest {
    private val d = 0.0001f

    @Test fun ringsGrowWithTheLevelAndStayAtRestWithoutAnimations() {
        assertEquals(52f, VoiceShapes.ringRadius(0, 0f, animated = true), d)
        assertEquals(88f, VoiceShapes.ringRadius(2, 1f, animated = true), d)
        assertEquals(VoiceShapes.ringRadius(2, 0f, animated = true), VoiceShapes.ringRadius(2, 1f, animated = false), d)
        assertEquals(0.45f, VoiceShapes.ringAlpha(0, 1f, animated = true), d)
        assertEquals(0.063f, VoiceShapes.ringAlpha(2, 0f, animated = true), d)
        assertEquals(0.135f, VoiceShapes.ringAlpha(0, 1f, animated = false), d)  // Fixed opacity when still.
        assertEquals(88f, VoiceShapes.ringRadius(2, 5f, animated = true), d)     // Level clamped.
    }

    @Test fun barsGoFromSixToFortyTwoDpAndAreCentred() {
        assertEquals(6f, VoiceShapes.barHeight(0f), d)
        assertEquals(42f, VoiceShapes.barHeight(1f), d)
        assertEquals(6f, VoiceShapes.barHeight(-1f), d)
        assertEquals(103f, VoiceShapes.barLeft(0), d)
        assertEquals(VoiceShapes.WIDTH - 103f, VoiceShapes.barLeft(4) + VoiceShapes.BAR_WIDTH, d)
    }

    @Test fun drawingOnlyShrinksToFit() {
        assertEquals(1f, VoiceShapes.scale(260f, 300f), d)
        assertEquals(1f, VoiceShapes.scale(520f, 900f), d)
        assertEquals(0.5f, VoiceShapes.scale(400f, 150f), d)  // Short screen: the buttons keep their room.
        assertEquals(0.5f, VoiceShapes.scale(130f, 600f), d)
        assertEquals(0f, VoiceShapes.scale(0f, 300f), d)
    }

    @Test fun everythingFitsInTheDrawingAtFullLevel() {
        val outer = VoiceShapes.ringRadius(VoiceShapes.RINGS - 1, 1f, animated = true) + VoiceShapes.RING_STROKE
        assertTrue(VoiceShapes.CENTER_Y - outer >= 0f)
        assertTrue(VoiceShapes.WIDTH / 2 - outer >= 0f)
        val barTop = VoiceShapes.BARS_CENTER_Y - VoiceShapes.barHeight(1f) / 2
        assertTrue("rings and bars do not overlap", VoiceShapes.CENTER_Y + outer < barTop)
        assertTrue(VoiceShapes.BARS_CENTER_Y + VoiceShapes.barHeight(1f) / 2 <= VoiceShapes.HEIGHT)
    }
}
