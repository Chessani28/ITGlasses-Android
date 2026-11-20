package com.itglasses.itglassesandroid

import org.junit.Assert.assertTrue
import org.junit.Test

/** Stage B3: voice bubbles and labels in ChatBubbles (drawing is checked on the phone). */
class VoiceBubblesTest {
    private val code by lazy { ModuleFiles.file("src/main/java/com/itglasses/itglassesandroid/ChatBubbles.kt").readText() }

    @Test fun voiceTextsAreTheExistingOnesOrTheApprovedLabels() {
        for (name in listOf("chat_transcribing_label", "chat_thinking_label", "voice_state_transcribing",
                "voice_state_thinking", "voice_state_synthesizing", "voice_state_speaking")) {
            assertTrue("$name no se usa", code.contains("R.string.$name"))
        }
    }

    @Test fun placeholderIsRebuiltInPlaceAndThinkingCarriesTheAvatar() {
        assertTrue(code.contains("placeholder = true"))
        assertTrue(code.contains("container.addView(row.view, index)"))
        assertTrue("\"Pensando\" debe llevar avatar", ChatAvatar.has(ChatEntry.Typing(2, 1, ChatEntry.Waiting.THINKING)))
        // Label on the green bubble: text_primary (text_secondary would be below 4.5:1).
        assertTrue(code.contains("textColorRes = R.color.itg_text_primary"))
    }

    @Test fun speechLabelIsALiveRegionWithAPulsingSpeaker() {
        assertTrue(code.contains("ACCESSIBILITY_LIVE_REGION_POLITE"))
        assertTrue(code.contains("R.drawable.ic_speaker"))
        assertTrue(code.contains("Pulse(icon)"))
        assertTrue(ModuleFiles.file("src/main/res/drawable/ic_speaker.xml").readText().contains("@color/itg_text_secondary"))
    }
}
