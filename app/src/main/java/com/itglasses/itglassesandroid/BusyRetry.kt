package com.itglasses.itglassesandroid

import okhttp3.Interceptor
import okhttp3.Response

/**
 * Demo lentes (two phones): the backend runs one speech operation at a time and answers 409 "voice_busy" to the
 * other phone instead of waiting. The 409 comes before any work is done, so the same call is retried a few times.
 * Pure rule here; [BusyRetryInterceptor] applies it. Only these calls, only 409 with the voice_busy code.
 */
internal object BusyRetry {
    const val MAX_RETRIES = 3
    const val WAIT_MS = 1500L
    const val BUSY_CODE = "\"voice_busy\""

    val PATHS = setOf(
        "/api/v1/voice/transcribe",
        "/api/v1/voice/synthesize",
        "/api/v1/assistant/chat",
        "/api/v1/chat",
    )

    /** [attempt] retries already made for this call. */
    fun shouldRetry(path: String, status: Int, body: String, attempt: Int): Boolean =
        attempt < MAX_RETRIES && status == 409 && path in PATHS && body.contains(BUSY_CODE)
}

/**
 * Outermost OkHttp interceptor: every retry goes again through the pairing interceptor, so each attempt carries a
 * fresh signed proof. Request bodies here are byte arrays and can be sent again.
 */
internal class BusyRetryInterceptor(
    private val sleep: (Long) -> Unit = { Thread.sleep(it) },
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        var attempt = 0
        while (true) {
            val response = chain.proceed(request)
            val body = if (response.code == 409) response.peekBody(BODY_PEEK).string() else ""
            if (!BusyRetry.shouldRetry(request.url.encodedPath, response.code, body, attempt)) return response
            response.close()
            attempt++
            sleep(BusyRetry.WAIT_MS)
        }
    }

    private companion object {
        const val BODY_PEEK = 4096L
    }
}
