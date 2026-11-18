package com.itglasses.itglassesandroid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Stage C: the two new texts, and the layout keeps every id with the message bar fixed at the bottom. */
class ChatTextsTest {
    private val strings: Map<String, String> by lazy {
        ModuleFiles.elements(ModuleFiles.xml("src/main/res/values/strings.xml"), "string")
            .associate { it.getAttribute("name") to it.textContent }
    }

    @Test fun chatTextsAreTheApprovedOnesAndUsed() {
        val approved = mapOf("chat_generating_label" to "Generando respuesta", "chat_not_sent" to "No enviado",
            "chat_transcribing_label" to "Transcribiendo", "chat_thinking_label" to "Pensando")
        val code = ModuleFiles.file("src/main/java/com/itglasses/itglassesandroid/ChatBubbles.kt").readText()
        for ((name, text) in approved) {
            assertEquals(name, text, strings[name])
            assertTrue("$name no se usa", code.contains("R.string.$name"))
        }
    }

    @Test fun messageBarIsFixedBelowTheScrollAndKeepsItsIds() {
        val root = ModuleFiles.xml("src/main/res/layout/activity_main.xml")
        assertEquals("LinearLayout", root.tagName)
        val children = (0 until root.childNodes.length).mapNotNull { root.childNodes.item(it) as? org.w3c.dom.Element }
        assertEquals(listOf("@+id/main_scroll", "@+id/message_bar"), children.map { it.getAttributeNS(ModuleFiles.ANDROID, "id") })
        val bar = children.last()
        val inBar = ModuleFiles.elements(bar, "*").map { it.getAttributeNS(ModuleFiles.ANDROID, "id") }
        assertEquals(listOf("@+id/et_message", "@+id/btn_send"), inBar)
    }

    @Test fun conversationContainerAndSharedTokenOutputBothExist() {
        val ids = ModuleFiles.elements(ModuleFiles.xml("src/main/res/layout/activity_main.xml"), "*")
            .map { it.getAttributeNS(ModuleFiles.ANDROID, "id") }
        for (id in listOf("@+id/chat_messages", "@+id/tv_chat_output", "@+id/tv_transcription", "@+id/tv_ai_response",
                "@+id/card_pairing", "@+id/btn_voice", "@+id/meta_section", "@+id/tv_status")) {
            assertTrue(id, id in ids)
        }
    }
}
