package com.itglasses.itglassesandroid

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ConnectException
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class NetworkAuthenticationTest {

    private var serverSocket: ServerSocket? = null
    private var serverPort: Int = 0
    private val executor = Executors.newCachedThreadPool()

    @Before
    fun setUp() {
        serverSocket = ServerSocket(0)
        serverPort = serverSocket!!.localPort
    }

    @After
    fun tearDown() {
        try {
            serverSocket?.close()
        } catch (_: Exception) {}
        executor.shutdownNow()
    }

    @Test
    fun `requests send Authorization Bearer header when token is configured`() {
        val capturedAuth = AtomicReference<String?>()
        executor.execute {
            try {
                val client = serverSocket?.accept() ?: return@execute
                val reader = BufferedReader(InputStreamReader(client.getInputStream()))
                var line: String? = reader.readLine()
                while (!line.isNullOrEmpty()) {
                    if (line.startsWith("Authorization:", ignoreCase = true)) {
                        capturedAuth.set(line.substringAfter(":").trim())
                    }
                    line = reader.readLine()
                }
                val body = """{"status":"ready","provider":"ollama","model":"qwen3"}"""
                val response = "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${body.length}\r\n\r\n$body"
                client.getOutputStream().write(response.toByteArray())
                client.getOutputStream().flush()
                client.close()
            } catch (_: Exception) {}
        }

        val token = "secret-test-token-12345"
        val client = OkHttpClient.Builder()
            .addInterceptor(AuthInterceptor { token })
            .build()

        val request = Request.Builder()
            .url("http://127.0.0.1:$serverPort/api/v1/status")
            .build()

        client.newCall(request).execute().use { response ->
            assertEquals(200, response.code)
            assertEquals("Bearer secret-test-token-12345", capturedAuth.get())
        }
    }

    @Test
    fun `requests omit Authorization header when token is null or blank`() {
        val capturedAuth = AtomicReference<String?>()
        executor.execute {
            try {
                val client = serverSocket?.accept() ?: return@execute
                val reader = BufferedReader(InputStreamReader(client.getInputStream()))
                var line: String? = reader.readLine()
                while (!line.isNullOrEmpty()) {
                    if (line.startsWith("Authorization:", ignoreCase = true)) {
                        capturedAuth.set(line.substringAfter(":").trim())
                    }
                    line = reader.readLine()
                }
                val body = """{"status":"ready"}"""
                val response = "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${body.length}\r\n\r\n$body"
                client.getOutputStream().write(response.toByteArray())
                client.getOutputStream().flush()
                client.close()
            } catch (_: Exception) {}
        }

        val client = OkHttpClient.Builder()
            .addInterceptor(AuthInterceptor { null })
            .build()

        val request = Request.Builder()
            .url("http://127.0.0.1:$serverPort/api/v1/status")
            .build()

        client.newCall(request).execute().use { response ->
            assertEquals(200, response.code)
            assertNull(capturedAuth.get())
        }
    }

    @Test
    fun `HTTP 401 response is returned when credentials are rejected`() {
        executor.execute {
            try {
                val client = serverSocket?.accept() ?: return@execute
                val reader = BufferedReader(InputStreamReader(client.getInputStream()))
                var line: String? = reader.readLine()
                while (!line.isNullOrEmpty()) {
                    line = reader.readLine()
                }
                val body = """{"detail":"invalid_credentials"}"""
                val response = "HTTP/1.1 401 Unauthorized\r\nContent-Type: application/json\r\nContent-Length: ${body.length}\r\n\r\n$body"
                client.getOutputStream().write(response.toByteArray())
                client.getOutputStream().flush()
                client.close()
            } catch (_: Exception) {}
        }

        val client = OkHttpClient.Builder()
            .addInterceptor(AuthInterceptor { "invalid-token" })
            .build()

        val body = """{"message":"test"}""".toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url("http://127.0.0.1:$serverPort/api/v1/chat")
            .post(body)
            .build()

        client.newCall(request).execute().use { response ->
            assertEquals(401, response.code)
        }
    }

    @Test
    fun `connection timeout is enforced when server does not respond`() {
        executor.execute {
            try {
                val client = serverSocket?.accept() ?: return@execute
                Thread.sleep(600)
                client.close()
            } catch (_: Exception) {}
        }

        val client = OkHttpClient.Builder()
            .readTimeout(100, TimeUnit.MILLISECONDS)
            .build()

        val request = Request.Builder()
            .url("http://127.0.0.1:$serverPort/api/v1/slow")
            .build()

        var timedOut = false
        try {
            client.newCall(request).execute().use { }
        } catch (_: SocketTimeoutException) {
            timedOut = true
        }
        assertTrue("Expected SocketTimeoutException", timedOut)
    }

    @Test
    fun `backend unreachable throws ConnectException on unused port`() {
        serverSocket?.close()
        val deadPort = serverPort

        val client = OkHttpClient.Builder()
            .connectTimeout(300, TimeUnit.MILLISECONDS)
            .build()

        val request = Request.Builder()
            .url("http://127.0.0.1:$deadPort/api/v1/status")
            .build()

        var connectRefused = false
        try {
            client.newCall(request).execute().use { }
        } catch (_: ConnectException) {
            connectRefused = true
        }
        assertTrue("Expected ConnectException", connectRefused)
    }

    @Test
    fun `credentials are never leaked in error messages or exceptions`() {
        val secretToken = "super-secret-auth-token-xyz-987"
        val client = OkHttpClient.Builder()
            .addInterceptor(AuthInterceptor { secretToken })
            .connectTimeout(200, TimeUnit.MILLISECONDS)
            .build()

        val request = Request.Builder()
            .url("http://127.0.0.1:59999/api/v1/chat")
            .build()

        try {
            client.newCall(request).execute().use { }
        } catch (e: Exception) {
            val message = e.message ?: ""
            val stringRep = e.toString()
            assertFalse("Exception message must not leak secret token", message.contains(secretToken))
            assertFalse("Exception toString must not leak secret token", stringRep.contains(secretToken))
        }
    }
}
