package com.itglasses.itglassesandroid

import android.content.Context
import java.net.URI
import java.net.URISyntaxException

internal object BackendConfiguration {

    private const val PREFS_NAME = "itglasses_network_config"
    private const val KEY_CUSTOM_URL = "custom_backend_url"
    private const val KEY_API_TOKEN = "api_bearer_token"

    internal fun isLoopback(host: String): Boolean {
        if (host.equals("localhost", ignoreCase = true)) return true
        if (host == "::1" || host == "[::1]") return true
        val parts = host.split('.')
        if (parts.size == 4) {
            val octets = parts.mapNotNull { it.toIntOrNull() }
            if (octets.size == 4 && octets.all { it in 0..255 }) {
                return octets[0] == 127
            }
        }
        return false
    }

    internal fun isPrivateIPv4(host: String): Boolean {
        val parts = host.split('.')
        if (parts.size != 4) return false
        val octets = parts.mapNotNull { it.toIntOrNull() }
        if (octets.size != 4 || !octets.all { it in 0..255 }) return false

        val (a, b, c, d) = octets
        // 10.0.0.0/8
        if (a == 10) return true
        // 172.16.0.0/12 (172.16.0.0 to 172.31.255.255)
        if (a == 172 && b in 16..31) return true
        // 192.168.0.0/16
        if (a == 192 && b == 168) return true
        // 169.254.0.0/16 (Link-Local)
        if (a == 169 && b == 254) return true

        return false
    }

    internal fun isPrivateLocalHostname(host: String): Boolean {
        val lower = host.lowercase()
        return lower.endsWith(".local") || lower.endsWith(".internal") || lower.endsWith(".test")
    }

    internal fun isAllowedDebugHost(host: String): Boolean {
        return isLoopback(host) || isPrivateIPv4(host) || isPrivateLocalHostname(host)
    }

    fun normalizeCandidateUrl(raw: String, debug: Boolean): String {
        var trimmed = raw.trim()
        if (trimmed.isEmpty()) return ""
        if (!trimmed.startsWith("http://", ignoreCase = true) &&
            !trimmed.startsWith("https://", ignoreCase = true)
        ) {
            trimmed = if (debug) "http://$trimmed" else "https://$trimmed"
        }
        return trimmed
    }

    fun select(debug: Boolean, debugUrl: String, productionUrl: String): String? {
        val raw = if (debug) debugUrl else productionUrl
        val configuredUrl = normalizeCandidateUrl(raw, debug)
        if (configuredUrl.isEmpty()) return null

        val uri = try {
            URI(configuredUrl)
        } catch (_: URISyntaxException) {
            return null
        }

        if (!uri.isAbsolute || uri.host.isNullOrBlank() || uri.rawUserInfo != null) return null
        if (uri.rawQuery != null || uri.rawFragment != null) return null

        val host = uri.host ?: return null
        if (debug) {
            if (uri.scheme !in setOf("http", "https")) return null
            if (uri.scheme == "http" && !isAllowedDebugHost(host)) return null
        } else if (uri.scheme != "https") {
            return null
        }

        return configuredUrl.trimEnd('/')
    }

    val baseUrl: String?
        get() = select(
            debug = BuildConfig.DEBUG,
            debugUrl = BuildConfig.BACKEND_DEBUG_URL,
            productionUrl = BuildConfig.BACKEND_PRODUCTION_URL,
        )

    fun hasCustomUrl(context: Context): Boolean {
        if (!BuildConfig.DEBUG) return false
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return !prefs.getString(KEY_CUSTOM_URL, null).isNullOrBlank()
    }

    fun getBaseUrl(context: Context): String? {
        if (!BuildConfig.DEBUG) {
            return baseUrl
        }
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val customUrl = prefs.getString(KEY_CUSTOM_URL, null)?.trim()
        if (!customUrl.isNullOrEmpty()) {
            val validated = select(debug = true, debugUrl = customUrl, productionUrl = "")
            if (validated != null) return validated
        }
        return baseUrl
    }

    fun setCustomUrl(context: Context, url: String?): Boolean {
        if (!BuildConfig.DEBUG) return false
        val trimmed = url?.trim() ?: ""
        if (trimmed.isEmpty()) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().remove(KEY_CUSTOM_URL).apply()
            return true
        }
        val validated = select(debug = true, debugUrl = trimmed, productionUrl = "") ?: return false
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_CUSTOM_URL, validated).apply()
        return true
    }

    fun getStoredToken(context: Context): String? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_API_TOKEN, null)?.trim()?.takeIf { it.isNotEmpty() }
    }

    fun saveToken(context: Context, token: String?) {
        val trimmed = token?.trim()
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
        if (trimmed.isNullOrEmpty()) {
            prefs.remove(KEY_API_TOKEN)
        } else {
            prefs.putString(KEY_API_TOKEN, trimmed)
        }
        prefs.apply()
    }

    fun clearConfiguration(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().clear().apply()
    }
}
