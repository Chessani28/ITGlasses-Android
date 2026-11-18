package com.itglasses.itglassesandroid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/** Welcome in the conversation area: when it shows, its approved texts and its place in the layout. */
class ChatEmptyTest {
    private val layout = "src/main/res/layout/activity_main.xml"

    @Test fun visibleOnlyInPairingModeConnectedAndBeforeTheFirstMessage() {
        for (pairingMode in listOf(true, false)) for (connected in listOf(true, false)) for (empty in listOf(true, false)) {
            assertEquals("$pairingMode/$connected/$empty", pairingMode && connected && empty,
                ChatEmpty.visible(pairingMode, connected, empty))
        }
    }

    @Test fun logIsEmptyOnlyUntilTheFirstMessage() {
        val log = ChatLog()
        assertTrue(log.isEmpty)
        val (id, _) = log.send("Hola")
        assertFalse(log.isEmpty)
        log.failed(id, null)
        assertFalse(log.isEmpty)
        log.answered(log.send("Otra").first, "Respuesta")
        assertFalse(log.isEmpty)
    }

    @Test fun welcomeTextsAreTheApprovedOnesAndUsed() {
        val strings = ModuleFiles.elements(ModuleFiles.xml("src/main/res/values/strings.xml"), "string")
            .associate { it.getAttribute("name") to it.textContent }
        assertEquals("Hola, soy tu asistente de ITGlasses", strings["chat_empty_title"])
        assertEquals("Escribe una pregunta sobre tus equipos o usa la voz.", strings["chat_empty_subtitle"])
        val xml = ModuleFiles.file(layout).readText()
        assertTrue(xml.contains("@string/chat_empty_title"))
        assertTrue(xml.contains("@string/chat_empty_subtitle"))
        val code = ModuleFiles.file("src/main/java/com/itglasses/MainActivity.kt").readText()
        assertTrue(code.contains("ChatEmpty.visible("))
        assertTrue(code.contains("binding.chatEmpty"))
    }

    @Test fun welcomeSitsJustBeforeTheConversationWithADecorativeAvatar() {
        val all = ModuleFiles.elements(ModuleFiles.xml(layout), "*")
        val id = { e: Element -> e.getAttributeNS(ModuleFiles.ANDROID, "id") }
        val empty = all.single { id(it) == "@+id/chat_empty" }
        assertEquals("gone", empty.getAttributeNS(ModuleFiles.ANDROID, "visibility"))
        val siblings = (0 until empty.parentNode.childNodes.length).mapNotNull { empty.parentNode.childNodes.item(it) as? Element }
        assertEquals("@+id/chat_messages", id(siblings[siblings.indexOf(empty) + 1]))
        val avatar = ModuleFiles.elements(empty, "ImageView").single()
        assertEquals("no", avatar.getAttributeNS(ModuleFiles.ANDROID, "importantForAccessibility"))
        assertEquals("@drawable/ic_assistant", avatar.getAttributeNS(ModuleFiles.ANDROID, "src"))
    }
}
