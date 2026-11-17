package com.itglasses.itglassesandroid

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * PairingGateway on top of DevicePairing. Every network and KeyStore call runs on Dispatchers.IO.
 * name and model are read at each registration (the user may rename the phone between attempts).
 * Android-only (KeyStore, SharedPreferences): verified on the phone.
 */
internal class DevicePairingGateway(
    private val pairing: DevicePairing,
    private val name: () -> String?,
    private val model: () -> String?,
) : PairingGateway {
    override val hasRequest: Boolean get() = pairing.id.isNotEmpty()
    override val clockImplausible: Boolean get() = pairing.clockImplausible

    override suspend fun register(withoutLabels: Boolean): PairingEvent = withContext(Dispatchers.IO) {
        val registration = pairing.register(name(), model(), withoutLabels)
        if (registration.active) PairingEvent.Status("active")
        else PairingEvent.Registered(registration.code, registration.expiresAt, registration.pollInterval)
    }

    override suspend fun status(): PairingEvent = withContext(Dispatchers.IO) { PairingEvent.Status(pairing.status()) }

    override suspend fun claim(): PairingEvent = withContext(Dispatchers.IO) {
        pairing.claim()
        PairingEvent.Claimed
    }

    override suspend fun verify(): PairingEvent = withContext(Dispatchers.IO) {
        pairing.verify()
        PairingEvent.Verified
    }

    override suspend fun forgetRequest() = withContext(Dispatchers.IO) { pairing.forgetRequest() }
}
