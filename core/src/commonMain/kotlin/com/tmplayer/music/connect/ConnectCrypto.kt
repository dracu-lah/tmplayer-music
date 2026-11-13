package com.tmplayer.music.connect

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * The few primitives Connect rests on, all from the JDK and Android's own providers (no library):
 * HMAC-SHA256, HKDF-SHA256, SHA-256, random bytes and a comparison that does not stop at the first
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

    fun sha256(vararg parts: ByteArray): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        for (p in parts) digest.update(p)
        return digest.digest()
    }

    fun sha256(text: String): ByteArray = sha256(text.toByteArray(Charsets.UTF_8))

    private fun hmacBytes(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec((if (key.isEmpty()) ByteArray(1) else key), "HmacSHA256"))
        return mac.doFinal(data)
    }

    /** HKDF-SHA256 (RFC 5869): [length] bytes (at most 255 * 32) from [ikm], [salt] and [info]. */
    fun hkdf(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int = 32): ByteArray {
        require(length in 1..255 * 32)
        val prk = hmacBytes(if (salt.isEmpty()) ByteArray(32) else salt, ikm)
        val out = ByteArray(length)
        var block = ByteArray(0)
        var done = 0
        var counter = 1
        while (done < length) {
            block = hmacBytes(prk, block + info + byteArrayOf(counter.toByte()))
            val n = minOf(block.size, length - done)
            System.arraycopy(block, 0, out, done, n)
            done += n
            counter++
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
