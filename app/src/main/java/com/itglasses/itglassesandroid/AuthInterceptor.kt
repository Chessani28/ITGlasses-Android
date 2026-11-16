package com.itglasses.itglassesandroid

import okhttp3.Interceptor
import okhttp3.Response

/**
 * Injects the Authorization Bearer token header on outgoing HTTP requests
 * when a non-blank token is provided.
 */
internal class AuthInterceptor(
    private val tokenProvider: () -> String?
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        val token = tokenProvider()?.trim()
        val request = if (!token.isNullOrEmpty()) {
            original.newBuilder()
                .header("Authorization", "Bearer $token")
                .build()
        } else {
            original
        }
        return chain.proceed(request)
    }
}
