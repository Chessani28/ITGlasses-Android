package com.itglasses.itglassesandroid

import com.itglasses.itglassesandroid.PairingAction.POLL
import com.itglasses.itglassesandroid.PairingAction.REGISTER
import com.itglasses.itglassesandroid.PairingState.Reason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PairingScreenTest {
    private val expiresAt = 1_794_932_400L
    private fun view(state: PairingState, model: String? = "Samsung SM-S911B", now: Long = expiresAt - 299) =
        PairingScreen.view(state, now, model)

    private val pairingStates = listOf(
        PairingState.Registering(), PairingState.Registering(Reason.EXPIRED), PairingState.Registering(Reason.REMOVED),
        PairingState.Pending("482913", expiresAt), PairingState.Connected, PairingState.Blocked, PairingState.Denied,
        PairingState.Offline(20, 3, POLL, PairingState.Registering()), PairingState.RateLimited(42, REGISTER, PairingState.Registering()),
        PairingState.CertificateRejected(POLL), PairingState.ClockMismatch(POLL), PairingState.Failed(POLL),
    )

    @Test fun pendingShowsGroupedCodeSpokenDigitsAndCountdown() {
        val pending = view(PairingState.Pending("482913", expiresAt))
        assertEquals(PairingText.PENDING_TITLE, pending.title)
        assertEquals(PairingText.PENDING_DETAIL, pending.detail)
        assertEquals("482 913", pending.code)
        assertEquals("4 8 2 9 1 3", pending.spokenCode)
        assertEquals(PairingText.PENDING_EXPIRES, pending.note)
        assertEquals("4:59", pending.noteArg)
        assertEquals("0:00", view(PairingState.Pending("482913", expiresAt), now = expiresAt + 10).noteArg)
        assertNull(pending.button)
    }

    @Test fun registeringTextsDependOnTheReason() {
        assertEquals(PairingView(PairingText.REGISTERING_TITLE, PairingText.REGISTERING_DETAIL), view(PairingState.Registering()))
        assertEquals(PairingView(PairingText.REGISTERING_TITLE, PairingText.REGISTERING_DETAIL),
            view(PairingState.Registering(Reason.REQUESTED)))
        assertEquals(PairingView(PairingText.EXPIRED_TITLE), view(PairingState.Registering(Reason.EXPIRED)))
        assertEquals(PairingView(PairingText.REMOVED_TITLE), view(PairingState.Registering(Reason.REMOVED)))
    }

    @Test fun connectedShowsTheModelOrAGenericText() {
        assertEquals(PairingView(PairingText.CONNECTED_TITLE, PairingText.CONNECTED_DETAIL, "Samsung SM-S911B", canChat = true),
            view(PairingState.Connected))
        assertEquals(PairingText.CONNECTED_DETAIL_UNKNOWN, view(PairingState.Connected, model = null).detail)
    }

    @Test fun buttonsMatchTheApprovedTable() {
        assertEquals(PairingText.BUTTON_CHECK_AGAIN, view(PairingState.Blocked).button)
        assertEquals(PairingText.BUTTON_REQUEST_AGAIN, view(PairingState.Denied).button)
        for (state in listOf(PairingState.Offline(5, 1, POLL, PairingState.Registering()), PairingState.CertificateRejected(POLL),
                PairingState.ClockMismatch(POLL), PairingState.Failed(POLL))) {
            assertEquals(state.toString(), PairingText.BUTTON_RETRY, view(state).button)
        }
        assertNull(view(PairingState.RateLimited(42, REGISTER, PairingState.Registering())).button)
        assertNull(view(PairingState.Connected).button)
    }

    @Test fun waitsShowTheirSeconds() {
        assertEquals(PairingView(PairingText.OFFLINE_TITLE, PairingText.OFFLINE_DETAIL, "20", button = PairingText.BUTTON_RETRY),
            view(PairingState.Offline(20, 3, POLL, PairingState.Registering())))
        assertEquals(PairingView(PairingText.RATE_LIMITED_TITLE, PairingText.RATE_LIMITED_DETAIL, "42"),
            view(PairingState.RateLimited(42, REGISTER, PairingState.Registering())))
    }

    @Test fun errorTexts() {
        assertEquals(PairingText.BLOCKED_DETAIL, view(PairingState.Blocked).detail)
        assertEquals(PairingText.DENIED_DETAIL, view(PairingState.Denied).detail)
        assertEquals(PairingText.CERTIFICATE_TITLE, view(PairingState.CertificateRejected(POLL)).title)
        assertEquals(PairingText.CLOCK_DETAIL, view(PairingState.ClockMismatch(POLL)).detail)
        assertEquals(PairingText.FAILED_TITLE, view(PairingState.Failed(POLL)).title)
    }

    @Test fun pairingModeNeverShowsTheSharedTokenFieldsAndOnlyChatsWhenConnected() {
        for (state in pairingStates) {
            val shown = view(state)
            assertFalse(state.toString(), shown.sharedTokenMode)
            assertEquals(state.toString(), state == PairingState.Connected, shown.canChat)
            assertTrue(state.toString(), shown.title != null || shown.detail != null)
        }
    }

    @Test fun apkWithoutOriginKeepsSharedTokenMode() {
        assertEquals(PairingView(detail = PairingText.UNCONFIGURED_DETAIL, sharedTokenMode = true, canChat = true),
            view(PairingState.Unconfigured))
    }
}
