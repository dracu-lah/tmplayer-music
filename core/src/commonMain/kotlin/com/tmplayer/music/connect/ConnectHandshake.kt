package com.tmplayer.music.connect

/**
 * The arithmetic of Connect's handshake, shared by the TV (host) and the phone (client), so the two
 * can only ever disagree on purpose.
 *
 * Paired phone: the host sends a nonce `sn`, the phone answers with its own `cn`, its id and
 * `HMAC(secret, auth|sn|cn|id)`; the host answers `HMAC(secret, ready|sn|cn)`. Each side has shown
 * it holds the secret without sending it, and a recorded exchange is useless: both nonces are new
 * every connection.
 *
 * Pairing: the phone has the code typed from the TV's screen. Both sides stretch it with the two
 * nonces into a one time key; the phone proves it with `HMAC(codeKey, pair|sn|cn|id)`; the host then
 * makes a random 256 bit secret and sends it hidden under `HMAC(codeKey, wrap|sn|cn|id)`, with
 * `HMAC(secret, paired|sn|cn)` so the phone can tell it came out right. After that the code is
 * spent and only the secret matters. A 6 digit code is weak against someone who recorded the
 * whole exchange and tries all million offline, which is why it lives for two minutes, works once
 * and is stretched; a wrong code on the wire is rate limited ([PairingGate]).
 */
object ConnectHandshake {
    /** Costly enough to slow a million guesses, cheap enough for a TV stick (about 0.1 s). */
    const val STRETCH_ROUNDS = 20_000

    const val SECRET_BYTES = 32
    const val NONCE_BYTES = 16
    const val ID_BYTES = 16

    fun newNonce(): String = ConnectCrypto.randomHex(NONCE_BYTES)
    fun newId(): String = ConnectCrypto.randomHex(ID_BYTES)
    fun newSecret(): ByteArray = ConnectCrypto.randomBytes(SECRET_BYTES)

    fun authMac(secret: ByteArray, sn: String, cn: String, id: String): String = ConnectCrypto.hmacHex(secret, "auth", sn, cn, id)

    fun readyMac(secret: ByteArray, sn: String, cn: String): String = ConnectCrypto.hmacHex(secret, "ready", sn, cn)

    fun codeKey(code: String, sn: String, cn: String): ByteArray =
        ConnectCrypto.stretch(code, "tmpmc1|$sn|$cn".toByteArray(Charsets.UTF_8), STRETCH_ROUNDS)

    fun pairMac(codeKey: ByteArray, sn: String, cn: String, id: String): String = ConnectCrypto.hmacHex(codeKey, "pair", sn, cn, id)

    /** The secret hidden under the code's key, as hex; [unwrap] undoes it. */
    fun wrap(secret: ByteArray, codeKey: ByteArray, sn: String, cn: String, id: String): String =
        ConnectCrypto.hex(ConnectCrypto.xor(secret, ConnectCrypto.hmac(codeKey, "wrap", sn, cn, id)))

    fun unwrap(wrapped: String, codeKey: ByteArray, sn: String, cn: String, id: String): ByteArray? {
        val bytes = ConnectCrypto.unhex(wrapped, SECRET_BYTES)?.takeIf { it.size == SECRET_BYTES } ?: return null
        return ConnectCrypto.xor(bytes, ConnectCrypto.hmac(codeKey, "wrap", sn, cn, id))
    }

    fun pairedMac(secret: ByteArray, sn: String, cn: String): String = ConnectCrypto.hmacHex(secret, "paired", sn, cn)

    /** The two keys of one connection: phone to TV, TV to phone. New for every connection, from the secret and both nonces. */
    fun channel(secret: ByteArray, sn: String, cn: String, host: Boolean): SecureChannel {
        val toHost = ConnectCrypto.hmac(secret, "c2t", sn, cn)
        val toClient = ConnectCrypto.hmac(secret, "t2c", sn, cn)
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
