package com.itglasses.itglassesandroid

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Background voice (parts 1–2 and phase B): pure rules, wiring, and the demo-lentes-v1 mode untouched. */
class BackgroundVoiceTest {
    private fun source(path: String) = ModuleFiles.file(path).readText()
    private val activity by lazy { source("src/main/java/com/itglasses/MainActivity.kt") }
    private val pipeline by lazy { source("src/main/java/com/itglasses/itglassesandroid/VoicePipeline.kt") }
    private val service by lazy { source("src/main/java/com/itglasses/itglassesandroid/VoiceForegroundService.kt") }

    // --- Phase B: what a tap does -----------------------------------------------------------------------------

    @Test fun oneTapTalksThenStopsAndSends() {
        for (key in listOf(KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)) {
            assertEquals(TalkAction.START, TalkButton.forKey(key, KeyEvent.ACTION_DOWN, 0, TurnStage.READY))
            assertEquals(TalkAction.STOP_AND_SEND, TalkButton.forKey(key, KeyEvent.ACTION_DOWN, 0, TurnStage.LISTENING))
        }
    }

    @Test fun keyUpRepeatsNextAndBusyStagesDoNothing() {
        val play = KeyEvent.KEYCODE_MEDIA_PLAY
        assertEquals(TalkAction.IGNORE, TalkButton.forKey(play, KeyEvent.ACTION_UP, 0, TurnStage.READY))
        assertEquals(TalkAction.IGNORE, TalkButton.forKey(play, KeyEvent.ACTION_DOWN, 1, TurnStage.READY))
        assertEquals(TalkAction.IGNORE, TalkButton.forKey(KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.ACTION_DOWN, 0, TurnStage.READY))
        assertEquals(TalkAction.IGNORE, TalkButton.forTap(TurnStage.THINKING))
        assertEquals(TalkAction.IGNORE, TalkButton.forTap(TurnStage.SPEAKING))
    }

    // --- PairingAccess: one instance, DevicePairing unchanged --------------------------------------------------

    @Test fun oneValuePerKeyEvenFromManyThreads() {
        val singletons = ProcessSingletons<String, Any>()
        val created = AtomicInteger()
        val pool = Executors.newFixedThreadPool(8)
        val start = CountDownLatch(1)
        val results = (1..32).map { pool.submit<Any> { start.await(); singletons.get("o") { created.incrementAndGet(); Any() } } }
        start.countDown()
        val values = results.map { it.get(5, TimeUnit.SECONDS) }
        pool.shutdown()
        assertEquals(1, created.get())
        values.forEach { assertSame(values.first(), it) }
        assertFalse(singletons.get("other") { Any() } === values.first())
    }

    @Test fun devicePairingIsByteForByteTheDemoVersion() {
        // SHA-256 (LF line endings) of DevicePairing.kt at tag demo-lentes-v1: pairing and token renewal unchanged.
        // QR pairing (2026-11-26) changed exactly two lines: the optional certificate pin and the transport that
        // uses it. Undoing those two lines must give back the demo file byte for byte.
        val text = source("src/main/java/com/itglasses/itglassesandroid/DevicePairing.kt").replace("\r\n", "\n")
            .replace("class DevicePairing(context: Context, val origin: String, val pin: String? = null) {",
                "class DevicePairing(context: Context, val origin: String) {")
            .replace("private val transport = ServerTrust.apply(OkHttpClient.Builder(), origin, pin).followRedirects(false)",
                "private val transport = OkHttpClient.Builder().followRedirects(false)")
        val hash = MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
        assertEquals("fb3c80238346312ab725972d01b244e1ba290f697ddeab2c8b83b7ee4bdf4820", hash)
    }

    @Test fun activityAndServiceShareOneDevicePairing() {
        // Both use the server this phone uses (scanned QR, else the APK's built-in origin).
        assertTrue(activity.contains("private val pairing by lazy { PairingAccess.current(applicationContext) }"))
        assertFalse("ninguna otra instancia en la app", activity.contains("DevicePairing(applicationContext"))
        assertTrue(service.contains("PairingAccess.current(applicationContext)"))
        assertFalse(service.contains("DevicePairing("))
        assertFalse(pipeline.contains("DevicePairing("))
    }

    // --- The service: manifest, SCO only per turn, fallback, TODO -----------------------------------------------

    @Test fun manifestDeclaresTheMicrophoneServiceAndItsPermissions() {
        val manifest = ModuleFiles.xml("src/main/AndroidManifest.xml")
        val names = ModuleFiles.elements(manifest, "uses-permission").map { it.getAttributeNS(ModuleFiles.ANDROID, "name") }
        for (p in listOf("FOREGROUND_SERVICE", "FOREGROUND_SERVICE_MICROPHONE", "POST_NOTIFICATIONS")) {
            assertTrue(p, "android.permission.$p" in names)
        }
        val svc = ModuleFiles.elements(manifest, "service").single { it.getAttributeNS(ModuleFiles.ANDROID, "name") == ".VoiceForegroundService" }
        assertEquals("microphone", svc.getAttributeNS(ModuleFiles.ANDROID, "foregroundServiceType"))
        assertEquals("false", svc.getAttributeNS(ModuleFiles.ANDROID, "exported"))
    }

    @Test fun scoIsOnlyOpenDuringATurn() {
        assertFalse("el servicio nunca abre la ruta", service.contains("acquire()"))
        assertEquals("una sola apertura, dentro del turno", 1, Regex("""glasses\.acquire\(\)""").findAll(pipeline).count())
        val turn = pipeline.substringAfter("private suspend fun runTurn(").substringBefore("private suspend fun fail(")
        assertTrue(turn.contains("glasses.acquire()"))
        assertTrue("siempre se libera al final del turno", turn.substringAfter("} finally {").contains("glasses.release()"))
    }

    @Test fun failingToStartFallsBackToTheUsualMode() {
        assertTrue(service.contains("BackgroundVoice.set(this, false)"))
        assertTrue(service.contains("BackgroundTurns.startFailures.tryEmit(Unit)"))
        assertTrue(activity.contains("BackgroundTurns.startFailures.collect { backgroundFailed() }"))
        val activityLf = activity.replace("\r\n", "\n")
        // Android refuses the start request: back to the usual mode right away.
        val start = activityLf.substringAfter("private fun startBackgroundVoice() {").substringBefore("\n    }\n")
        val refused = start.substringAfter("if (!VoiceForegroundService.start(this)) {").substringBefore("}")
        assertTrue("si Android rechaza el arranque", refused.contains("backgroundFailed()") && refused.contains("return"))
        // Accepted but not running 3 s later: also back to the usual mode.
        assertTrue("si no arrancó a los 3 s",
            start.contains("if (BackgroundVoice.enabled(this@MainActivity) && !BackgroundTurns.running.value) backgroundFailed()"))
        // What "the usual mode" means: setting off, switch off, a notice.
        val failed = activityLf.substringAfter("private fun backgroundFailed(").substringBefore("\n    }\n")
        for (step in listOf("BackgroundVoice.set(this, false)", "binding.swBackground.isChecked = false", "voiceNotice(text)")) {
            assertTrue(step, failed.contains(step))
        }
    }

    @Test fun duplicatedLogicIsMarkedToUnify() {
        assertTrue(pipeline.contains("TODO(unificar)"))
    }

    // --- demo-lentes-v1 mode untouched with the new switches off ------------------------------------------------

    @Test fun withTheSwitchOffTheAppKeepsItsOwnVoice() {
        assertTrue("Hablar solo se delega con el servicio encendido",
            activity.contains("if (BackgroundTurns.running.value) {\r\n            VoiceForegroundService.talk(this)") ||
                activity.contains("if (BackgroundTurns.running.value) {\n            VoiceForegroundService.talk(this)"))
        assertTrue("el servicio solo arranca con su interruptor",
            activity.contains("} else if (BackgroundVoice.enabled(this)) {"))
        assertFalse("la app no reclama botones multimedia", activity.contains("MediaSession("))
        val strings = ModuleFiles.elements(ModuleFiles.xml("src/main/res/values/strings.xml"), "string")
            .associate { it.getAttribute("name") to it.textContent }
        assertEquals("Escuchar con el celular bloqueado", strings["background_switch"])
    }
}
