package com.itglasses.itglassesandroid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/** Stage B2: the voice screen layout, its approved texts and accessibility. */
class VoiceScreenTest {
    private val layout = "src/main/res/layout/voice_screen.xml"
    private val all: List<Element> by lazy { listOf(ModuleFiles.xml(layout)) + ModuleFiles.elements(ModuleFiles.xml(layout), "*") }
    private fun attr(e: Element, name: String) = e.getAttributeNS(ModuleFiles.ANDROID, name)
    private fun byId(id: String) = all.single { attr(it, "id") == "@+id/$id" }

    @Test fun textsAreTheApprovedOnesAndUsed() {
        val strings = ModuleFiles.elements(ModuleFiles.xml("src/main/res/values/strings.xml"), "string")
            .associate { it.getAttribute("name") to it.textContent }
        val approved = mapOf(
            "voice_screen_listening" to "Escuchando…",
            "voice_screen_seconds" to "%1\$d s",
            "voice_screen_stop" to "Detener y enviar",
            "voice_screen_cancel" to "Cancelar",
            "voice_screen_hint" to "Toca Detener y enviar cuando termines.",
        )
        val used = ModuleFiles.file(layout).readText() +
            ModuleFiles.file("src/main/java/com/itglasses/itglassesandroid/VoiceScreen.kt").readText()
        for ((name, text) in approved) {
            assertEquals(name, text, strings[name])
            assertTrue("$name no se usa", used.contains("@string/$name") || used.contains("R.string.$name"))
        }
    }

    @Test fun orderIsVisualizerStateSecondsHintStopCancel() {
        val ids = all.map { attr(it, "id") }.filter { it.isNotEmpty() }
        assertEquals(listOf("@+id/voice_screen", "@+id/voice_visualizer", "@+id/tv_voice_state", "@+id/tv_voice_seconds",
            "@+id/tv_voice_hint", "@+id/btn_voice_stop", "@+id/btn_voice_cancel"), ids)
    }

    @Test fun accessibilityAndTouchBlocking() {
        assertEquals("no", attr(byId("voice_visualizer"), "importantForAccessibility"))
        assertEquals("polite", attr(byId("tv_voice_state"), "accessibilityLiveRegion"))
        assertEquals("", attr(byId("tv_voice_seconds"), "accessibilityLiveRegion"))  // Not read every second.
        assertEquals("56dp", attr(byId("btn_voice_stop"), "minHeight"))
        val root = byId("voice_screen")
        assertEquals("true", attr(root, "clickable"))
        val code = ModuleFiles.file("src/main/java/com/itglasses/itglassesandroid/VoiceScreen.kt").readText()
        assertTrue(code.contains("setAccessibilityPaneTitle"))
        assertTrue(code.contains("ACTION_ACCESSIBILITY_FOCUS"))
        assertTrue(code.contains("IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS"))
        assertFalse("announceForAccessibility está obsoleto", code.contains("announceForAccessibility"))
    }

    @Test fun visualizerShrinksAndColoursComeFromThePalette() {
        val visualizer = byId("voice_visualizer")
        assertEquals("0dp", attr(visualizer, "layout_height"))
        assertEquals("1", attr(visualizer, "layout_weight"))
        for (e in all) for (name in listOf("textColor", "background")) assertFalse("$name fijo", attr(e, name).startsWith("#"))
        assertTrue(ModuleFiles.file("src/main/res/drawable/ic_mic.xml").readText().contains("@color/itg_on_accent"))
    }
}
