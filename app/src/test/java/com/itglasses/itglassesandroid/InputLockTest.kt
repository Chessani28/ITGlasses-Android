package com.itglasses.itglassesandroid

import org.junit.Assert.assertEquals
import org.junit.Test

class InputLockTest {
    private fun of(canChat: Boolean = true, chat: Boolean = false, voice: VoiceStage = VoiceStage.IDLE, processing: Boolean = false) =
        InputLock.of(canChat, chat, voice, processing)

    @Test fun withoutConnectionNothingIsEnabled() {
        for (stage in VoiceStage.values()) assertEquals(InputState(send = false, voice = false), of(canChat = false, voice = stage))
    }

    @Test fun aTypedQuestionBlocksVoiceAndAnotherMessage() {
        assertEquals(InputState(send = true, voice = true), of())
        assertEquals(InputState(send = false, voice = false), of(chat = true))
    }

    @Test fun aSpokenQuestionBlocksTypingUntilTheAudioStarts() {
        for (stage in listOf(VoiceStage.LISTENING, VoiceStage.TRANSCRIBING, VoiceStage.THINKING, VoiceStage.SYNTHESIZING)) {
            assertEquals("$stage", InputState(send = false, voice = false), of(voice = stage))
        }
        // While the answer is spoken you can type; voice waits until the audio ends.
        assertEquals(InputState(send = true, voice = false), of(voice = VoiceStage.SPEAKING))
        for (stage in listOf(VoiceStage.IDLE, VoiceStage.DONE, VoiceStage.CANCELLED, VoiceStage.FAILED)) {
            assertEquals("$stage", InputState(send = true, voice = true), of(voice = stage))
        }
        assertEquals(InputState(send = true, voice = false), of(processing = true))
    }
}
