package com.itglasses.itglassesandroid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The service keeps the temple tap: one owner at a time, reclaimed after each turn and on return to the app. */
class ReclaimTest {
    private fun source(path: String) = ModuleFiles.file(path).readText().replace("\r\n", "\n")
    private val activity by lazy { source("src/main/java/com/itglasses/MainActivity.kt") }
    private val service by lazy { source("src/main/java/com/itglasses/itglassesandroid/VoiceForegroundService.kt") }

    @Test fun probeCannotTurnOnWhileTheServiceRunsAndGivesTheTapBack() {
        val listener = activity.substringAfter("binding.swMediaProbe.setOnCheckedChangeListener").substringBefore("if (BuildConfig.DEBUG && MediaButtonProbe")
        assertTrue(listener.indexOf("if (on && BackgroundTurns.running.value)") in 0 until listener.indexOf("MediaButtonProbe.set(this, on)"))
        assertTrue(listener.contains("VoiceForegroundService.reclaim(this, \"probe_off\")"))
        assertTrue("tampoco al abrir la app", activity.substringAfter("private fun startMediaProbe()")
            .substringBefore("MediaButtonProbe(this)").contains("if (BackgroundTurns.running.value)"))
        assertTrue("el servicio apaga la prueba al arrancar",
            activity.substringAfter("private fun startBackgroundVoice()").substringBefore("VoiceForegroundService.start(this)")
                .contains("binding.swMediaProbe.isChecked = false"))
        val strings = ModuleFiles.elements(ModuleFiles.xml("src/main/res/values/strings.xml"), "string")
            .associate { it.getAttribute("name") to it.textContent }
        assertTrue(strings.getValue("media_probe_busy").startsWith("Apaga primero"))
    }

    @Test fun sessionIsReassertedAfterEachTurnAndOnReturnToTheApp() {
        assertTrue(service.contains("ACTION_RECLAIM -> reclaimMediaButtons(source)"))
        assertTrue(service.contains("main.postDelayed({ reclaimMediaButtons(\"turn_end\") }, RECLAIM_AFTER_TURN_MS)"))
        val reclaim = service.substringAfter("private fun reclaimMediaButtons(").substringBefore("private fun playingState()")
        for (step in listOf("current.isActive = true", "current.setPlaybackState(playingState())", "MediaClaim.playSilence()")) {
            assertTrue(step, reclaim.contains(step))
        }
        assertTrue(activity.contains("VoiceForegroundService.reclaim(this, \"app_start\")"))
    }

    @Test fun savedOnButNotRunningRestartsOrShowsOff() {
        val start = activity.substringAfter("override fun onStart()").substringBefore("override fun onStop()")
        assertTrue(start.contains("if (canRun) startBackgroundVoice() else showBackgroundOff()"))
        assertTrue("comprueba que de verdad arrancó",
            activity.contains("if (BackgroundVoice.enabled(this@MainActivity) && !BackgroundTurns.running.value) backgroundFailed()"))
        assertTrue("\"Apagar\" se queda apagado", service.substringAfter("if (intent?.action == ACTION_SHUTDOWN) {")
            .substringBefore("stopSelf()").contains("BackgroundVoice.set(this, false)"))
        assertEquals(1, Regex("""private fun showBackgroundOff\(\)""").findAll(activity).count())
    }
}
