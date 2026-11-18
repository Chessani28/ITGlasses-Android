package com.itglasses.itglassesandroid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/** Stage D: voice texts in Spanish, nothing hardcoded, and the Meta section grouped so phone can hide it. */
class VoiceTextsTest {
    /**
     * The app module, found without trusting the working directory: walk up from user.dir and from where this
     * test class was compiled (app/build/...) until a folder has src/main/AndroidManifest.xml.
     */
    private val module: File by lazy {
        val starts = listOfNotNull(
            File(System.getProperty("user.dir") ?: "."),
            runCatching { File(VoiceTextsTest::class.java.protectionDomain.codeSource.location.toURI()) }.getOrNull(),
        )
        starts.asSequence()
            .flatMap { start -> generateSequence(start.absoluteFile) { it.parentFile }.take(10) }
            .flatMap { dir -> sequenceOf(dir, File(dir, "app")) }
            .firstOrNull { File(it, "src/main/AndroidManifest.xml").isFile && File(it, "src/main/res").isDirectory }
            ?: throw AssertionError("App module not found from ${starts.map { it.absolutePath }}")
    }
    private fun file(path: String) = File(module, path).also { assertTrue("Falta $path", it.isFile) }
    private fun xml(path: String): Element =
        DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder().parse(file(path)).documentElement

    private val strings: Map<String, String> by lazy {
        val nodes = xml("src/main/res/values/strings.xml").getElementsByTagName("string")
        (0 until nodes.length).map { nodes.item(it) as Element }.associate { it.getAttribute("name") to it.textContent }
    }

    /** Existing keys translated in stage D. */
    private val translated = mapOf(
        "phone_audio_mode" to "Micrófono y altavoz del celular",
        "transcription_pending" to "Aquí aparecerá lo que digas",
        "transcription_empty" to "No se reconoció ninguna voz.",
        "ai_response_pending" to "Aquí aparecerá la respuesta",
        "chat_placeholder" to "Escribe un mensaje…",
        "send" to "Enviar",
    )

    /**
     * The 22 voice keys approved in stage D. A fixed list, not every "voice_*" in strings.xml: voice_input and
     * voice_output predate this stage, are unused and are left alone (to clean up separately).
     */
    private val voiceTexts = mapOf(
        "voice_button_start" to "Hablar",
        "voice_button_stop" to "Detener",
        "voice_state_recording" to "Grabando… %1\$d s",
        "voice_state_transcribing" to "Transcribiendo…",
        "voice_state_thinking" to "Pensando…",
        "voice_state_synthesizing" to "Preparando la voz…",
        "voice_state_speaking" to "Hablando…",
        "voice_state_ready" to "Listo",
        "voice_state_error" to "Error",
        "voice_mic_denied" to "Permiso de micrófono denegado.",
        "voice_mic_unsupported" to "Este celular no permite grabar audio en el formato necesario.",
        "voice_mic_unavailable" to "No se pudo iniciar el micrófono. Ciérralo en otras apps e inténtalo de nuevo.",
        "voice_recording_empty" to "La grabación quedó vacía. Inténtalo de nuevo.",
        "voice_saving" to "Guardando la grabación…",
        "voice_saved" to "Grabación guardada (%1\$d s).",
        "voice_save_failed" to "No se pudo guardar la grabación.",
        "voice_clarification" to "Hace falta aclarar la pregunta.",
        "voice_answer_received" to "Respuesta recibida",
        "voice_no_audio" to "No hay audio para reproducir.",
        "voice_playing" to "Reproduciendo la respuesta…",
        "voice_playback_done" to "Reproducción terminada",
        "voice_playback_failed" to "No se pudo reproducir la respuesta.",
    )
    private val approved = translated + voiceTexts

    @Test fun everyVoiceTextIsTheApprovedOne() {
        assertEquals(22, voiceTexts.size)
        for ((name, text) in approved) assertEquals(name, text, strings[name])
    }

    @Test fun ellipsisIsTheCharacterAndOnlyTwoTextsTakeANumber() {
        for (name in approved.keys) {
            val text = strings.getValue(name)
            assertFalse(name, text.contains("..."))
            assertEquals(name, name in setOf("voice_state_recording", "voice_saved"), text.contains("%1\$d"))
        }
    }

    @Test fun everyVoiceKeyIsUsed() {
        val code = file("src/main/java/com/itglasses/MainActivity.kt").readText() +
            file("src/main/res/layout/activity_main.xml").readText()
        for (name in voiceTexts.keys) {
            assertTrue("$name no se usa", code.contains("R.string.$name") || code.contains("@string/$name"))
        }
    }

    @Test fun metaViewsAreGroupedInMetaSection() {
        val android = "http://schemas.android.com/apk/res/android"
        val all = xml("src/main/res/layout/activity_main.xml").getElementsByTagName("*")
        val section = (0 until all.length).map { all.item(it) as Element }
            .single { it.getAttributeNS(android, "id") == "@+id/meta_section" }
        val inside = section.getElementsByTagName("*").let { nodes ->
            (0 until nodes.length).map { (nodes.item(it) as Element) }
        }
        val ids = inside.map { it.getAttributeNS(android, "id") }.filter { it.isNotEmpty() }.toSet()
        assertEquals(setOf("@+id/tv_meta_status", "@+id/btn_meta_register", "@+id/btn_meta_session", "@+id/btn_meta_stop"), ids)
        assertTrue(inside.any { it.getAttributeNS(android, "text") == "@string/meta_mode_title" })
    }

    @Test fun voiceButtonTextIsAResourceNotHardcoded() {
        val android = "http://schemas.android.com/apk/res/android"
        val all = xml("src/main/res/layout/activity_main.xml").getElementsByTagName("*")
        val voice = (0 until all.length).map { all.item(it) as Element }
            .single { it.getAttributeNS(android, "id") == "@+id/btn_voice" }
        assertEquals("@string/voice_button_start", voice.getAttributeNS(android, "text"))
        val code = file("src/main/java/com/itglasses/MainActivity.kt").readText()
        for (old in listOf("\"Voice\"", "\"Stop\"", "\"Recording...", "\"Transcribing...\"", "\"Playback error\"")) {
            assertFalse(old, code.contains(old))
        }
    }
}
