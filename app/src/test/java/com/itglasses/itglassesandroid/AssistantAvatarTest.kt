package com.itglasses.itglassesandroid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Avatar beside every assistant answer and its waiting bubble, drawn only with palette colours. */
class AssistantAvatarTest {
    private val withAvatar = listOf(
        ChatEntry.Assistant(1, "respuesta"),
        ChatEntry.Assistant(2, "respuesta por voz", ChatEntry.Speech.SPEAKING),
        ChatEntry.Typing(3, 1),                                     // "Generando respuesta"
        ChatEntry.Typing(4, 1, ChatEntry.Waiting.THINKING),         // "Pensando"
    )
    private val withoutAvatar = listOf(
        ChatEntry.User(5, "hola", ChatEntry.Status.SENT),
        ChatEntry.User(6, "", ChatEntry.Status.TRANSCRIBING),
        ChatEntry.Notice(7, "aviso"),
        ChatEntry.Checking(8, 5),                                   // "Comprobando…"
    )

    @Test fun avatarResourcesUseThePalette() {
        val background = ModuleFiles.file("src/main/res/drawable/avatar_assistant.xml").readText()
        assertTrue("el fondo del avatar debe usar itg_surface_raised", background.contains("@color/itg_surface_raised"))
        assertTrue("el borde del avatar debe usar itg_outline", background.contains("@color/itg_outline"))
        val glyph = ModuleFiles.file("src/main/res/drawable/ic_assistant.xml").readText()
        assertTrue("las gafas deben usar itg_accent", glyph.contains("@color/itg_accent"))
    }

    @Test fun answersAndTheirWaitingBubblesCarryTheAvatar() {
        assertEquals(36, ChatAvatar.RESERVED_DP)
        for (entry in withAvatar) {
            assertTrue("$entry debería llevar avatar", ChatAvatar.has(entry))
            assertEquals("$entry debería reservar 36 dp", 36, ChatAvatar.reservedDp(entry))
        }
        for (entry in withoutAvatar) {
            assertTrue("$entry no debería llevar avatar", !ChatAvatar.has(entry))
            assertEquals("$entry no debería reservar espacio", 0, ChatAvatar.reservedDp(entry))
        }
    }

    @Test fun aRowWithAvatarStaysWithinEightyPercent() {
        for (density in listOf(1f, 2.625f, 3.5f)) for (available in listOf(720, 1080, 2400)) {
            val eighty = BubbleWidth.max(available = available, fallback = 0)
            for (entry in withAvatar + withoutAvatar) {
                val reserved = (ChatAvatar.reservedDp(entry) * density).toInt()  // ChatBubbles.dp()
                val bubble = BubbleWidth.max(available = available, fallback = 0, reserved = reserved)
                assertEquals("$entry a ${density}x en $available px: burbuja + avatar debe ser el 80 %", eighty, bubble + reserved)
            }
        }
    }

    @Test fun bubblesTakeTheAvatarRuleFromChatAvatar() {
        val code = ModuleFiles.file("src/main/java/com/itglasses/itglassesandroid/ChatBubbles.kt").readText()
        assertTrue("ChatBubbles debe calcular el espacio de la respuesta con ChatAvatar.reservedDp(entry)",
            code.contains("ChatAvatar.reservedDp(entry)"))
        assertTrue("las burbujas de espera deben decidir el avatar con ChatAvatar.has(entry)",
            code.contains("avatar = ChatAvatar.has(entry)"))
        assertTrue("el límite debe venir de BubbleWidth.max con el espacio reservado", code.contains("reserved = reserved,"))
    }
}
