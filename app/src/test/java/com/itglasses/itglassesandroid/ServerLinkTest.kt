package com.itglasses.itglassesandroid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Calendar
import java.util.TimeZone

/** QR pairing (2026-11-26): one APK learns the server's origin and certificate pin from the web panel. */
class ServerLinkTest {
    private val pin = "Fx6bj0hfLp3uFe5wnmTFYpL6aq0lmqy1MJXdquVSQvk"
    private fun link(origin: String = "https%3A%2F%2F172.16.5.142%3A8443", p: String = pin, extra: String = "") =
        "itglasses://emparejar?v=1&o=$origin&p=$p$extra"

    @Test fun aLinkFromTheWebPanelIsRead() {
        val read = ServerLink.parse(link())!!
        assertEquals("https://172.16.5.142:8443", read.origin)
        assertEquals(pin, read.pin)
        assertEquals("172.16.5.142", read.host)
        assertEquals("https://itglasses.jatco.local",
            ServerLink.parse(link("https%3A%2F%2Fitglasses.jatco.local"))!!.origin)
        assertEquals("https://172.16.5.142:8443", ServerLink.parse("  " + link() + "\n")!!.origin)
    }

    @Test fun anythingElseIsRefused() {
        for (text in listOf(null, "", "https://172.16.5.142:8443", "hola",
                link().replace("v=1", "v=2"),                                        // unknown version
                link("http%3A%2F%2F172.16.5.142%3A8443"),                            // not HTTPS
                link("https%3A%2F%2Fx.com%2Fruta"),                                  // a path
                link("https%3A%2F%2Fuser%40x.com"),                                  // user info
                link("https%3A%2F%2FX.com"),                                         // not canonical
                link("https%3A%2F%2Fx.com%3A99999"),                                 // bad port
                link(p = "corto"), link(p = pin + "A"), link(p = pin.dropLast(1) + "="),
                link(extra = "&o=https%3A%2F%2Fotro.com"),                           // repeated field
                link(extra = "&x=1"),                                                // unknown field
                link().replace("itglasses://emparejar?", "itglasses://otra?"),
                link(extra = "&p2=" + "a".repeat(400)))) {
            assertNull(text, ServerLink.parse(text))
        }
    }

    @Test fun theSameServerIsNotChangedAndAnotherOneAsksFirst() {
        val current = ServerLink("https://172.16.5.142:8443", pin)
        assertEquals(ServerChange.SAME, ServerChoice.change(ServerLink.parse(link()), current))
        assertEquals(ServerChange.NEW, ServerChoice.change(ServerLink.parse(link()), null))
        assertEquals(ServerChange.NEW, ServerChoice.change(ServerLink("https://10.0.0.5", pin), current))
        assertEquals(ServerChange.INVALID, ServerChoice.change(null, current))
    }

    // --- certificate pin -------------------------------------------------------------------------------------

    private fun certificate(pem: String): X509Certificate =
        CertificateFactory.getInstance("X.509").generateCertificate(pem.trimIndent().byteInputStream()) as X509Certificate

    private val ca by lazy { certificate(CA) }
    private val leaf by lazy { certificate(LEAF) }
    private val other by lazy { certificate(OTHER) }
    private val in2030 = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { clear(); set(2030, 0, 1) }.time
    private val in2200 = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { clear(); set(2200, 0, 1) }.time

    @Test fun thePinIsTheSameTheServerComputes() {
        // Expected values computed by backend/app/pairing/link.py (spki_pin) for these same certificates.
        assertEquals("Fx6bj0hfLp3uFe5wnmTFYpL6aq0lmqy1MJXdquVSQvk", CertificatePins.pin(ca))
        assertEquals("6KMh2nYxnP38-MBZVZxurDi5bZ2Htpzs76eescCEXaY", CertificatePins.pin(leaf))
        assertEquals("uIKHHTRdyWi9NmM3p_VE4FYX9u-DQR3p6f1Fnj2kWw4", CertificatePins.pin(other))
    }

    @Test fun thePinnedCaOrServerCertificateIsAccepted() {
        assertTrue(CertificatePins.matches(arrayOf(leaf, ca), CertificatePins.pin(ca), in2030))
        assertTrue(CertificatePins.matches(arrayOf(leaf, ca), CertificatePins.pin(leaf), in2030))
        assertTrue(CertificatePins.matches(arrayOf(leaf), CertificatePins.pin(leaf), in2030))
    }

    @Test fun anotherServerAnExpiredOneOrABrokenChainIsNot() {
        assertFalse(CertificatePins.matches(arrayOf(other), CertificatePins.pin(ca), in2030))       // not pinned
        assertFalse(CertificatePins.matches(arrayOf(leaf, ca), CertificatePins.pin(ca), in2200))    // expired
        assertFalse(CertificatePins.matches(arrayOf(other, ca), CertificatePins.pin(ca), in2030))   // not signed by it
        assertFalse(CertificatePins.matches(emptyArray(), CertificatePins.pin(ca), in2030))
    }

    @Test fun theTrustManagerFallsBackToTheUsualChecks() {
        var usual = 0
        val trust = PinnedTrustManager(CertificatePins.pin(other)) { _, _ -> usual++; throw CertificateException("no") }
        trust.checkServerTrusted(arrayOf(other), "ECDHE_ECDSA")                                   // pinned: accepted
        assertEquals(0, usual)
        try {
            trust.checkServerTrusted(arrayOf(leaf, ca), "ECDHE_ECDSA")
            throw AssertionError("debió rechazarse")
        } catch (_: CertificateException) {
        }
        assertEquals(1, usual)
        try {
            trust.checkServerTrusted(emptyArray(), "ECDHE_ECDSA")
            throw AssertionError("debió rechazarse")
        } catch (_: CertificateException) {
        }
        assertEquals(0, trust.acceptedIssuers.size)
    }

    @Test fun theAppAsksBeforeChangingServerAndTheVoiceUsesThePin() {
        val activity = ModuleFiles.file("src/main/java/com/itglasses/MainActivity.kt").readText()
        assertTrue(activity.contains("ServerChange.NEW -> androidx.appcompat.app.AlertDialog.Builder(this)"))
        assertTrue(activity.contains("ServerTrust.apply(OkHttpClient.Builder(), pairing.origin, if (pairing.configured) pairing.pin else null)"))
        val pipeline = ModuleFiles.file("src/main/java/com/itglasses/itglassesandroid/VoicePipeline.kt").readText()
        assertTrue(pipeline.contains("ServerTrust.apply(OkHttpClient.Builder(), pairing.origin, pairing.pin)"))
        val manifest = ModuleFiles.file("src/main/AndroidManifest.xml").readText()
        assertTrue(manifest.contains("android:scheme=\"itglasses\" android:host=\"emparejar\""))
    }

    private companion object {
        // Test-only certificates (made for this test; their keys were never kept).
        const val CA = """
-----BEGIN CERTIFICATE-----
MIIBlTCCATugAwIBAgIUUkN0nRRAM2mpvmqnp2e4Yn/Ipw8wCgYIKoZIzj0EAwIw
FzEVMBMGA1UEAwwMQ0EgZGUgcHJ1ZWJhMCAXDTI2MTEyNzA1NDEzNVoYDzIxMjYx
MTAzMDU0MTM1WjAXMRUwEwYDVQQDDAxDQSBkZSBwcnVlYmEwWTATBgcqhkjOPQIB
BggqhkjOPQMBBwNCAASHXdqcbXX7jj2g1GsyfFjOvmF/Dgc51qBKhfjvHcD6FR4u
RnaDRDML4A9ROo+usEC4bc6hp+vTtjkNM7OHEIHao2MwYTAdBgNVHQ4EFgQUf6Qb
lAy2cD12kSMH1iurW95uqBAwHwYDVR0jBBgwFoAUf6QblAy2cD12kSMH1iurW95u
qBAwDwYDVR0TAQH/BAUwAwEB/zAOBgNVHQ8BAf8EBAMCAgQwCgYIKoZIzj0EAwID
SAAwRQIgHlf6XLGfatGMh1irte0+Ayaw/vz2673Y2IaN6mPUQ8sCIQD7SX+2x+EV
e459ZNFX1tK5rPZhw1kp3pnXuQ+kt903Fg==
-----END CERTIFICATE-----"""
        const val LEAF = """
-----BEGIN CERTIFICATE-----
MIIBjTCCATKgAwIBAgIUAOJ9K8usxInvbW2Kmd/C+BZ2JK8wCgYIKoZIzj0EAwIw
FzEVMBMGA1UEAwwMQ0EgZGUgcHJ1ZWJhMCAXDTI2MTEyNzA1NDEzNVoYDzIxMjYx
MTAzMDU0MTM1WjATMREwDwYDVQQDDAgxMC4wLjAuNTBZMBMGByqGSM49AgEGCCqG
SM49AwEHA0IABDRpOvq2/zaT1EFkIVCBSJMFpKTlK1caJO+94hrIge2fZImM9LWn
EZL0necbVci7e4cX3uf1AgR6XsgE/VpBMRmjXjBcMA8GA1UdEQQIMAaHBAoAAAUw
CQYDVR0TBAIwADAdBgNVHQ4EFgQUktWZ6Uo1gULFr17/CTIEsiAij1gwHwYDVR0j
BBgwFoAUf6QblAy2cD12kSMH1iurW95uqBAwCgYIKoZIzj0EAwIDSQAwRgIhAPYX
OanwzV0BXZHAhJ4jIv3XLSdaukIA1sxQH6pgovm2AiEA/1S/XDN7RT6zW4sx2hcL
BD++0x+j9iNb8EGT8/i0e1s=
-----END CERTIFICATE-----"""
        const val OTHER = """
-----BEGIN CERTIFICATE-----
MIIBejCCASGgAwIBAgIUcwye8sOMM1YX8CQRNL1CO4gU6m4wCgYIKoZIzj0EAwIw
EjEQMA4GA1UEAwwHT3RyYSBDQTAgFw0yNjExMjcwNTQxMzVaGA8yMTI2MTEwMzA1
NDEzNVowEjEQMA4GA1UEAwwHT3RyYSBDQTBZMBMGByqGSM49AgEGCCqGSM49AwEH
A0IABAKWs6eXQU4p41rxqIufDCBVwFer2S/rtlztg+jDCXnXm/Kcpfq/b6HglgCf
s3yk7bg7ZEUoaO9rycqbKsCkK/ejUzBRMB0GA1UdDgQWBBRdF63OwPdwZClMqjTy
VDlPeCDb+DAfBgNVHSMEGDAWgBRdF63OwPdwZClMqjTyVDlPeCDb+DAPBgNVHRMB
Af8EBTADAQH/MAoGCCqGSM49BAMCA0cAMEQCIEmQBrGwdvGrWoDgjjm+uBbxgaMV
05ZL66ZidBCSFfKcAiBIjoaKjItWHWxerribvDrXFaX8TdaegSWo5DlZ4cqL+g==
-----END CERTIFICATE-----"""
    }
}
