package com.itglasses.itglassesandroid

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.Signature

class PairingProtocolTest {
    @Test fun onlyExactHttpsOriginIsAllowed() {
        val origin = "https://pilot.example"
        assertTrue(PairingProtocol.accepts(origin, "$origin/api/v1/chat".toHttpUrl()))
        for (url in listOf("http://pilot.example/a", "$origin:8443/a", "https://pilot.example.evil/a", "https://other.test/a")) {
            assertFalse(PairingProtocol.accepts(origin, url.toHttpUrl()))
        }
    }
    @Test fun ecdsaProofHasFixedWidthCoordinates() {
        val key = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
        repeat(64) {
            val signature = Signature.getInstance("SHA256withECDSA").apply {
                initSign(key.private)
                update(byteArrayOf(it.toByte()))
            }.sign()
            val raw = PairingProtocol.derToJose(signature)
            assertEquals(64, raw.size)
            assertTrue(Signature.getInstance("SHA256withECDSAinP1363Format").run {
                initVerify(key.public)
                update(byteArrayOf(it.toByte()))
                verify(raw)
            })
        }
    }
}

