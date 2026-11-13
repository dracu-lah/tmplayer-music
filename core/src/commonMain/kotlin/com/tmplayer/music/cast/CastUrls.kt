package com.tmplayer.music.cast

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * The addresses the Cast device is given. Every URL is `http://<phone's Wi-Fi address>:<port>/<token>/<name>`:
 * the token is made fresh for each cast and is the only thing that lets a request in, and the name is a
 * short, plain file name (so the receiver sees an extension) that says nothing about the track.
 */
object CastUrls {
    private val random = SecureRandom()
    private val nameShape = Regex("[A-Za-z0-9._-]{1,96}")

    /** A one-time secret: 18 random bytes, 24 URL-safe characters. */
    fun newToken(): String {
        val bytes = ByteArray(18)
        random.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    /**
     * The name a resource is served under: [kind] ('a' for audio, 'c' for a cover), 24 hex digits
     * from a hash of [key], and [extension] made safe. The same key always gives the same name, and
     * the key (a chat and message id) never appears in it.
     */
    fun nameFor(kind: Char, key: String, extension: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(key.toByteArray(Charsets.UTF_8))
        val hex = digest.take(12).joinToString("") { "%02x".format(it.toInt() and 0xFF) }
        val ext = extension.lowercase().filter { it in 'a'..'z' || it in '0'..'9' }.take(5).ifEmpty { "bin" }
        return "$kind$hex.$ext"
    }

    /** The file extension that goes with a Content-Type this app serves. */
    fun extensionFor(mime: String): String = when (mime.substringBefore(';').trim().lowercase()) {
        "audio/mpeg" -> "mp3"
        "audio/aac" -> "aac"
        "audio/mp4" -> "m4a"
        "audio/flac" -> "flac"
        "audio/ogg" -> "ogg"
        "audio/wav" -> "wav"
        "image/jpeg" -> "jpg"
        "image/png" -> "png"
        "image/webp" -> "webp"
        else -> "bin"
    }

    fun urlFor(host: String, port: Int, token: String, name: String): String {
        val shown = if (':' in host && !host.startsWith("[")) "[$host]" else host
        return "http://$shown:$port/$token/$name"
    }

    /**
     * The resource name in a request path, or null when the path is not exactly `/<token>/<name>`
     * with the right token and a plain name. The token is compared in constant time.
     */
    fun nameIn(path: String, token: String): String? {
        if (path.length > 256 || !path.startsWith("/")) return null
        val parts = path.substring(1).split('/')
        if (parts.size != 2) return null
        val given = parts[0].toByteArray(Charsets.UTF_8)
        if (!MessageDigest.isEqual(given, token.toByteArray(Charsets.UTF_8))) return null
        return parts[1].takeIf { nameShape.matches(it) }
    }
}
