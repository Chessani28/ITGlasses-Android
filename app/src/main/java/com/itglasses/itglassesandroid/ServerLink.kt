package com.itglasses.itglassesandroid

import android.content.Context
import okhttp3.OkHttpClient
import okio.ByteString.Companion.toByteString
import java.net.URLDecoder
import java.security.KeyStore
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.Date
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * QR pairing (2026-11-26): one APK for every customer. The web panel shows
 *     itglasses://emparejar?v=1&o=<https origin>&p=<pin>
 * The pin is the base64url SHA-256 of the public key (SPKI) of a certificate the server presents (its CA or its
 * own certificate). Nothing in it is secret: the administrator still approves the phone with its code.
 */
internal data class ServerLink(val origin: String, val pin: String) {
    val host: String get() = java.net.URI(origin).host

    companion object {
        const val PREFIX = "itglasses://emparejar?"
        private val ORIGIN = Regex("https://[a-z0-9.-]+(:[0-9]+)?")
        private val PIN = Regex("[A-Za-z0-9_-]{43}")

        /** null for anything that is not exactly a version-1 ITGlasses link. */
        fun parse(text: String?): ServerLink? {
            val raw = text?.trim() ?: return null
            if (!raw.startsWith(PREFIX) || raw.length > 400) return null
            val fields = HashMap<String, String>()
            for (part in raw.removePrefix(PREFIX).split('&')) {
                val (name, value) = part.split('=', limit = 2).takeIf { it.size == 2 } ?: return null
                val decoded = try { URLDecoder.decode(value, "UTF-8") } catch (_: IllegalArgumentException) { return null }
                if (fields.put(name, decoded) != null) return null              // a repeated field: refused
            }
            if (fields.keys != setOf("v", "o", "p") || fields["v"] != "1") return null
            val origin = fields.getValue("o")
            val pin = fields.getValue("p")
            if (!ORIGIN.matches(origin) || !PIN.matches(pin)) return null
            val port = origin.substringAfter("//").substringAfter(':', "443").toIntOrNull() ?: return null
            if (port !in 1..65535) return null
            return ServerLink(origin, pin)
        }
    }
}

/** What the phone does with a scanned link, given the server it uses now. */
internal enum class ServerChange { INVALID, SAME, NEW }

internal object ServerChoice {
    fun change(scanned: ServerLink?, current: ServerLink?): ServerChange = when {
        scanned == null -> ServerChange.INVALID
        scanned == current -> ServerChange.SAME
        else -> ServerChange.NEW
    }
}

/** The scanned server, kept out of backups (backup_rules.xml). Without one, the APK's built-in origin is used. */
internal object ServerStore {
    private const val PREFS = "itglasses_server"

    fun get(context: Context): ServerLink? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val origin = prefs.getString("origin", null) ?: return null
        val pin = prefs.getString("pin", null) ?: return null
        return ServerLink.parse("${ServerLink.PREFIX}v=1&o=${java.net.URLEncoder.encode(origin, "UTF-8")}&p=$pin")
    }

    fun save(context: Context, link: ServerLink): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("origin", link.origin).putString("pin", link.pin).commit()

    /** Origin for pairing: the scanned one, else BuildConfig.PAIRING_ORIGIN (the demo APK, as before). */
    fun origin(context: Context): String = get(context)?.origin ?: BuildConfig.PAIRING_ORIGIN

    /** Pin for that origin: only a scanned server has one. */
    fun pin(context: Context): String? = get(context)?.pin
}

internal object CertificatePins {
    fun pin(certificate: X509Certificate): String =
        certificate.publicKey.encoded.toByteString().sha256().base64Url().trimEnd('=')

    /**
     * chain: as the server presented it, its own certificate first. True when one certificate carries the pin
     * and every certificate up to it is valid now and signed by the next one.
     */
    fun matches(chain: Array<out X509Certificate>, pin: String, now: Date = Date()): Boolean {
        val anchor = chain.indexOfFirst { pin(it) == pin }
        if (anchor < 0) return false
        return try {
            for (i in 0..anchor) chain[i].checkValidity(now)
            for (i in 0 until anchor) chain[i].verify(chain[i + 1].publicKey)
            true
        } catch (_: Exception) {
            false
        }
    }
}

/**
 * Accepts the pinned server; anything else must pass the phone's usual checks (system CAs and the network
 * security config) through [usual]. The host name is still checked by OkHttp.
 */
internal class PinnedTrustManager(
    private val pin: String,
    private val usual: (Array<out X509Certificate>, String) -> Unit,
) : X509TrustManager {
    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        if (chain.isNullOrEmpty()) throw CertificateException("Sin certificado")
        if (CertificatePins.matches(chain, pin)) return
        usual(chain, authType ?: "")
    }

    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) =
        throw CertificateException("No se aceptan clientes")

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}

internal object ServerTrust {
    /** Without a pin the builder is left as it was (the demo APK trusts its pilot CA as before). */
    fun apply(builder: OkHttpClient.Builder, origin: String, pin: String?): OkHttpClient.Builder {
        if (pin == null) return builder
        val host = java.net.URI(origin).host
        val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        factory.init(null as KeyStore?)
        val system = factory.trustManagers.filterIsInstance<X509TrustManager>().first()
        val extensions = android.net.http.X509TrustManagerExtensions(system)
        val trust = PinnedTrustManager(pin) { chain, authType ->
            @Suppress("UNCHECKED_CAST")
            extensions.checkServerTrusted(chain as Array<X509Certificate>, authType, host)
        }
        val context = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trust), null) }
        return builder.sslSocketFactory(context.socketFactory, trust)
    }
}
