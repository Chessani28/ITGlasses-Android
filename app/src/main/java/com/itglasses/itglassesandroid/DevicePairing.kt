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

internal class PairingError(val status: Int) : IOException("ITGlasses HTTP $status")

internal class DevicePairing(context: Context, val origin: String) {
    private val prefs = context.getSharedPreferences("itglasses_pairing", Context.MODE_PRIVATE)
    private val alias = "itglasses-" + encode(sha(origin))
    private val transport = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .retryOnConnectionFailure(false).build()
    private var access = ""
    private var deadline = 0L
    val id: String get() = prefs.getString("$alias.id", "") ?: ""
    val code: String get() = prefs.getString("$alias.code", "") ?: ""
    val managed: Boolean get() = prefs.getBoolean("$alias.managed", false)
    val configured: Boolean get() = origin.toHttpUrlOrNull()?.let { PairingProtocol.accepts(origin, it) } == true

    private fun sha(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
    private fun encode(bytes: ByteArray) = Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    private fun keys() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    fun hasIdentity(): Boolean = keys().containsAlias(alias)

    private fun save(editor: android.content.SharedPreferences.Editor) {
        if (!editor.commit()) throw IOException("No se pudo guardar la autorización")
    }

    @Synchronized fun start(): String {
        require(configured) { "Falta configurar la dirección HTTPS de la empresa" }
        if (!keys().containsAlias(alias)) {
            val spec = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN)
                .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                .setDigests(KeyProperties.DIGEST_SHA256).build()
            KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore").apply {
                initialize(spec)
            }.generateKeyPair()
        }
        val reply = post("/pairing/requests", mapOf("variant" to BuildConfig.FLAVOR))
        save(prefs.edit().putString("$alias.id", reply.getString("request_id"))
            .putString("$alias.code", reply.getString("user_code")))
        return code
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
            .put("iat",System.currentTimeMillis()/1000).put("jti",UUID.randomUUID().toString())
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

    private fun call(request: Request): JSONObject = transport.newCall(request).execute().use {
        if (!it.isSuccessful) throw PairingError(it.code)
        JSONObject(it.body?.string() ?: throw IOException("Respuesta vacía"))
    }

    private fun post(path: String, claims: Map<String,String>): JSONObject {
        val request = Request.Builder().url("$origin/api/v1$path").post(ByteArray(0).toRequestBody()).build()
        return call(sign(request,claims))
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

    @Synchronized fun poll(): String {
        val state = post("/pairing/status",mapOf("rid" to id)).getString("state")
        if (state == "approved") remember(post("/pairing/claim",mapOf("rid" to id)))
        if (state == "active" || state == "approved") {
            val me = Request.Builder().url("$origin/api/v1/devices/me").build()
            if (call(sign(me,emptyMap(),token())).getString("device_id") != id) throw IOException("Identidad inválida")
            save(prefs.edit().putBoolean("$alias.managed",true).remove("$alias.code"))
            return "active"
        }
        return state
    }

    // Keep managed=true after revocation. A migrated device must never fall back to Bearer.
    @Synchronized fun forgetRequest() {
        access = ""
        deadline = 0
        save(prefs.edit().remove("$alias.id").remove("$alias.code"))
    }

    fun authorize(chain: Interceptor.Chain): okhttp3.Response {
        val request = chain.request()
        if (!managed || id.isEmpty()) throw IOException("Se requiere aprobación")
        val signed = try { sign(request, emptyMap(), token()) }
            catch (e: IOException) { throw e }
            catch (e: Exception) { throw IOException("Clave de instalación no disponible",e) }
        val response = chain.proceed(signed)
        if (response.code == 401) synchronized(this) { deadline = 0 }
        return response
    }
}

