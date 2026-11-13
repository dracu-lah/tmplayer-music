package com.tmplayer.music.connect

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * The few primitives Connect rests on, all from the JDK and Android's own providers (no library):
 * HMAC-SHA256, a password stretch (PBKDF2 over that HMAC, written out because Android 6 to 7 have
 * no PBKDF2WithHmacSHA256), random bytes and a comparison that does not stop at the first
 * difference.
 */
object ConnectCrypto {
    private val random = SecureRandom()

    fun randomBytes(count: Int): ByteArray = ByteArray(count).also(random::nextBytes)

    fun randomHex(bytes: Int): String = hex(randomBytes(bytes))

    /** A uniformly random number below [bound], for the pairing code. */
    fun randomBelow(bound: Int): Int = random.nextInt(bound)

    /** HMAC-SHA256 of [parts] joined with a bar, so two different part lists never make the same text. */
    fun hmac(key: ByteArray, vararg parts: String): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec((if (key.isEmpty()) ByteArray(1) else key), "HmacSHA256"))
        return mac.doFinal(parts.joinToString("|").toByteArray(Charsets.UTF_8))
    }

    fun hmacHex(key: ByteArray, vararg parts: String): String = hex(hmac(key, *parts))

    /** PBKDF2 with HMAC-SHA256, one 32 byte block (RFC 8018, the first block is all that is needed). */
    fun stretch(password: String, salt: ByteArray, iterations: Int): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(password.toByteArray(Charsets.UTF_8).let { if (it.isEmpty()) ByteArray(1) else it }, "HmacSHA256"))
        mac.update(salt)
        mac.update(byteArrayOf(0, 0, 0, 1))
        var u = mac.doFinal()
        val out = u.copyOf()
        for (i in 1 until iterations) {
            u = mac.doFinal(u)
            for (j in out.indices) out[j] = (out[j].toInt() xor u[j].toInt()).toByte()
        }
        return out
    }

    fun xor(a: ByteArray, b: ByteArray): ByteArray = ByteArray(minOf(a.size, b.size)) { (a[it].toInt() xor b[it].toInt()).toByte() }

    /** True when [a] and [b] are the same text; takes as long whatever they share. */
    fun same(a: String, b: String): Boolean = MessageDigest.isEqual(a.toByteArray(Charsets.UTF_8), b.toByteArray(Charsets.UTF_8))

    fun hex(bytes: ByteArray): String {
        val chars = "0123456789abcdef"
        val out = StringBuilder(bytes.size * 2)
        for (b in bytes) out.append(chars[(b.toInt() shr 4) and 15]).append(chars[b.toInt() and 15])
        return out.toString()
    }

    /** The bytes of [text], or null when it is not an even length of hex digits (or longer than [maxBytes]). */
    fun unhex(text: String, maxBytes: Int = 1024): ByteArray? {
        if (text.length % 2 != 0 || text.length > maxBytes * 2) return null
        val out = ByteArray(text.length / 2)
        for (i in out.indices) {
            val hi = Character.digit(text[i * 2], 16)
            val lo = Character.digit(text[i * 2 + 1], 16)
            if (hi < 0 || lo < 0) return null
            out[i] = ((hi shl 4) or lo).toByte()
        }
        return out
    }
}
