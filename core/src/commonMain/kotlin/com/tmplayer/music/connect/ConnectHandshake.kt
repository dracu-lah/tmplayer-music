package com.tmplayer.music.connect

/**
 * The arithmetic of Connect's handshake (protocol 2), shared by the TV (host) and the phone (client),
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
 * Known limit: someone who sits in the middle of the exchange while the user types the code can
 * relay it and learn the code from the phone's confirmation (every code-only scheme without a PAKE
 * has this); the QR token is 128 bits and cannot be tested that way, which is why it is the primary path.
 */
object ConnectHandshake {
    const val SECRET_BYTES = 32
    const val NONCE_BYTES = 16
    const val ID_BYTES = 16

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
        clientId: String, clientName: String, clientKey: String,
    ): ByteArray = ConnectCrypto.sha256(
        listOf(
            "tmpm2", kind, version.toString(), sn, cn, hostId, hostKey, clientId,
            ConnectCrypto.hex(ConnectCrypto.sha256(clientName)), clientKey,
        ).joinToString("|"),
    )

    fun authMac(secret: ByteArray, transcript: ByteArray): String = ConnectCrypto.hmacHex(secret, "auth", ConnectCrypto.hex(transcript))

    fun readyMac(secret: ByteArray, transcript: ByteArray): String = ConnectCrypto.hmacHex(secret, "ready", ConnectCrypto.hex(transcript))

    /** The key of the pairing proofs: from the ECDH secret, the transcript and the proof ([Via.Code] digits or [Via.Token] hex). */
    fun confirmKey(shared: ByteArray, transcript: ByteArray, via: Via, proof: String): ByteArray =
        ConnectCrypto.hkdf(shared, transcript, "tmpm2 confirm|${via.wire}|$proof".toByteArray(Charsets.UTF_8))

    fun pairMac(confirmKey: ByteArray, transcript: ByteArray): String = ConnectCrypto.hmacHex(confirmKey, "pair", ConnectCrypto.hex(transcript))

    /** The secret hidden under the confirmation key, as hex; [unwrap] undoes it. */
    fun wrap(secret: ByteArray, confirmKey: ByteArray, transcript: ByteArray): String =
        ConnectCrypto.hex(ConnectCrypto.xor(secret, ConnectCrypto.hmac(confirmKey, "wrap", ConnectCrypto.hex(transcript))))

    fun unwrap(wrapped: String, confirmKey: ByteArray, transcript: ByteArray): ByteArray? {
        val bytes = ConnectCrypto.unhex(wrapped, SECRET_BYTES)?.takeIf { it.size == SECRET_BYTES } ?: return null
        return ConnectCrypto.xor(bytes, ConnectCrypto.hmac(confirmKey, "wrap", ConnectCrypto.hex(transcript)))
    }

    fun pairedMac(secret: ByteArray, transcript: ByteArray): String = ConnectCrypto.hmacHex(secret, "paired", ConnectCrypto.hex(transcript))

    /**
     * The two keys of one connection: phone to TV, TV to phone. New for every connection, from the
     * ECDH secret (so a stolen pairing secret does not open recorded traffic), the pairing secret and the transcript.
     */
    fun channel(shared: ByteArray, secret: ByteArray, transcript: ByteArray, host: Boolean): SecureChannel {
        val ikm = shared + secret
        val toHost = ConnectCrypto.hkdf(ikm, transcript, "tmpm2 c2t".toByteArray(Charsets.UTF_8))
        val toClient = ConnectCrypto.hkdf(ikm, transcript, "tmpm2 t2c".toByteArray(Charsets.UTF_8))
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
