package com.itglasses.itglassesandroid

import com.itglasses.itglassesandroid.PairingAction.NONE
import com.itglasses.itglassesandroid.PairingAction.POLL
import com.itglasses.itglassesandroid.PairingAction.RECHECK
import com.itglasses.itglassesandroid.PairingAction.REGISTER
import com.itglasses.itglassesandroid.PairingAction.VERIFY
import com.itglasses.itglassesandroid.PairingState.Blocked
import com.itglasses.itglassesandroid.PairingState.Connected
import com.itglasses.itglassesandroid.PairingState.Pending
import com.itglasses.itglassesandroid.PairingState.Reason
import com.itglasses.itglassesandroid.PairingState.Registering
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.net.ConnectException
import java.security.cert.CertificateExpiredException
import javax.net.ssl.SSLHandshakeException

class PairingControllerTest {
    private class HttpError(override val status: Int, override val retryAfter: Int? = null) :
        IOException("HTTP $status"), HttpFailure

    /** Scripted gateway: each call takes the next reply (a PairingEvent, or a Throwable to throw). */
    private class FakeGateway(vararg replies: Any) : PairingGateway {
        private val script = ArrayDeque(replies.toList())
        val calls = mutableListOf<String>()
        var forgets = 0
        override var hasRequest = false
        override var clockImplausible = false

        fun add(vararg replies: Any) = script.addAll(replies)
        private fun reply(call: String): PairingEvent {
            calls += call
            val next = script.removeFirstOrNull() ?: throw AssertionError("Llamada inesperada: $call")
            if (next is Throwable) throw next
            return next as PairingEvent
        }
        override suspend fun register(withoutLabels: Boolean) = reply("register($withoutLabels)").also { hasRequest = true }
        override suspend fun status() = reply("status")
        override suspend fun claim() = reply("claim")
        override suspend fun verify() = reply("verify")
        override suspend fun forgetRequest() { forgets++; hasRequest = false }
    }

    private val pending = Pending("482913", 1_794_932_400L)
    private fun connected(gateway: FakeGateway) =
        PairingController(gateway, PairingStep(Connected, NONE)).also { gateway.hasRequest = true }

    @Test fun firstStartRegistersPollsClaimsAndConnects() = runBlocking {
        val gateway = FakeGateway(
            PairingEvent.Registered("482913", pending.expiresAt, 5), PairingEvent.Status("pending"),
            PairingEvent.Status("approved"), PairingEvent.Claimed, PairingEvent.Verified)
        val controller = PairingController(gateway, PairingMachine.start(true, false, false, null))
        controller.runOnce()
        assertEquals(pending, controller.state.value)
        assertEquals(PairingStep(pending, POLL, 5), controller.pending)
        repeat(4) { controller.runOnce() }
        assertEquals(Connected, controller.state.value)
        assertEquals(NONE, controller.pending.action)
        assertEquals(listOf("register(false)", "status", "status", "claim", "verify"), gateway.calls)
        controller.runOnce()  // Nothing to do: no extra calls.
        assertEquals(5, gateway.calls.size)
        assertEquals(0, gateway.forgets)
    }

    @Test fun expiredRequestIsForgottenAndRegisteredAgain() = runBlocking {
        val gateway = FakeGateway(PairingEvent.Status("expired"), PairingEvent.Registered("111222", 5, 5))
        gateway.hasRequest = true
        val controller = PairingController(gateway, PairingStep(pending, POLL))
        controller.runOnce()
        assertEquals(Registering(Reason.EXPIRED), controller.state.value)
        assertEquals(1, gateway.forgets)
        controller.runOnce()
        assertEquals(Pending("111222", 5), controller.state.value)
    }

    @Test fun invalidLabelsAreRetriedWithoutThem() = runBlocking {
        val gateway = FakeGateway(HttpError(422), PairingEvent.Registered("482913", 5, 5))
        val controller = PairingController(gateway, PairingMachine.start(true, false, false, null))
        controller.runOnce()
        controller.runOnce()
        assertEquals(listOf("register(false)", "register(true)"), gateway.calls)
    }

    @Test fun blockedKeepsItsIdentityRechecksEvery30SecondsAndOnTheButton() = runBlocking {
        val gateway = FakeGateway(HttpError(403), PairingEvent.Status("blocked"),
            PairingEvent.Status("active"), PairingEvent.Verified)
        gateway.hasRequest = true
        val controller = PairingController(gateway, PairingStep(Connected, VERIFY))
        controller.runOnce()
        assertEquals(PairingStep(Blocked, RECHECK, 30), controller.pending)
        controller.runOnce()
        assertEquals(PairingStep(Blocked, RECHECK, 30), controller.pending)
        controller.onEvent(PairingEvent.UserRetry)
        controller.runOnce()
        assertEquals(PairingStep(Blocked, RECHECK, 0), controller.pending)
        controller.runOnce()
        controller.runOnce()
        assertEquals(Connected, controller.state.value)
        assertEquals(listOf("verify", "status", "status", "verify"), gateway.calls)
        assertEquals(0, gateway.forgets)
    }

    @Test fun forbiddenBurstFromManyThreadsGivesASingleStatusCheck() = runBlocking {
        val gateway = FakeGateway(PairingEvent.Status("active"))
        val controller = connected(gateway)
        val threads = (1..8).map { Thread { repeat(5) { controller.onCallResponse(403) } } }
        threads.forEach { it.start() }
        threads.forEach { it.join() }
        controller.runOnce()  // The queued 403 -> RECHECK.
        assertEquals(PairingStep(Connected, RECHECK), controller.pending)
        controller.runOnce()  // The status check.
        controller.runOnce()  // Nothing else is queued.
        assertEquals(listOf("status"), gateway.calls)
        assertEquals(Connected, controller.state.value)

        gateway.add(PairingEvent.Status("blocked"))
        controller.onCallResponse(403)  // Accepted again once the previous check finished.
        controller.runOnce()
        controller.runOnce()
        assertEquals(Blocked, controller.state.value)
    }

    @Test fun goneCallRegistersAgainOnlyOnce() = runBlocking {
        val gateway = FakeGateway(PairingEvent.Registered("111222", 5, 5))
        val controller = connected(gateway)
        controller.onCallResponse(410)
        controller.onCallResponse(410)
        controller.runOnce()
        assertEquals(Registering(Reason.REMOVED), controller.state.value)
        controller.runOnce()  // The second 410 arrives after the state changed: ignored.
        assertEquals(1, gateway.forgets)
        controller.runOnce()
        assertEquals(Pending("111222", 5), controller.state.value)
        assertEquals(listOf("register(false)"), gateway.calls)
    }

    @Test fun otherCallStatusesAreIgnored() = runBlocking {
        val gateway = FakeGateway()
        val controller = connected(gateway)
        for (code in listOf(200, 401, 404, 500)) controller.onCallResponse(code)
        controller.runOnce()
        assertEquals(PairingStep(Connected, NONE), controller.pending)
        assertTrue(gateway.calls.isEmpty())
    }

    @Test fun gatewayErrorsBecomeStatesNotCrashes() = runBlocking {
        val gateway = FakeGateway(ConnectException("refused"),
            SSLHandshakeException("handshake").apply { initCause(CertificateExpiredException("NotAfter 2027")) })
        val controller = PairingController(gateway, PairingMachine.start(true, false, false, null))
        controller.runOnce()
        assertEquals(PairingStep(PairingState.Offline(5, 1, REGISTER, Registering()), REGISTER, 5), controller.pending)
        controller.runOnce()
        assertEquals(PairingState.ClockMismatch(REGISTER), controller.state.value)
    }

    @Test fun retryAfterIsLimitedTo300Seconds() = runBlocking {
        val gateway = FakeGateway(HttpError(429, 3600))
        val controller = PairingController(gateway, PairingMachine.start(true, false, false, null))
        controller.runOnce()
        assertEquals(300, controller.pending.delaySeconds)
    }

    @Test fun cancellationIsNotTurnedIntoAnError() = runBlocking {
        val controller = PairingController(FakeGateway(CancellationException("stop")), PairingMachine.start(true, false, false, null))
        try {
            controller.runOnce()
            fail("Se esperaba CancellationException")
        } catch (_: CancellationException) {
        }
        assertEquals(Registering(), controller.state.value)
    }

    @Test fun runWakesUpOnAnEventInsteadOfWaitingTheFullDelay() = runBlocking {
        val gateway = FakeGateway(PairingEvent.Status("active"), PairingEvent.Verified)
        gateway.hasRequest = true
        val controller = PairingController(gateway, PairingStep(Blocked, RECHECK, 30))
        val loop = launch { controller.run() }
        delay(50)  // run() is now waiting out the 30 s recheck delay.
        assertTrue(gateway.calls.isEmpty())
        controller.onEvent(PairingEvent.UserRetry)
        withTimeout(5_000) { controller.state.first { it == Connected } }
        loop.cancel()
        assertEquals(listOf("status", "verify"), gateway.calls)
    }

    @Test fun forbiddenCallOfAConnectedDeviceEndsAsUnavailable() = runBlocking {
        val gateway = FakeGateway(PairingEvent.Status("active"))
        val controller = connected(gateway)
        assertEquals(CallCheck.IDLE, controller.callCheck.value)
        controller.onCallResponse(403)
        assertEquals(CallCheck.CHECKING, controller.callCheck.value)  // Before the caller shows anything.
        controller.runOnce()  // Queued 403 -> RECHECK.
        assertEquals(CallCheck.CHECKING, controller.callCheck.value)
        controller.runOnce()  // /pairing/status says active.
        assertEquals(CallCheck.UNAVAILABLE, controller.callCheck.value)
        assertEquals(Connected, controller.state.value)
    }

    @Test fun forbiddenCallOfABlockedDeviceIsLeftToTheCard() = runBlocking {
        val gateway = FakeGateway(PairingEvent.Status("blocked"))
        val controller = connected(gateway)
        controller.onCallResponse(403)
        controller.runOnce()
        controller.runOnce()
        assertEquals(CallCheck.RESOLVED, controller.callCheck.value)
        assertEquals(Blocked, controller.state.value)
    }

    @Test fun forbiddenCallCheckThatCannotReachTheServerIsLeftToTheCard() = runBlocking {
        val gateway = FakeGateway(ConnectException("refused"))
        val controller = connected(gateway)
        controller.onCallResponse(403)
        controller.runOnce()
        controller.runOnce()
        assertEquals(CallCheck.RESOLVED, controller.callCheck.value)
        assertEquals(PairingState.Offline(5, 1, RECHECK, Connected), controller.state.value)
    }

    @Test fun periodicBlockedRechecksDoNotTouchTheCallCheck() = runBlocking {
        val gateway = FakeGateway(PairingEvent.Status("blocked"))
        gateway.hasRequest = true
        val controller = PairingController(gateway, PairingStep(Blocked, RECHECK, 30))
        controller.runOnce()
        assertEquals(CallCheck.IDLE, controller.callCheck.value)
    }

    @Test fun forbiddenCallWhenNotConnectedIsResolvedAtOnce() = runBlocking {
        val gateway = FakeGateway()
        val controller = PairingController(gateway, PairingStep(Blocked, NONE))
        controller.onCallResponse(403)
        assertEquals(CallCheck.RESOLVED, controller.callCheck.value)
        controller.runOnce()
        assertTrue(gateway.calls.isEmpty())
    }

    @Test fun pendingCallCheckReplacedByAnotherEventIsSettled() = runBlocking {
        val gateway = FakeGateway(PairingEvent.Registered("111222", 5, 5))
        val controller = connected(gateway)
        controller.onCallResponse(403)
        controller.onCallResponse(410)
        controller.runOnce()  // 403 -> RECHECK pending, CHECKING.
        assertEquals(CallCheck.CHECKING, controller.callCheck.value)
        controller.runOnce()  // 410 replaces the check: registering again, so the card explains it.
        assertEquals(CallCheck.RESOLVED, controller.callCheck.value)
        assertEquals(Registering(Reason.REMOVED), controller.state.value)
        assertTrue(gateway.calls.isEmpty())
    }
}
