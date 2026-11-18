package com.itglasses.itglassesandroid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BubbleWidthTest {
    @Test
    fun `limit is 80 percent of the container width`() {
        assertEquals(800, BubbleWidth.max(available = 1000, fallback = 2000))
        assertEquals(1600, BubbleWidth.max(available = 2000, fallback = 1000)) // landscape: the real width wins
    }

    @Test
    fun `screen width is used only before the first layout`() {
        assertEquals(864, BubbleWidth.max(available = 0, fallback = 1080))
        assertEquals(0, BubbleWidth.max(available = -5, fallback = -5))
    }

    @Test
    fun `bubbles wrap their content and follow the container width`() {
        val source = ModuleFiles.file("src/main/java/com/itglasses/itglassesandroid/ChatBubbles.kt").readText()
        assertTrue(source.contains("BubbleWidth.max("))
        assertTrue(source.contains("addOnLayoutChangeListener"))
        assertTrue(source.contains("LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)"))
        assertTrue(source.contains("gravity = align"))
    }
}
