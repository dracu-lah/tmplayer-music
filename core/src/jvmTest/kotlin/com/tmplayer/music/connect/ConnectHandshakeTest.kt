package com.tmplayer.music.connect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The pairing and connect arithmetic of protocol 2: ECDH, HKDF, the transcript, the key confirmation, the QR link. */
class ConnectHandshakeTest {
    private val hostId = "11".repeat(16)
    private val clientId = "22".repeat(16)

    /** One recorded run of a pairing, as the phone made it, with the host's own keys kept for the check. */
    private class Run(val sn: String, val cn: String, val host: EphemeralKey, val client: EphemeralKey, val name: String = "Pixel")

    private fun run() = Run(ConnectHandshake.newNonce(), ConnectHandshake.newNonce(), EphemeralKey.generate(), EphemeralKey.generate())

    private fun transcript(r: Run, kind: String) =
        ConnectHandshake.transcript(kind, ConnectLimits.VERSION, r.sn, r.cn, hostId, r.host.publicHex, clientId, r.name, r.client.publicHex)

    @Test
    fun hkdfMatchesRfc5869TestCase1() {
        val ikm = ByteArray(22) { 0x0b }
        val salt = ByteArray(13) { it.toByte() }
        val info = ByteArray(10) { (0xf0 + it).toByte() }
        val okm = ConnectCrypto.hkdf(ikm, salt, info, 42)
        assertEquals("3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865", ConnectCrypto.hex(okm))
    }

    @Test
    fun bothSidesOfTheEcdhAgreeAndAnotherKeyDoesNot() {
        val a = EphemeralKey.generate()
        val b = EphemeralKey.generate()
        val c = EphemeralKey.generate()
        assertEquals(EphemeralKey.POINT_CHARS, a.publicHex.length)
        assertTrue(a.agree(b.publicHex)!!.contentEquals(b.agree(a.publicHex)!!))
        assertFalse(a.agree(b.publicHex)!!.contentEquals(a.agree(c.publicHex)!!))
    }

    @Test
    fun aTamperedOrInvalidEcdhKeyIsRefused() {
        val a = EphemeralKey.generate()
        val b = EphemeralKey.generate()
        // One flipped digit of the Y coordinate: off the curve.
        val flipped = b.publicHex.dropLast(1) + (if (b.publicHex.last() == '0') "1" else "0")
        assertNull(a.agree(flipped))
        assertNull(a.agree(""))
        assertNull(a.agree("04" + "00".repeat(64)))
        assertNull(a.agree("02" + b.publicHex.substring(2, 66) + "00".repeat(32)))
        assertNull(a.agree("zz".repeat(65)))
        assertNull(a.agree(b.publicHex + "00"))
        // A coordinate not below the field prime.
        assertNull(a.agree("04" + "ff".repeat(64)))
        assertNotNull(a.agree(b.publicHex))
    }

    @Test
    fun theRightCodeConfirmsAndAWrongCodeNever() {
        val r = run()
        val th = transcript(r, "c")
        val shared = r.host.agree(r.client.publicHex)!!
        val phoneShared = r.client.agree(r.host.publicHex)!!
        val phoneKey = ConnectHandshake.confirmKey(phoneShared, th, ConnectHandshake.Via.Code, "123456")
        val mac = ConnectHandshake.pairMac(phoneKey, th)
        // The host tries the code on show: right is confirmed, every other code is not.
        assertTrue(ConnectCrypto.same(mac, ConnectHandshake.pairMac(ConnectHandshake.confirmKey(shared, th, ConnectHandshake.Via.Code, "123456"), th)))
        var confirmed = 0
        for (n in 0 until 2_000) {
            val guess = n.toString().padStart(6, '0').takeIf { it != "123456" } ?: continue
            if (ConnectCrypto.same(mac, ConnectHandshake.pairMac(ConnectHandshake.confirmKey(shared, th, ConnectHandshake.Via.Code, guess), th))) confirmed++
        }
        assertEquals(0, confirmed)
        // The token and the code are not interchangeable.
        assertFalse(ConnectCrypto.same(mac, ConnectHandshake.pairMac(ConnectHandshake.confirmKey(shared, th, ConnectHandshake.Via.Token, "123456"), th)))
    }

    @Test
    fun aRecordedExchangeGivesNothingToTestACodeAgainst() {
        // An eavesdropper has both public keys, both nonces and the phone's MAC, but not the ECDH
        // secret: whatever it computes from the public data does not reproduce the MAC, even for the right code.
        val r = run()
        val th = transcript(r, "c")
        val phoneKey = ConnectHandshake.confirmKey(r.client.agree(r.host.publicHex)!!, th, ConnectHandshake.Via.Code, "123456")
        val mac = ConnectHandshake.pairMac(phoneKey, th)
        val noSecret = ConnectCrypto.sha256(r.host.publicHex + r.client.publicHex).copyOf(32)
        assertFalse(ConnectCrypto.same(mac, ConnectHandshake.pairMac(ConnectHandshake.confirmKey(noSecret, th, ConnectHandshake.Via.Code, "123456"), th)))
        val stranger = EphemeralKey.generate().agree(r.host.publicHex)!!
        assertFalse(ConnectCrypto.same(mac, ConnectHandshake.pairMac(ConnectHandshake.confirmKey(stranger, th, ConnectHandshake.Via.Code, "123456"), th)))
    }

    @Test
    fun aReplayedTranscriptFailsOnANewConnection() {
        val first = run()
        val th1 = transcript(first, "c")
        val key1 = ConnectHandshake.confirmKey(first.client.agree(first.host.publicHex)!!, th1, ConnectHandshake.Via.Code, "123456")
        val recorded = ConnectHandshake.pairMac(key1, th1)
        // The same phone frame (same phone key, same nonce) played at a new connection: the TV has new keys and a new nonce.
        val again = Run(ConnectHandshake.newNonce(), first.cn, EphemeralKey.generate(), first.client)
        val th2 = transcript(again, "c")
        val key2 = ConnectHandshake.confirmKey(again.host.agree(first.client.publicHex)!!, th2, ConnectHandshake.Via.Code, "123456")
        assertFalse(ConnectCrypto.same(recorded, ConnectHandshake.pairMac(key2, th2)))
        // The transcript covers every part: the name, the version and the kind change it.
        val base = transcript(first, "c")
        assertFalse(base.contentEquals(transcript(Run(first.sn, first.cn, first.host, first.client, "Other"), "c")))
        assertFalse(base.contentEquals(transcript(first, "t")))
        assertFalse(base.contentEquals(transcript(first, "auth")))
    }

    @Test
    fun theSecretTravelsHiddenUnderTheConfirmationKey() {
        val r = run()
        val th = transcript(r, "c")
        val secret = ConnectHandshake.newSecret()
        val key = ConnectHandshake.confirmKey(r.host.agree(r.client.publicHex)!!, th, ConnectHandshake.Via.Code, "123456")
        val wrapped = ConnectHandshake.wrap(secret, key, th)
        assertTrue(ConnectHandshake.unwrap(wrapped, key, th)!!.contentEquals(secret))
        val wrong = ConnectHandshake.confirmKey(r.host.agree(r.client.publicHex)!!, th, ConnectHandshake.Via.Code, "123457")
        assertFalse(ConnectHandshake.unwrap(wrapped, wrong, th)!!.contentEquals(secret))
        assertNull(ConnectHandshake.unwrap("zz", key, th))
        assertNull(ConnectHandshake.unwrap("ab".repeat(33), key, th))
    }

    @Test
    fun pairedProofsFitOnlyTheRightSecretAndTranscript() {
        val r = run()
        val th = transcript(r, "auth")
        val secret = ConnectHandshake.newSecret()
        assertEquals(ConnectHandshake.authMac(secret, th), ConnectHandshake.authMac(secret, th))
        assertFalse(ConnectHandshake.authMac(secret, th) == ConnectHandshake.authMac(ConnectHandshake.newSecret(), th))
        assertFalse(ConnectHandshake.authMac(secret, th) == ConnectHandshake.authMac(secret, transcript(run(), "auth")))
        assertFalse(ConnectHandshake.authMac(secret, th) == ConnectHandshake.readyMac(secret, th))
    }

    @Test
    fun sessionKeysDependOnTheEcdhSecretNotJustThePairingSecret() {
        val secret = ConnectHandshake.newSecret()
        val th = ConnectCrypto.sha256("t")
        val one = ConnectHandshake.channel(ConnectCrypto.randomBytes(32), secret, th, host = true)
        val phone = ConnectHandshake.channel(ConnectCrypto.randomBytes(32), secret, th, host = false)
        // A phone with the secret but another ECDH result cannot talk to the host.
        assertNull(one.open(phone.seal(ConnectBody.Ping)))
    }

    @Test
    fun aPairLinkRoundTripsAndGarbageIsRefused() {
        val link = PairLink("192.168.1.20", 41000, hostId, "ab".repeat(16))
        assertEquals(link, PairLink.parse(link.text()))
        assertEquals(link, PairLink.parse("  " + link.text() + "\n"))
        listOf(
            null, "", "hello", "tmplayermusic://pair", "tmplayermusic://pair?", "tmplayermusic://pair?h=1.2.3.4",
            link.text().replace("p=41000", "p=0"), link.text().replace("p=41000", "p=99999"), link.text().replace("p=41000", "p=abc"),
            link.text().replace("t=" + "ab".repeat(16), "t=short"), link.text().replace("t=" + "ab".repeat(16), "t=" + "zz".repeat(16)),
            link.text().replace("i=$hostId", "i=zz"), link.text().replace("h=192.168.1.20", "h=evil.example.com/x"),
            link.text() + "x".repeat(300), "http://x", "tmplayermusic://other?h=1",
        ).forEach { assertNull(it, PairLink.parse(it)) }
    }

    @Test
    fun framesOfAnotherVersionParseSoTheyCanBeTurnedAwayAndGarbageNeverCrashes() {
        // An old (version 1) hello has no key: it still parses, as another version.
        val old = ConnectFrames.parse("""{"t":"hello","v":1,"sn":"${"aa".repeat(8)}","id":"$hostId","name":"TV"}""") as ConnectFrame.Hello
        assertEquals(1, old.version)
        assertEquals("", old.key)
        assertTrue(ConnectFrames.parse("""{"t":"pair","v":1,"id":"$clientId","name":"P","cn":"${"bb".repeat(8)}","m":"${"cc".repeat(32)}"}""") is ConnectFrame.Pair)
        // The current version needs a real-looking key.
        assertNull(ConnectFrames.parse("""{"t":"hello","v":${ConnectLimits.VERSION},"sn":"${"aa".repeat(8)}","id":"$hostId","name":"TV"}"""))
        assertNull(ConnectFrames.parse("""{"t":"hello","v":${ConnectLimits.VERSION},"sn":"${"aa".repeat(8)}","id":"$hostId","name":"TV","ek":"04"}"""))
        assertNull(ConnectFrames.parse("""{"t":"pair","v":${ConnectLimits.VERSION},"id":"$clientId","cn":"${"bb".repeat(8)}","ek":"${"0".repeat(130)}","via":"x","m":"${"cc".repeat(32)}"}"""))
        val key = EphemeralKey.generate().publicHex
        val pair = ConnectFrame.Pair(ConnectLimits.VERSION, clientId, "Pixel", "bb".repeat(8), key, ConnectHandshake.Via.Token, "cc".repeat(32))
        assertEquals(pair, ConnectFrames.parse(ConnectFrames.encode(pair)))
        val auth = ConnectFrame.Auth(ConnectLimits.VERSION, clientId, "Pixel", "bb".repeat(8), key, "cc".repeat(32))
        assertEquals(auth, ConnectFrames.parse(ConnectFrames.encode(auth)))
        val hello = ConnectFrame.Hello(ConnectLimits.VERSION, "aa".repeat(8), hostId, "TV", key)
        assertEquals(hello, ConnectFrames.parse(ConnectFrames.encode(hello)))
    }
}
