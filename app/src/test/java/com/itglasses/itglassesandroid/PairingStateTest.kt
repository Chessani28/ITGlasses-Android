package com.itglasses.itglassesandroid

import com.itglasses.itglassesandroid.PairingAction.CLAIM
import com.itglasses.itglassesandroid.PairingAction.NONE
import com.itglasses.itglassesandroid.PairingAction.POLL
import com.itglasses.itglassesandroid.PairingAction.RECHECK
import com.itglasses.itglassesandroid.PairingAction.REGISTER
import com.itglasses.itglassesandroid.PairingAction.VERIFY
import com.itglasses.itglassesandroid.PairingMachine.next
import com.itglasses.itglassesandroid.PairingMachine.start
import com.itglasses.itglassesandroid.PairingState.Blocked
import com.itglasses.itglassesandroid.PairingState.Connected
import com.itglasses.itglassesandroid.PairingState.Denied
import com.itglasses.itglassesandroid.PairingState.Failed
import com.itglasses.itglassesandroid.PairingState.Offline
import com.itglasses.itglassesandroid.PairingState.Pending
import com.itglasses.itglassesandroid.PairingState.RateLimited
import com.itglasses.itglassesandroid.PairingState.Reason
import com.itglasses.itglassesandroid.PairingState.Registering
import com.itglasses.itglassesandroid.PairingState.Unconfigured
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class PairingStateTest {
    private val pending = Pending("482913", 1_794_932_400L)

    @Test fun apkWithoutOriginStaysInSharedTokenMode() {
        assertEquals(PairingStep(Unconfigured, NONE), start(false, hasRequest = true, managed = true, pending = pending))
    }

    @Test fun firstStartRegistersWithoutAnyButton() {
        assertEquals(PairingStep(Registering(Reason.FIRST), REGISTER), start(true, false, false, null))
    }

    @Test fun startResumesAnOpenRequestEvenIfManaged() {
        // A deleted device that re-registered keeps managed=true: it must poll, not verify.
        assertEquals(PairingStep(pending, POLL), start(true, true, true, pending))
        assertEquals(PairingStep(Registering(), POLL), start(true, true, false, null))
    }

    @Test fun startVerifiesAnActiveDevice() {
        assertEquals(PairingStep(Connected, VERIFY), start(true, true, true, null))
    }

    @Test fun registrationShowsTheCodeAndPollsAtTheServerInterval() {
        assertEquals(PairingStep(pending, POLL, 5), next(Registering(), PairingEvent.Registered("482913", pending.expiresAt, 5)))
        assertEquals(1, next(Registering(), PairingEvent.Registered("1", 0, 0)).delaySeconds)
        assertEquals(60, next(Registering(), PairingEvent.Registered("1", 0, 600)).delaySeconds)
    }

    @Test fun pendingKeepsPollingAndApprovalEndsConnected() {
        assertEquals(PairingStep(pending, POLL, PairingMachine.POLL_SECONDS), next(pending, PairingEvent.Status("pending")))
        assertEquals(PairingStep(pending, CLAIM), next(pending, PairingEvent.Status("approved")))
        assertEquals(PairingStep(pending, VERIFY), next(pending, PairingEvent.Claimed))
        assertEquals(PairingStep(Connected, NONE), next(pending, PairingEvent.Verified))
    }

    @Test fun claimConflictKeepsPolling() {
        assertEquals(PairingStep(pending, POLL, 5), next(pending, PairingEvent.Http(409, CLAIM)))
    }

    @Test fun expiredRequestRegistersAgainAndForgetsOnlyTheRequest() {
        assertEquals(PairingStep(Registering(Reason.EXPIRED), REGISTER, forgetRequest = true),
            next(pending, PairingEvent.Status("expired")))
    }

    @Test fun deletedDeviceRegistersAgainFromPairingOrNormalCalls() {
        val expected = PairingStep(Registering(Reason.REMOVED), REGISTER, forgetRequest = true)
        assertEquals(expected, next(pending, PairingEvent.Http(410, POLL)))
        assertEquals(expected, next(Connected, PairingEvent.Http(410, VERIFY)))
    }

    @Test fun blockedIsNotExpiredAndKeepsTheRequest() {
        val step = next(pending, PairingEvent.Status("blocked"))
        assertEquals(PairingStep(Blocked, RECHECK, PairingMachine.BLOCKED_SECONDS), step)
        assertFalse(step.forgetRequest)
        assertEquals(PairingStep(Blocked, RECHECK, 30), next(Registering(), PairingEvent.Http(403, REGISTER)))
        assertEquals(PairingStep(Blocked, RECHECK, 30), next(Connected, PairingEvent.Http(403, VERIFY)))
    }

    @Test fun blockedRechecksEvery30SecondsUntilUnblocked() {
        assertEquals(PairingStep(Blocked, RECHECK, 30), next(Blocked, PairingEvent.Status("blocked")))
        assertEquals(PairingStep(Blocked, VERIFY), next(Blocked, PairingEvent.Status("active")))
        assertEquals(PairingStep(Connected, NONE), next(Blocked, PairingEvent.Verified))
    }

    @Test fun checkAgainButtonRechecksAtOnce() {
        assertEquals(PairingStep(Blocked, RECHECK), next(Blocked, PairingEvent.UserRetry))
    }

    @Test fun deniedStopsUntilTheUserAsksAgain() {
        assertEquals(PairingStep(Denied, NONE, forgetRequest = true), next(pending, PairingEvent.Status("denied")))
        assertEquals(PairingStep(Registering(Reason.REQUESTED), REGISTER), next(Denied, PairingEvent.UserRetry))
    }

    @Test fun unknownStateIsAnErrorNotAnExpiry() {
        // The backend never sends "revoked".
        assertEquals(PairingStep(Failed(POLL), NONE), next(pending, PairingEvent.Status("revoked")))
        assertEquals(PairingStep(Failed(POLL), POLL), next(Failed(POLL), PairingEvent.UserRetry))
    }

    @Test fun forbiddenNormalCallRechecksBeforeDeciding() {
        assertEquals(PairingStep(Connected, RECHECK), next(Connected, PairingEvent.ForbiddenCall))
        assertEquals(PairingStep(Connected, NONE), next(Connected, PairingEvent.Status("active")))
        assertEquals(PairingStep(Blocked, RECHECK, 30), next(Connected, PairingEvent.Status("blocked")))
        assertEquals(PairingStep(pending, NONE), next(pending, PairingEvent.ForbiddenCall))
    }

    @Test fun rateLimitHonoursRetryAfterWithinBounds() {
        assertEquals(PairingStep(RateLimited(42, REGISTER, Registering()), REGISTER, 42),
            next(Registering(), PairingEvent.Http(429, REGISTER, 42)))
        assertEquals(30, next(Registering(), PairingEvent.Http(429, REGISTER, null)).delaySeconds)
        assertEquals(300, next(Registering(), PairingEvent.Http(429, REGISTER, 10_000)).delaySeconds)
        assertEquals(1, next(Registering(), PairingEvent.Http(429, REGISTER, 0)).delaySeconds)
        assertEquals(NONE, next(RateLimited(42, REGISTER, Registering()), PairingEvent.UserRetry).action)
    }

    @Test fun networkBackoffGrowsCapsAndRestoresThePendingCode() {
        var state: PairingState = pending
        val waits = (1..6).map { next(state, PairingEvent.NetworkError(POLL)).also { state = it.state }.delaySeconds }
        assertEquals(listOf(5, 10, 20, 40, 60, 60), waits)
        assertEquals(Offline(60, 6, POLL, pending), state)
        assertEquals(PairingStep(pending, POLL, 5), next(state, PairingEvent.Status("pending")))
        assertEquals(PairingStep(state, POLL), next(state, PairingEvent.UserRetry))
    }

    @Test fun serverErrorsBackOffLikeNetworkErrors() {
        assertEquals(PairingStep(Offline(5, 1, POLL, pending), POLL, 5), next(pending, PairingEvent.Http(503, POLL)))
    }

    @Test fun invalidLabelsRetryOnceWithoutThem() {
        val first = next(Registering(), PairingEvent.Http(422, REGISTER))
        assertEquals(PairingStep(Registering(withoutLabels = true), REGISTER), first)
        assertEquals(PairingStep(Failed(REGISTER), NONE), next(first.state, PairingEvent.Http(422, REGISTER)))
    }

    @Test fun certificateAndClockErrorsWaitForTheUser() {
        val cert = next(pending, PairingEvent.CertificateError(POLL))
        assertEquals(PairingStep(PairingState.CertificateRejected(POLL), NONE), cert)
        assertEquals(POLL, next(cert.state, PairingEvent.UserRetry).action)
        val clock = next(Registering(), PairingEvent.ClockError(REGISTER))
        assertEquals(PairingStep(PairingState.ClockMismatch(REGISTER), NONE), clock)
        assertEquals(PairingStep(Registering(), REGISTER), next(clock.state, PairingEvent.UserRetry))
    }
}
