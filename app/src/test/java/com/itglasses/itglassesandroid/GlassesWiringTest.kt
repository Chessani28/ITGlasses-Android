package com.itglasses.itglassesandroid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Demo lentes: the switch, permissions, texts and the hooks in MainActivity. */
class GlassesWiringTest {
    private val activity by lazy { ModuleFiles.file("src/main/java/com/itglasses/MainActivity.kt").readText() }
    private val strings: Map<String, String> by lazy {
        ModuleFiles.elements(ModuleFiles.xml("src/main/res/values/strings.xml"), "string")
            .associate { it.getAttribute("name") to it.textContent }
    }

    @Test fun switchIsInTheLayoutWithItsText() {
        val switch = ModuleFiles.elements(ModuleFiles.xml("src/main/res/layout/activity_main.xml"), "*")
            .single { it.getAttributeNS(ModuleFiles.ANDROID, "id") == "@+id/sw_glasses" }
        assertEquals("@string/glasses_switch", switch.getAttributeNS(ModuleFiles.ANDROID, "text"))
        assertEquals("Usar lentes (Bluetooth)", strings["glasses_switch"])
        assertTrue(activity.contains("GlassesSettings.set(this, on)"))
        assertTrue(activity.contains("binding.swGlasses.isChecked = GlassesSettings.enabled(this)"))
    }

    @Test fun fallbackNoticeIsSpanishAndUsed() {
        assertTrue(strings.getValue("glasses_unavailable").startsWith("No se pudieron usar los lentes"))
        assertTrue(activity.contains("R.string.glasses_unavailable"))
    }

    @Test fun manifestHasTheAudioPermissions() {
        val permissions = ModuleFiles.elements(ModuleFiles.xml("src/main/AndroidManifest.xml"), "uses-permission")
            .associate { it.getAttributeNS(ModuleFiles.ANDROID, "name") to it.getAttributeNS(ModuleFiles.ANDROID, "maxSdkVersion") }
        assertTrue("android.permission.MODIFY_AUDIO_SETTINGS" in permissions)
        assertEquals("30", permissions["android.permission.BLUETOOTH"])
    }

    @Test fun everythingNewOnlyRunsWithTheSwitchOn() {
        assertTrue("sin el interruptor, la grabación de siempre",
            activity.contains("if (!GlassesSettings.enabled(this)) {\r\n            startRecording()") ||
                activity.contains("if (!GlassesSettings.enabled(this)) {\n            startRecording()"))
        assertTrue("VAD solo con lentes", activity.contains("endOfSpeech = if (GlassesSettings.enabled(this)) EndOfSpeech() else null"))
        assertTrue("el VAD termina igual que el límite de 30 s", activity.contains("if (VoiceTiming.reachedMax(elapsed) || ended)"))
    }

    @Test fun routeIsUsedAndAlwaysReleased() {
        assertTrue(activity.contains("glassesAudio.acquire()"))
        assertTrue(activity.contains("glassesAudio.input?.let { record.setPreferredDevice(it) }"))
        assertTrue(activity.contains("AudioAttributes.USAGE_VOICE_COMMUNICATION"))
        assertTrue(activity.contains("glassesAudio.release()"))
        assertTrue("fin de turno en Listo y Error", activity.contains("if (state == UiState.Ready || state == UiState.Error)"))
        assertTrue(activity.contains("FLAG_KEEP_SCREEN_ON"))
    }

    @Test fun glassesLogIsDebugOnlyAndNeverLogsNames() {
        val audio = ModuleFiles.file("src/main/java/com/itglasses/itglassesandroid/GlassesAudio.kt").readText()
        assertTrue(audio.contains("const val TAG = \"ITG-Glasses\""))
        assertTrue(audio.contains("if (!BuildConfig.DEBUG) return"))
        for (leak in listOf("productName", ".address", "Log.i(", "Log.w(", "Log.e(")) assertTrue(leak, !audio.contains(leak))
    }

    @Test fun busyRetryIsTheOutermostInterceptor() {
        val builder = activity.substringAfter("OkHttpClient.Builder()")
        assertTrue(builder.indexOf(".addInterceptor(BusyRetryInterceptor())") in 0 until builder.indexOf("pairing.authorize(chain)"))
    }
}
