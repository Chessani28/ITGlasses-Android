package com.itglasses.itglassesandroid

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

internal object PairingProtocol {
    fun accepts(origin: String, url: HttpUrl): Boolean {
        val base = origin.toHttpUrlOrNull() ?: return false
        return base.isHttps && url.isHttps && base.host == url.host && base.port == url.port &&
            base.encodedPath == "/" && base.query == null && base.fragment == null &&
            base.username.isEmpty() && base.password.isEmpty() &&
            url.username.isEmpty() && url.password.isEmpty() && url.query == null && url.fragment == null
    }

    // Android Keystore returns a DER sequence containing two signed integers.
    fun derToJose(der: ByteArray): ByteArray {
        var pos = 0
        fun next() = der[pos++].toInt() and 255
        require(next() == 0x30 && next() == der.size - 2)
        val raw = ByteArray(64)
        for (offset in listOf(0, 32)) {
            require(next() == 0x02)
            val size = next()
            require(size in 1..33 && pos + size <= der.size)
            val start = if (der[pos].toInt() == 0 && size > 1) pos + 1 else pos
            val end = pos + size
            require(end - start <= 32)
            der.copyInto(raw, offset + 32 - (end - start), start, end)
            pos = end
        }
        require(pos == der.size)
        return raw
    }
}

