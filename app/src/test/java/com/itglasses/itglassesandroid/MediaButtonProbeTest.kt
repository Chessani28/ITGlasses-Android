package com.itglasses.itglassesandroid

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Prueba de la patilla (fase A): only numbers and known names are logged; off by default and only in debug. */
class MediaButtonProbeTest {
    private val activity by lazy { ModuleFiles.file("src/main/java/com/itglasses/MainActivity.kt").readText() }

    @Test fun logLineHasOnlyNumbersAndKnownNames() {
        assertEquals("probe media_button key=85 name=PLAY_PAUSE action=down repeat=0",
            MediaButtons.describe(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.ACTION_DOWN, 0))
        assertEquals("probe media_button key=79 name=HEADSETHOOK action=up repeat=2",
            MediaButtons.describe(KeyEvent.KEYCODE_HEADSETHOOK, KeyEvent.ACTION_UP, 2))
        assertEquals("OTHER", MediaButtons.name(KeyEvent.KEYCODE_A))
        assertEquals("probe media_button key=29 name=OTHER action=other repeat=0", MediaButtons.describe(29, 7, -1))
    }

    @Test fun switchIsInTheLayoutWithItsText() {
        val switch = ModuleFiles.elements(ModuleFiles.xml("src/main/res/layout/activity_main.xml"), "*")
            .single { it.getAttributeNS(ModuleFiles.ANDROID, "id") == "@+id/sw_media_probe" }
        assertEquals("@string/media_probe_switch", switch.getAttributeNS(ModuleFiles.ANDROID, "text"))
        val strings = ModuleFiles.elements(ModuleFiles.xml("src/main/res/values/strings.xml"), "string")
            .associate { it.getAttribute("name") to it.textContent }
        assertEquals("Prueba: botón de los lentes (diagnóstico)", strings["media_probe_switch"])
    }

    @Test fun probeOnlyRunsWithItsSwitchAndOnlyInDebug() {
        assertTrue(activity.contains("binding.swMediaProbe.visibility = if (BuildConfig.DEBUG)"))
        assertTrue(activity.contains("if (BuildConfig.DEBUG && MediaButtonProbe.enabled(this)) startMediaProbe()"))
        assertTrue(activity.contains("mediaProbe?.release()"))
        val probe = ModuleFiles.file("src/main/java/com/itglasses/itglassesandroid/MediaButtonProbe.kt").readText()
        assertTrue("fase A: solo registra", probe.contains("return true  // Fase A"))
        for (leak in listOf("startRecording", "beginVoice", "Log.i(", "Log.w(", "Log.e(")) assertFalse(leak, probe.contains(leak))
    }
}
