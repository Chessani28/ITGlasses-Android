package com.itglasses.itglassesandroid

import com.itglasses.itglassesandroid.PairingAction.CLAIM
import com.itglasses.itglassesandroid.PairingAction.NONE
import com.itglasses.itglassesandroid.PairingAction.POLL
import com.itglasses.itglassesandroid.PairingAction.RECHECK
import com.itglasses.itglassesandroid.PairingAction.REGISTER
import com.itglasses.itglassesandroid.PairingAction.VERIFY
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean

/** Network side of pairing. Implemented by DevicePairingGateway (tanda 2b); faked in the tests. */
internal interface PairingGateway {
    /** A request id is stored (pending, or an active device). */
    val hasRequest: Boolean
    /** The last Date header was implausible (see ClockOffset.Observation.IMPLAUSIBLE). */
    val clockImplausible: Boolean
    /** Registered, or Status("active") if this key is already approved. */
    suspend fun register(withoutLabels: Boolean): PairingEvent
    /** Status(state) from /pairing/status. */
    suspend fun status(): PairingEvent
    suspend fun claim(): PairingEvent
    suspend fun verify(): PairingEvent
    /** Drops the request id, code and expiry only. */
    suspend fun forgetRequest()
}

/**
 * Runs PairingMachine against a PairingGateway. One consumer (run() or runOnce()) changes the state; the UI only
 * reads [state]. onEvent() and onCallResponse() are safe from any thread (OkHttp's included) and never touch the
 * UI: they only queue work. MainActivity runs run() inside repeatOnLifecycle(STARTED), so nothing is polled
 * while the app is in the background.
 */
internal class PairingController(private val gateway: PairingGateway, initial: PairingStep) {
    private sealed class Inbound {
        class Event(val event: PairingEvent) : Inbound()
        class Call(val code: Int) : Inbound()
    }

    private val inbox = Channel<Inbound>(Channel.UNLIMITED)
    private val mutex = Mutex()
    /** True from the first 403 on a normal call until its status check has finished. */
    private val recheckQueued = AtomicBoolean(false)
    private val mutableState = MutableStateFlow(initial.state)
    @Volatile private var current: PairingStep = initial

    val state: StateFlow<PairingState> = mutableState.asStateFlow()
    /** The step waiting to run (action and delay); for the UI countdown and the tests. */
    val pending: PairingStep get() = current

    /** Buttons and other UI events. Thread-safe. */
    fun onEvent(event: PairingEvent) {
        inbox.trySend(Inbound.Event(event))
    }

    /** HTTP status of a normal call (chat, voice). Thread-safe; 403 bursts give a single status check. */
    fun onCallResponse(code: Int) {
        when (code) {
            403 -> if (recheckQueued.compareAndSet(false, true)) inbox.trySend(Inbound.Call(403))
            410 -> inbox.trySend(Inbound.Call(410))
        }
    }

    /** Loops until cancelled: waits for the step's delay or for an incoming event, whichever comes first. */
    suspend fun run() {
        while (true) {
            val step = current
            val inbound = inbox.tryReceive().getOrNull()
                ?: if (step.action == NONE) inbox.receive()
                else withTimeoutOrNull(step.delaySeconds * 1000L) { inbox.receive() }
            advance(inbound)
        }
    }

    /** One iteration without waiting: a queued event if there is one, otherwise the pending action. */
    suspend fun runOnce() {
        advance(inbox.tryReceive().getOrNull())
    }

    private suspend fun advance(inbound: Inbound?) {
        mutex.withLock {
            when (inbound) {
                null -> perform(current)?.let { apply(it) }
                is Inbound.Event -> apply(inbound.event)
                is Inbound.Call -> call(inbound.code)
            }
        }
    }

    /** Calls only happen while Connected; a late reply after the state changed is ignored. */
    private suspend fun call(code: Int) {
        val connected = current.state == PairingState.Connected
        when {
            code == 403 && connected -> apply(PairingEvent.ForbiddenCall)
            code == 403 -> recheckQueued.set(false)
            code == 410 && connected -> apply(PairingEvent.Http(410, VERIFY))
        }
    }

    private suspend fun perform(step: PairingStep): PairingEvent? {
        val action = step.action
        if (action == NONE) return null
        return try {
            when (action) {
                REGISTER -> gateway.register((step.state as? PairingState.Registering)?.withoutLabels == true)
                POLL -> gateway.status()
                RECHECK -> if (gateway.hasRequest) gateway.status() else gateway.register(false)
                CLAIM -> gateway.claim()
                VERIFY -> gateway.verify()
                NONE -> null
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            PairingErrors.event(e, action, gateway.clockImplausible)
        } finally {
            if (action == RECHECK) recheckQueued.set(false)
        }
    }

    private suspend fun apply(event: PairingEvent) {
        val next = PairingMachine.next(current.state, event)
        if (next.forgetRequest) gateway.forgetRequest()
        current = next
        mutableState.value = next.state
        if (event == PairingEvent.ForbiddenCall && next.action != RECHECK) recheckQueued.set(false)
    }
}
