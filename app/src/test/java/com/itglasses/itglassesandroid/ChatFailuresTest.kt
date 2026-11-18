package com.itglasses.itglassesandroid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChatFailuresTest {
    @Test fun failuresTheCardDoesNotExplainGetANoticeInTheConversation() {
        for (text in CallText.entries.filter { it != CallText.REMOVED && it != CallText.GENERATING }) {
            assertEquals(text.name, ChatFailure.WithNotice(text, null), ChatFailures.present(CallOutcome.Notice(text)))
        }
        assertEquals(ChatFailure.WithNotice(CallText.UNEXPECTED_CODE, "502"),
            ChatFailures.present(CallOutcome.Notice(CallText.UNEXPECTED_CODE, "502")))
    }

    @Test fun deletedDeviceAndForbiddenCallsAreLeftToTheCardOrTheCheck() {
        assertEquals(ChatFailure.NotSentOnly, ChatFailures.present(CallOutcome.Notice(CallText.REMOVED)))
        assertEquals(ChatFailure.Check, ChatFailures.present(CallOutcome.DeviceCheck))
    }

    @Test fun checkResolutionFollowsTheController() {
        assertEquals(ChatCheckResolution.UNAVAILABLE, ChatFailures.resolution(CallCheck.UNAVAILABLE))
        assertEquals(ChatCheckResolution.LEFT_TO_CARD, ChatFailures.resolution(CallCheck.RESOLVED))
        assertNull(ChatFailures.resolution(CallCheck.CHECKING))
        assertNull(ChatFailures.resolution(CallCheck.IDLE))
    }
}
