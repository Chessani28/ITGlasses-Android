package com.itglasses.itglassesandroid

import android.content.Context
import android.os.SystemClock
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.util.UUID

/** clockAdjusted: this same response corrected the clock, so a 401 is worth one retry. */
internal class PairingError(
    override val status: Int,
    override val retryAfter: Int? = null,
    val clockAdjusted: Boolean = false,
) : IOException("ITGlasses HTTP $status"), HttpFailure

/** Reply to /pairing/requests: active (this key is already approved) or a pending code. */
internal data class Registration(val active: Boolean, val code: String, val expiresAt: Long, val pollInterval: Int)

internal class DevicePairing(context: Context, val origin: String, val pin: String? = null) {
    private val prefs = context.getSharedPreferences("itglasses_pairing", Context.MODE_PRIVATE)
    private val alias = "itglasses-" + encode(sha(origin))
    private val transport = ServerTrust.apply(OkHttpClient.Builder(), origin, pin).followRedirects(false).followSslRedirects(false)
        .retryOnConnectionFailure(false).build()
    private var access = ""
    private var deadline = 0L
    /** Server minus phone clock, from the Date header of every response from the origin. */
    val clock = ClockOffset()
    /** The last Date header was more than 24 h away from the phone clock. */
    @Volatile var clockImplausible = false
        private set
    val id: String get() = prefs.getString("$alias.id", "") ?: ""
    val code: String get() = prefs.getString("$alias.code", "") ?: ""
    val expires: Long get() = prefs.getLong("$alias.expires", 0L)
    val managed: Boolean get() = prefs.getBoolean("$alias.managed", false)
    val configured: Boolean get() = origin.toHttpUrlOrNull()?.let { PairingProtocol.accepts(origin, it) } == true

    private fun sha(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
    private fun encode(bytes: ByteArray) = Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    private fun keys() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    fun hasIdentity(): Boolean = keys().containsAlias(alias)

    private fun save(editor: android.content.SharedPreferences.Editor) {
        if (!editor.commit()) throw IOException("No se pudo guardar la autorización")
    }

    /** name and model are optional; withoutLabels sends neither (retry after a 422). */
    @Synchronized fun register(name: String?, model: String?, withoutLabels: Boolean): Registration {
        require(configured) { "Falta configurar la dirección HTTPS de la empresa" }
        if (!keys().containsAlias(alias)) {
            val spec = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN)
                .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                .setDigests(KeyProperties.DIGEST_SHA256).build()
            KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore").apply {
                initialize(spec)
            }.generateKeyPair()
        }
        val claims = mutableMapOf("variant" to BuildConfig.FLAVOR)
        if (!withoutLabels) {
            name?.let { claims["name"] = it }
            model?.let { claims["model"] = it }
        }
        val reply = post("/pairing/requests", claims)
        val rid = reply.getString("request_id")
        if (reply.optString("state") == "active") {
            save(prefs.edit().putString("$alias.id", rid).remove("$alias.code").remove("$alias.expires"))
            return Registration(true, "", 0L, PairingMachine.POLL_SECONDS)
        }
        val registration = Registration(false, reply.getString("user_code"), reply.getLong("expires_at"),
            reply.optInt("poll_interval", PairingMachine.POLL_SECONDS))
        save(prefs.edit().putString("$alias.id", rid).putString("$alias.code", registration.code)
            .putLong("$alias.expires", registration.expiresAt))
        return registration
    }

    private fun coordinate(number: BigInteger): String {
        val bytes = number.toByteArray()
        val count = minOf(32, bytes.size)
        val result = ByteArray(32)
        bytes.copyInto(result, 32-count, bytes.size-count)
        return encode(result)
    }

    private fun sign(request: Request, claims: Map<String,String>, token: String = ""): Request {
        if (!PairingProtocol.accepts(origin, request.url)) throw IOException("Origen HTTPS inválido")
        val store = keys()
        val key = store.getCertificate(alias)?.publicKey as? ECPublicKey ?: throw IOException("Falta la clave de instalación")
        val jwk = JSONObject().put("kty","EC").put("crv","P-256")
            .put("x",coordinate(key.w.affineX)).put("y",coordinate(key.w.affineY))
        val header = JSONObject().put("typ","dpop+jwt").put("alg","ES256").put("jwk",jwk)
        val data = JSONObject().put("htm",request.method).put("htu",request.url.toString())
            .put("iat",clock.serverNow(System.currentTimeMillis())).put("jti",UUID.randomUUID().toString())
        for ((name,value) in claims) data.put(name,value)
        val builder = request.newBuilder()
        if (token.isNotEmpty()) {
            data.put("ath",encode(sha(token)))
            builder.header("Authorization","DPoP $token")
        }
        val input = encode(header.toString().toByteArray()) + "." + encode(data.toString().toByteArray())
        val sig = Signature.getInstance("SHA256withECDSA").run {
            initSign(store.getKey(alias,null) as PrivateKey)
            update(input.toByteArray())
            sign()
        }
        return builder.header("DPoP", input + "." + encode(PairingProtocol.derToJose(sig))).build()
    }

    private fun observe(response: okhttp3.Response): ClockOffset.Observation {
        val result = clock.observe(response.header("Date"), System.currentTimeMillis())
        if (result != ClockOffset.Observation.MISSING) clockImplausible = result == ClockOffset.Observation.IMPLAUSIBLE
        return result
    }

    private fun call(request: Request): JSONObject = transport.newCall(request).execute().use {
        val observation = observe(it)
        if (!it.isSuccessful) throw PairingError(it.code, PairingErrors.retryAfter(it.header("Retry-After")),
            clockAdjusted = observation == ClockOffset.Observation.ADJUSTED)
        JSONObject(it.body?.string() ?: throw IOException("Respuesta vacía"))
    }

    /** A 401 right after the clock was corrected is retried once with a fresh proof (new jti, corrected iat). */
    private fun signed(request: Request, claims: Map<String,String>, token: String = ""): JSONObject {
        try {
            return call(sign(request, claims, token))
        } catch (e: PairingError) {
            if (e.status != 401 || !e.clockAdjusted) throw e
        }
        return call(sign(request, claims, token))
    }

    private fun post(path: String, claims: Map<String,String>): JSONObject {
        val request = Request.Builder().url("$origin/api/v1$path").post(ByteArray(0).toRequestBody()).build()
        return signed(request, claims)
    }

    private fun remember(reply: JSONObject) {
        access = reply.getString("access_token")
        deadline = SystemClock.elapsedRealtime() + (reply.getLong("expires_in")-30)*1000
    }

    @Synchronized private fun token(): String {
        if (access.isEmpty() || SystemClock.elapsedRealtime() >= deadline) {
            val nonce = post("/devices/challenge", mapOf("rid" to id)).getString("nonce")
            remember(post("/devices/token",mapOf("rid" to id,"nonce" to nonce)))
        }
        return access
    }

    /** The "state" field of /pairing/status, as the server sends it. */
    @Synchronized fun status(): String = post("/pairing/status",mapOf("rid" to id)).getString("state")

    /** Only after status() said "approved"; keeps the access token for verify(). */
    @Synchronized fun claim() {
        remember(post("/pairing/claim",mapOf("rid" to id)))
    }

    /** Gets a token if needed and checks it with /devices/me; then the device is managed. */
    @Synchronized fun verify() {
        val me = Request.Builder().url("$origin/api/v1/devices/me").build()
        if (signed(me,emptyMap(),token()).getString("device_id") != id) throw IOException("Identidad inválida")
        save(prefs.edit().putBoolean("$alias.managed",true).remove("$alias.code").remove("$alias.expires"))
    }

    // Drops the request id, code and expiry only. Keeps the installation key, managed=true (a migrated device
    // must never fall back to Bearer) and the stored shared token (BackendConfiguration, untouched here).
    @Synchronized fun forgetRequest() {
        access = ""
        deadline = 0
        save(prefs.edit().remove("$alias.id").remove("$alias.code").remove("$alias.expires"))
    }

    fun authorize(chain: Interceptor.Chain): okhttp3.Response {
        val request = chain.request()
        if (!managed || id.isEmpty()) throw IOException("Se requiere aprobación")
        val signed = try { sign(request, emptyMap(), token()) }
            catch (e: IOException) { throw e }
            catch (e: Exception) { throw IOException("Clave de instalación no disponible",e) }
        val response = chain.proceed(signed)
        observe(response)
        if (response.code == 401) synchronized(this) { deadline = 0 }
        return response
    }
}

