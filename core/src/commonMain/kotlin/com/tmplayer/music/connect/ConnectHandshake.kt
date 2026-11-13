package com.tmplayer.music.connect

/**
 * The arithmetic of Connect's handshake (protocol 3), shared by the TV (host) and the phone (client),
 * so the two can only ever disagree on purpose.
 *
 * Every connection starts with an ephemeral ECDH exchange on P-256 ([EphemeralKey]): the host's
 * `Hello` carries its public key and a nonce `sn`, the phone answers with its key, its nonce `cn` and
 * its id. Both then hold the same `shared` secret that nobody who only watched the wire can compute,
 * and the same [transcript]: a hash of the version, both nonces, both ids, the phone's name and both
 * public keys. Everything after is bound to that transcript, so a recorded exchange fits no other
 * connection (every connection has new keys and nonces).
 *
 * Paired phone: `HMAC(secret, auth|transcript)` from the phone, `HMAC(secret, ready|transcript)` back.
 *
 * Pairing: the phone proves it knows the pairing proof, the 6 digit code typed from the TV's screen
 * or the 128 bit token of its QR code, with a key confirmation MAC keyed by
 * `HKDF(shared, transcript, proof)`. That key exists only inside the ECDH exchange, so a recording of
 * the whole exchange gives nothing to test a code against offline, and an attacker who talks to the
 * TV gets one guess per attempt ([PairingGate]: five tries, a lock, a new code). The host then makes
 * a random 256 bit secret and sends it hidden under that same key, with `HMAC(secret, paired|transcript)`
 * so the phone can tell the TV knew the proof. After that the code and token are spent and only
 * the secret matters.
 *
 * Authenticating a new phone: a code of 6 digits has no PAKE behind it, so someone who sits in the
 * middle (or poses as the TV on the network) learns the phone's confirmation MAC and can test the code
 * against it offline in milliseconds. What stops that man in the middle is not the code but a short
 * authentication string ([sas], 6 digits) that both ends derive from the whole exchange (the
 * transcript, the ECDH secret and two random values, one from each side). The TV shows it and the
 * phone shows it; the phone's user and the TV's user each confirm that the two screens agree, and the
 * pairing is only stored then. A man in the middle holds two separate exchanges (phone to him, him to
 * the TV) with different keys, so the two strings differ and the user says no, except for a 1 in a
 * million chance. To keep him from tuning one exchange to match the other, the phone commits to its
 * random value before it sees the TV's ([commit]) and reveals it after, so whichever side he fixes
 * last is blind to the other. A no on either screen ends the attempt and counts as a wrong try
 * ([PairingGate.reject]).
 *
 * The QR code carries a fingerprint of the key the TV will use ([fingerprint]) next to the 128 bit
 * token. The phone refuses a TV whose key does not fit it, so a device that merely answers at the same
 * address cannot pose as the TV, and no string is compared on that path. The QR code must still come
 * from the TV's own screen: a link from anywhere else names a TV of its own.
 */
object ConnectHandshake {
    const val SECRET_BYTES = 32
    const val NONCE_BYTES = 16
    const val ID_BYTES = 16
    const val REVEAL_BYTES = 16
    const val FINGERPRINT_CHARS = 32

    /** What a pairing phone proves it knows. */
    enum class Via(val wire: String) {
        Code("c"),
        Token("t");

        companion object {
            fun of(wire: String?): Via? = entries.firstOrNull { it.wire == wire }
        }
    }

    fun newNonce(): String = ConnectCrypto.randomHex(NONCE_BYTES)
    fun newId(): String = ConnectCrypto.randomHex(ID_BYTES)
    fun newSecret(): ByteArray = ConnectCrypto.randomBytes(SECRET_BYTES)

    /** [kind] is "auth" for a paired phone, else the [Via] wire letter: a proof made for one cannot pass as the other. */
    fun transcript(
        kind: String, version: Int, sn: String, cn: String, hostId: String, hostKey: String,
        clientId: String, clientName: String, clientKey: String, commit: String = "",
    ): ByteArray = ConnectCrypto.sha256(
        listOf(
            "tmpm3", kind, version.toString(), sn, cn, hostId, hostKey, clientId,
            ConnectCrypto.hex(ConnectCrypto.sha256(clientName)), clientKey, commit,
        ).joinToString("|"),
    )

    fun authMac(secret: ByteArray, transcript: ByteArray): String = ConnectCrypto.hmacHex(secret, "auth", ConnectCrypto.hex(transcript))

    fun readyMac(secret: ByteArray, transcript: ByteArray): String = ConnectCrypto.hmacHex(secret, "ready", ConnectCrypto.hex(transcript))

    /** The key of the pairing proofs: from the ECDH secret, the transcript and the proof ([Via.Code] digits or [Via.Token] hex). */
    fun confirmKey(shared: ByteArray, transcript: ByteArray, via: Via, proof: String): ByteArray =
        ConnectCrypto.hkdf(shared, transcript, "tmpm3 confirm|${via.wire}|$proof".toByteArray(Charsets.UTF_8))

    fun pairMac(confirmKey: ByteArray, transcript: ByteArray): String = ConnectCrypto.hmacHex(confirmKey, "pair", ConnectCrypto.hex(transcript))

    /** The secret hidden under the confirmation key, as hex; [unwrap] undoes it. */
    fun wrap(secret: ByteArray, confirmKey: ByteArray, transcript: ByteArray): String =
        ConnectCrypto.hex(ConnectCrypto.xor(secret, ConnectCrypto.hmac(confirmKey, "wrap", ConnectCrypto.hex(transcript))))

    fun unwrap(wrapped: String, confirmKey: ByteArray, transcript: ByteArray): ByteArray? {
        val bytes = ConnectCrypto.unhex(wrapped, SECRET_BYTES)?.takeIf { it.size == SECRET_BYTES } ?: return null
        return ConnectCrypto.xor(bytes, ConnectCrypto.hmac(confirmKey, "wrap", ConnectCrypto.hex(transcript)))
    }

    fun pairedMac(secret: ByteArray, transcript: ByteArray): String = ConnectCrypto.hmacHex(secret, "paired", ConnectCrypto.hex(transcript))

    /** A random value for the authentication string: the phone's [commit]ted one, and the TV's. */
    fun newReveal(): String = ConnectCrypto.randomHex(REVEAL_BYTES)

    /** What the phone sends before the TV's value is known: it binds the phone to [reveal] without showing it. */
    fun commit(reveal: String): String = ConnectCrypto.hex(ConnectCrypto.sha256("tmpm3 sas commit|$reveal"))

    /**
     * The 6 digit string both sides show, as `123 456`: from the ECDH secret, the whole transcript (both
     * public keys, both nonces, both ids, the version, the phone's commitment) and the two random values.
     * A relayed exchange has other keys and nonces on each leg, so its two ends get different strings.
     */
    fun sas(shared: ByteArray, transcript: ByteArray, tvReveal: String, phoneReveal: String): String {
        val bytes = ConnectCrypto.hkdf(shared, transcript, "tmpm3 sas|$tvReveal|$phoneReveal".toByteArray(Charsets.UTF_8), 4)
        var n = 0L
        for (b in bytes) n = (n shl 8) or (b.toLong() and 0xff)
        val digits = (n % 1_000_000L).toString().padStart(6, '0')
        return digits.substring(0, 3) + " " + digits.substring(3)
    }

    /** The TV's proof, inside the key exchange, that it knew the code when it sent its value [tvReveal]. */
    fun sasInitMac(confirmKey: ByteArray, transcript: ByteArray, tvReveal: String): String =
        ConnectCrypto.hmacHex(confirmKey, "sasinit", tvReveal, ConnectCrypto.hex(transcript))

    /** The phone's answer to "do the two screens show [sas]?", bound to that string and the exchange. */
    fun decisionMac(confirmKey: ByteArray, transcript: ByteArray, sas: String, ok: Boolean): String =
        ConnectCrypto.hmacHex(confirmKey, "sasdecision", if (ok) "yes" else "no", sas, ConnectCrypto.hex(transcript))

    /** A short name for a TV key (128 bits of its hash, as hex): what the QR code carries to pin the key the phone must meet. */
    fun fingerprint(publicHex: String): String {
        val raw = ConnectCrypto.unhex(publicHex, 65) ?: return ""
        return ConnectCrypto.hex(ConnectCrypto.sha256(raw)).take(FINGERPRINT_CHARS)
    }

    /**
     * The two keys of one connection: phone to TV, TV to phone. New for every connection, from the
     * ECDH secret (so a stolen pairing secret does not open recorded traffic), the pairing secret and the transcript.
     */
    fun channel(shared: ByteArray, secret: ByteArray, transcript: ByteArray, host: Boolean): SecureChannel {
        val ikm = shared + secret
        val toHost = ConnectCrypto.hkdf(ikm, transcript, "tmpm3 c2t".toByteArray(Charsets.UTF_8))
        val toClient = ConnectCrypto.hkdf(ikm, transcript, "tmpm3 t2c".toByteArray(Charsets.UTF_8))
        return if (host) SecureChannel(sendKey = toClient, receiveKey = toHost) else SecureChannel(sendKey = toHost, receiveKey = toClient)
    }
}

/**
 * The two directions of one trusted connection. Every body leaves as a [ConnectFrame.Secured]:
 * a counter that starts at 1 and goes up by one, and an HMAC over counter and payload with this
 * direction's key. A frame with a wrong MAC, a counter that is not the next one (a replay, a gap, a
 * reorder) or a payload that is not a known message is refused.
 */
class SecureChannel(private val sendKey: ByteArray, private val receiveKey: ByteArray) {
    private var sent = 0L
    private var received = 0L

    @Synchronized
    fun seal(body: ConnectBody): ConnectFrame.Secured {
        val payload = ConnectBodies.encode(body)
        val seq = ++sent
        return ConnectFrame.Secured(seq, payload, ConnectCrypto.hmacHex(sendKey, seq.toString(), payload))
    }

    /**
     * The body inside [frame], or null when it is not exactly the next frame from the peer or its
     * MAC is wrong (the connection is then dropped). A body that is not understood comes back as a
     * [ConnectBody.Problem] of [UNKNOWN].
     */
    @Synchronized
    fun open(frame: ConnectFrame.Secured): ConnectBody? {
        if (frame.seq != received + 1) return null
        if (!ConnectCrypto.same(frame.mac, ConnectCrypto.hmacHex(receiveKey, frame.seq.toString(), frame.payload))) return null
        received = frame.seq
        // A genuine peer's message this build does not know (a newer minor version) is skipped, not fatal.
        return ConnectBodies.parse(frame.payload) ?: ConnectBody.Problem(UNKNOWN)
    }

    companion object {
        const val UNKNOWN = "unknown"
    }
}
