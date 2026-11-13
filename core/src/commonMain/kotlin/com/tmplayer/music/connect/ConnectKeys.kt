package com.tmplayer.music.connect

import java.math.BigInteger
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.interfaces.ECPublicKey
import java.security.spec.ECFieldFp
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import javax.crypto.KeyAgreement

/**
 * One ephemeral ECDH key pair on P-256 (secp256r1), made for one connection and dropped with it.
 * P-256 through `KeyAgreement "ECDH"` works from Android 6 (X25519 would need Android 13).
 *
 * The public key travels as the raw uncompressed point, `04 | X | Y`, 65 bytes as 130 hex digits.
 * A peer's point is untrusted: it is parsed by hand and refused unless both coordinates are below the
 * field prime and it lies on the curve (an invalid-curve key could leak the private key), so what
 * reaches the JDK's own agreement is always a real point. P-256 has cofactor 1, so every point on the
 * curve is in the group.
 */
class EphemeralKey private constructor(private val pair: KeyPair, val publicHex: String) {

    /** The shared secret with the peer's [peerHex] public key, or null when that is not a valid point or the agreement fails. */
    fun agree(peerHex: String): ByteArray? {
        val point = parsePoint(peerHex) ?: return null
        return runCatching {
            val peer = KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(point, params))
            val agreement = KeyAgreement.getInstance("ECDH")
            agreement.init(pair.private)
            agreement.doPhase(peer, true)
            agreement.generateSecret()
        }.getOrNull()?.takeIf { it.size == COORDINATE_BYTES }
    }

    companion object {
        const val POINT_CHARS = 130
        private const val COORDINATE_BYTES = 32

        private val params: ECParameterSpec by lazy {
            AlgorithmParameters.getInstance("EC").apply { init(ECGenParameterSpec("secp256r1")) }.getParameterSpec(ECParameterSpec::class.java)
        }

        fun generate(): EphemeralKey {
            val generator = KeyPairGenerator.getInstance("EC")
            generator.initialize(ECGenParameterSpec("secp256r1"))
            val pair = generator.generateKeyPair()
            val w = (pair.public as ECPublicKey).w
            return EphemeralKey(pair, ConnectCrypto.hex(byteArrayOf(4) + fixed(w.affineX) + fixed(w.affineY)))
        }

        private fun fixed(n: BigInteger): ByteArray {
            val raw = n.toByteArray()
            val out = ByteArray(COORDINATE_BYTES)
            val skip = if (raw.size > COORDINATE_BYTES) raw.size - COORDINATE_BYTES else 0
            System.arraycopy(raw, skip, out, COORDINATE_BYTES - (raw.size - skip), raw.size - skip)
            return out
        }

        /** The point in [hex], or null when it is not 65 bytes starting with 04 or not on the curve. */
        fun parsePoint(hex: String): ECPoint? {
            if (hex.length != POINT_CHARS) return null
            val bytes = ConnectCrypto.unhex(hex, 65)?.takeIf { it.size == 65 && it[0].toInt() == 4 } ?: return null
            val x = BigInteger(1, bytes.copyOfRange(1, 33))
            val y = BigInteger(1, bytes.copyOfRange(33, 65))
            val field = params.curve.field as? ECFieldFp ?: return null
            val p = field.p
            if (x >= p || y >= p) return null
            val left = y.multiply(y).mod(p)
            val right = x.multiply(x).add(params.curve.a).multiply(x).add(params.curve.b).mod(p)
            if (left != right) return null
            return ECPoint(x, y)
        }
    }
}
