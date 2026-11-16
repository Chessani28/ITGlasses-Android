package com.itglasses.itglassesandroid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BackendConfigurationTest {

    @Test
    fun `debug build accepts loopback addresses`() {
        assertEquals(
            "http://127.0.0.1:8000",
            BackendConfiguration.select(true, "http://127.0.0.1:8000", "")
        )
        assertEquals(
            "http://localhost:8000",
            BackendConfiguration.select(true, "http://localhost:8000", "")
        )
        assertEquals(
            "http://127.0.1.1:8000",
            BackendConfiguration.select(true, "http://127.0.1.1:8000", "")
        )
        assertEquals(
            "http://[::1]:8000",
            BackendConfiguration.select(true, "http://[::1]:8000", "")
        )
    }

    @Test
    fun `debug build accepts RFC 1918 private IPv4 LAN addresses`() {
        // 10.0.0.0/8
        assertEquals(
            "http://10.0.0.1:8000",
            BackendConfiguration.select(true, "http://10.0.0.1:8000", "")
        )
        assertEquals(
            "http://10.254.1.50:8000",
            BackendConfiguration.select(true, "http://10.254.1.50:8000", "")
        )
        // 172.16.0.0/12 (172.16.0.0 to 172.31.255.255)
        assertEquals(
            "http://172.16.0.1:8000",
            BackendConfiguration.select(true, "http://172.16.0.1:8000", "")
        )
        assertEquals(
            "http://172.24.10.20:8000",
            BackendConfiguration.select(true, "http://172.24.10.20:8000", "")
        )
        assertEquals(
            "http://172.31.255.254:8000",
            BackendConfiguration.select(true, "http://172.31.255.254:8000", "")
        )
        // 192.168.0.0/16
        assertEquals(
            "http://192.168.1.1:8000",
            BackendConfiguration.select(true, "http://192.168.1.1:8000", "")
        )
        assertEquals(
            "http://192.168.100.200:8000",
            BackendConfiguration.select(true, "http://192.168.100.200:8000/", "")
        )
    }

    @Test
    fun `debug build accepts Link-Local and local domain names`() {
        assertEquals(
            "http://169.254.1.2:8000",
            BackendConfiguration.select(true, "http://169.254.1.2:8000", "")
        )
        assertEquals(
            "http://itglasses.local:8000",
            BackendConfiguration.select(true, "http://itglasses.local:8000", "")
        )
        assertEquals(
            "http://server.internal:8000",
            BackendConfiguration.select(true, "http://server.internal:8000", "")
        )
        assertEquals(
            "http://test-server.test:8000",
            BackendConfiguration.select(true, "http://test-server.test:8000", "")
        )
    }

    @Test
    fun `debug build rejects public internet IPv4 addresses`() {
        assertNull(BackendConfiguration.select(true, "http://8.8.8.8:8000", ""))
        assertNull(BackendConfiguration.select(true, "http://1.1.1.1:8000", ""))
        assertNull(BackendConfiguration.select(true, "http://93.184.216.34:8000", ""))
        // 172.32.0.1 is outside the 172.16.0.0/12 private block
        assertNull(BackendConfiguration.select(true, "http://172.32.0.1:8000", ""))
        // 172.15.255.255 is outside the 172.16.0.0/12 private block
        assertNull(BackendConfiguration.select(true, "http://172.15.255.255:8000", ""))
        // 192.169.1.1 is outside 192.168.0.0/16
        assertNull(BackendConfiguration.select(true, "http://192.169.1.1:8000", ""))
    }

    @Test
    fun `debug build rejects public internet domains over http`() {
        assertNull(BackendConfiguration.select(true, "http://example.com:8000", ""))
        assertNull(BackendConfiguration.select(true, "http://google.com", ""))
        assertNull(BackendConfiguration.select(true, "http://api.itglasses.com", ""))
    }

    @Test
    fun `release build uses only configured https production endpoint`() {
        assertEquals(
            "https://api.example.test",
            BackendConfiguration.select(false, "http://127.0.0.1:8000", "https://api.example.test/")
        )
        assertEquals(
            "https://192.168.1.20:8443",
            BackendConfiguration.select(false, "http://127.0.0.1:8000", "https://192.168.1.20:8443")
        )
        assertNull(
            BackendConfiguration.select(false, "http://127.0.0.1:8000", "")
        )
        assertNull(
            BackendConfiguration.select(false, "http://api.example.test", "http://api.example.test")
        )
        assertNull(
            BackendConfiguration.select(false, "http://192.168.1.20:8000", "http://192.168.1.20:8000")
        )
    }

    @Test
    fun `URLs cannot embed credentials query values or fragments`() {
        assertNull(
            BackendConfiguration.select(true, "http://user:token@192.168.1.20:8000", "")
        )
        assertNull(
            BackendConfiguration.select(true, "http://192.168.1.20:8000?token=value", "")
        )
        assertNull(
            BackendConfiguration.select(true, "http://192.168.1.20:8000#section", "")
        )
        assertNull(
            BackendConfiguration.select(true, "ftp://192.168.1.20:8000", "")
        )
        assertNull(
            BackendConfiguration.select(true, "not a URL", "")
        )
    }

    @Test
    fun `host classification helper unit tests`() {
        assertTrue(BackendConfiguration.isLoopback("127.0.0.1"))
        assertTrue(BackendConfiguration.isLoopback("localhost"))
        assertTrue(BackendConfiguration.isLoopback("::1"))
        assertFalse(BackendConfiguration.isLoopback("192.168.1.1"))

        assertTrue(BackendConfiguration.isPrivateIPv4("10.0.0.1"))
        assertTrue(BackendConfiguration.isPrivateIPv4("172.20.0.1"))
        assertTrue(BackendConfiguration.isPrivateIPv4("192.168.1.1"))
        assertTrue(BackendConfiguration.isPrivateIPv4("169.254.1.1"))
        assertFalse(BackendConfiguration.isPrivateIPv4("8.8.8.8"))
        assertFalse(BackendConfiguration.isPrivateIPv4("invalid.ip"))

        assertTrue(BackendConfiguration.isPrivateLocalHostname("itglasses.local"))
        assertTrue(BackendConfiguration.isPrivateLocalHostname("server.internal"))
        assertTrue(BackendConfiguration.isPrivateLocalHostname("test.test"))
        assertFalse(BackendConfiguration.isPrivateLocalHostname("google.com"))

        assertTrue(BackendConfiguration.isAllowedDebugHost("192.168.1.50"))
        assertTrue(BackendConfiguration.isAllowedDebugHost("127.0.0.1"))
        assertFalse(BackendConfiguration.isAllowedDebugHost("8.8.8.8"))
    }
}
