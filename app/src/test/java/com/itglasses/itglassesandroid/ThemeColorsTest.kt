package com.itglasses.itglassesandroid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

/** Stage A: dark palette, readable contrast, the theme uses it and the app uses the theme. */
class ThemeColorsTest {
    private val colors: Map<String, String> by lazy {
        ModuleFiles.elements(ModuleFiles.xml("src/main/res/values/colors.xml"), "color")
            .associate { it.getAttribute("name") to it.textContent.trim().uppercase() }
    }

    private val approved = mapOf(
        "itg_background" to "#0E1116", "itg_surface" to "#161B22", "itg_surface_raised" to "#1C232C",
        "itg_outline" to "#30363D", "itg_text_primary" to "#E6EDF3", "itg_text_secondary" to "#8B949E",
        "itg_accent" to "#3DDC97", "itg_on_accent" to "#0E1116", "itg_warning" to "#F2B84B", "itg_danger" to "#FF8A8F",
    )

    private fun luminance(hex: String): Double {
        val channels = (1..5 step 2).map { hex.substring(it, it + 2).toInt(16) / 255.0 }
            .map { if (it <= 0.04045) it / 12.92 else ((it + 0.055) / 1.055).pow(2.4) }
        return 0.2126 * channels[0] + 0.7152 * channels[1] + 0.0722 * channels[2]
    }

    private fun contrast(a: String, b: String): Double {
        val (high, low) = listOf(luminance(colors.getValue(a)), luminance(colors.getValue(b))).sortedDescending()
        return (high + 0.05) / (low + 0.05)
    }

    @Test fun paletteHasTheApprovedValues() {
        assertEquals(approved, colors)
    }

    @Test fun textAndTonesAreReadableOnBackgroundAndCards() {
        for (fg in listOf("itg_text_primary", "itg_text_secondary", "itg_accent", "itg_warning", "itg_danger")) {
            for (bg in listOf("itg_background", "itg_surface")) {
                assertTrue("$fg sobre $bg: ${contrast(fg, bg)}", contrast(fg, bg) >= 4.5)
            }
        }
        assertTrue(contrast("itg_on_accent", "itg_accent") >= 4.5)
        assertTrue(contrast("itg_text_secondary", "itg_surface_raised") >= 4.5)
    }

    @Test fun themeUsesThePalette() {
        val style = ModuleFiles.elements(ModuleFiles.xml("src/main/res/values/themes.xml"), "style")
            .single { it.getAttribute("name") == "Theme.ITGlasses" }
        assertEquals("Theme.MaterialComponents.NoActionBar", style.getAttribute("parent"))
        val items = ModuleFiles.elements(style, "item").associate { it.getAttribute("name") to it.textContent.trim() }
        val expected = mapOf(
            "colorPrimary" to "@color/itg_accent", "colorOnPrimary" to "@color/itg_on_accent",
            "colorSurface" to "@color/itg_surface", "colorOnSurface" to "@color/itg_text_primary",
            "colorError" to "@color/itg_danger", "colorControlActivated" to "@color/itg_accent",
            "android:windowBackground" to "@color/itg_background", "android:colorBackground" to "@color/itg_background",
            "android:textColorPrimary" to "@color/itg_text_primary", "android:textColorSecondary" to "@color/itg_text_secondary",
            "android:statusBarColor" to "@color/itg_background", "android:navigationBarColor" to "@color/itg_background",
            "android:windowLightStatusBar" to "false",
        )
        for ((name, value) in expected) assertEquals(name, value, items[name])
    }

    @Test fun applicationUsesTheTheme() {
        val application = ModuleFiles.elements(ModuleFiles.xml("src/main/AndroidManifest.xml"), "application").single()
        assertEquals("@style/Theme.ITGlasses", application.getAttributeNS(ModuleFiles.ANDROID, "theme"))
    }

    @Test fun layoutHasNoFixedTextColoursAndKeepsThePairingIds() {
        val layout = ModuleFiles.xml("src/main/res/layout/activity_main.xml")
        val all = ModuleFiles.elements(layout, "*")
        for (element in all) {
            val color = element.getAttributeNS(ModuleFiles.ANDROID, "textColor")
            assertFalse("Color fijo $color", color.startsWith("#"))
        }
        val card = all.single { it.getAttributeNS(ModuleFiles.ANDROID, "id") == "@+id/card_pairing" }
        val inside = ModuleFiles.elements(card, "*").map { it.getAttributeNS(ModuleFiles.ANDROID, "id") }.toSet()
        assertTrue(inside.containsAll(setOf("@+id/status_dot", "@+id/tv_pairing_title", "@+id/tv_pairing_code",
            "@+id/tv_pairing", "@+id/tv_pairing_note", "@+id/btn_pair")))
        val dot = all.single { it.getAttributeNS(ModuleFiles.ANDROID, "id") == "@+id/status_dot" }
        assertEquals("no", dot.getAttributeNS(ModuleFiles.ANDROID, "importantForAccessibility"))
    }
}
