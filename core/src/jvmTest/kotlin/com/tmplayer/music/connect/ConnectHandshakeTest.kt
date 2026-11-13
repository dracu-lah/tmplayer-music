package com.tmplayer.music.connect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The pairing and connect arithmetic of protocol 3: ECDH, HKDF, the transcript, the key confirmation, the QR link. */
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
        val fp = "cd".repeat(16)
        val link = PairLink("192.168.1.20", 41000, hostId, "ab".repeat(16), fp, "Living room")
        assertEquals(link, PairLink.parse(link.text()))
        assertEquals(link, PairLink.parse("  " + link.text() + "\n"))
        listOf(
            null, "", "hello", "tmplayermusic://pair", "tmplayermusic://pair?", "tmplayermusic://pair?h=1.2.3.4",
            link.text().replace("p=41000", "p=0"), link.text().replace("p=41000", "p=99999"), link.text().replace("p=41000", "p=abc"),
            link.text().replace("t=" + "ab".repeat(16), "t=short"), link.text().replace("t=" + "ab".repeat(16), "t=" + "zz".repeat(16)),
            link.text().replace("i=$hostId", "i=zz"), link.text().replace("h=192.168.1.20", "h=evil.example.com/x"),
            link.text() + "x".repeat(400), link.text().replace("&f=$fp", ""), link.text().replace("f=$fp", "f=abcd"), link.text().replace("f=$fp", "f=" + "zz".repeat(16)), "http://x", "tmplayermusic://other?h=1",
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

    /** One leg of an exchange as the phone and the TV each see it: their own view of the keys and nonces. */
    private class Leg(val sn: String, val cn: String, val host: EphemeralKey, val client: EphemeralKey, val commit: String)

    private fun sasOf(leg: Leg, tvReveal: String, phoneReveal: String, side: String): String {
        val th = ConnectHandshake.transcript("c", ConnectLimits.VERSION, leg.sn, leg.cn, hostId, leg.host.publicHex, clientId, "Pixel", leg.client.publicHex, leg.commit)
        val shared = if (side == "tv") leg.host.agree(leg.client.publicHex)!! else leg.client.agree(leg.host.publicHex)!!
        return ConnectHandshake.sas(shared, th, tvReveal, phoneReveal)
    }

    @Test
    fun bothEndsOfAnHonestExchangeShowTheSameSixDigits() {
        val reveal = ConnectHandshake.newReveal()
        val leg = Leg(ConnectHandshake.newNonce(), ConnectHandshake.newNonce(), EphemeralKey.generate(), EphemeralKey.generate(), ConnectHandshake.commit(reveal))
        val tvReveal = ConnectHandshake.newReveal()
        val phone = sasOf(leg, tvReveal, reveal, "phone")
        assertEquals(phone, sasOf(leg, tvReveal, reveal, "tv"))
        assertTrue(Regex("\\d{3} \\d{3}").matches(phone))
    }

    @Test
    fun aRelayedEcdhGivesTheTwoEndsDifferentStrings() {
        // The man in the middle holds two exchanges: phone to him (keys, nonces of that leg) and him to the TV.
        // Even when the code is known to him and every MAC passes, the strings the two users see are not the same.
        var same = 0
        repeat(300) {
            val reveal = ConnectHandshake.newReveal()
            val tvReveal = ConnectHandshake.newReveal()
            val phoneLeg = Leg(ConnectHandshake.newNonce(), ConnectHandshake.newNonce(), EphemeralKey.generate(), EphemeralKey.generate(), ConnectHandshake.commit(reveal))
            val tvLeg = Leg(ConnectHandshake.newNonce(), ConnectHandshake.newNonce(), EphemeralKey.generate(), EphemeralKey.generate(), ConnectHandshake.commit(reveal))
            if (sasOf(phoneLeg, tvReveal, reveal, "phone") == sasOf(tvLeg, tvReveal, reveal, "tv")) same++
        }
        // 1 in a million per run: none in 300 (a failure here would mean the strings do not depend on the exchange).
        assertEquals(0, same)
    }

    @Test
    fun theStringDependsOnEveryPartOfTheTranscriptAndBothRevealedValues() {
        val reveal = ConnectHandshake.newReveal()
        val tvReveal = ConnectHandshake.newReveal()
        val leg = Leg(ConnectHandshake.newNonce(), ConnectHandshake.newNonce(), EphemeralKey.generate(), EphemeralKey.generate(), ConnectHandshake.commit(reveal))
        val base = sasOf(leg, tvReveal, reveal, "tv")
        assertFalse(base == sasOf(leg, ConnectHandshake.newReveal(), reveal, "tv"))
        assertFalse(base == sasOf(leg, tvReveal, ConnectHandshake.newReveal(), "tv"))
        assertFalse(base == sasOf(Leg(ConnectHandshake.newNonce(), leg.cn, leg.host, leg.client, leg.commit), tvReveal, reveal, "tv"))
        assertFalse(base == sasOf(Leg(leg.sn, ConnectHandshake.newNonce(), leg.host, leg.client, leg.commit), tvReveal, reveal, "tv"))
        assertFalse(base == sasOf(Leg(leg.sn, leg.cn, leg.host, leg.client, ConnectHandshake.commit(ConnectHandshake.newReveal())), tvReveal, reveal, "tv"))
    }

    @Test
    fun theCommitmentBindsThePhoneToItsValue() {
        val reveal = ConnectHandshake.newReveal()
        val commit = ConnectHandshake.commit(reveal)
        assertEquals(64, commit.length)
        assertTrue(ConnectCrypto.same(commit, ConnectHandshake.commit(reveal)))
        assertFalse(ConnectCrypto.same(commit, ConnectHandshake.commit(ConnectHandshake.newReveal())))
    }

    @Test
    fun theDecisionAndTheTvValueAreProvenWithTheCodesKeyAndBoundToTheString() {
        val key = ConnectCrypto.randomBytes(32)
        val th = ConnectCrypto.sha256("t")
        val yes = ConnectHandshake.decisionMac(key, th, "123 456", true)
        assertFalse(yes == ConnectHandshake.decisionMac(key, th, "123 456", false))
        assertFalse(yes == ConnectHandshake.decisionMac(key, th, "123 457", true))
        assertFalse(yes == ConnectHandshake.decisionMac(ConnectCrypto.randomBytes(32), th, "123 456", true))
        assertFalse(ConnectHandshake.sasInitMac(key, th, "aa".repeat(16)) == ConnectHandshake.sasInitMac(key, th, "bb".repeat(16)))
    }

    @Test
    fun aKeyFingerprintNamesOneKeyAndGarbageNamesNone() {
        val a = EphemeralKey.generate()
        val b = EphemeralKey.generate()
        assertEquals(ConnectHandshake.FINGERPRINT_CHARS, ConnectHandshake.fingerprint(a.publicHex).length)
        assertEquals(ConnectHandshake.fingerprint(a.publicHex), ConnectHandshake.fingerprint(a.publicHex))
        assertFalse(ConnectHandshake.fingerprint(a.publicHex) == ConnectHandshake.fingerprint(b.publicHex))
        assertEquals("", ConnectHandshake.fingerprint("zz"))
        assertEquals("", ConnectHandshake.fingerprint("ab".repeat(100)))
    }

    @Test
    fun theNewHandshakeFramesRoundTripAndGarbageNeverCrashes() {
        val reveal = "ab".repeat(16)
        val mac = "cc".repeat(32)
        listOf(ConnectFrame.SasInit(reveal, mac), ConnectFrame.SasReveal(reveal), ConnectFrame.Confirm(true, mac), ConnectFrame.Confirm(false, mac)).forEach {
            assertEquals(it, ConnectFrames.parse(ConnectFrames.encode(it)))
        }
        val key = EphemeralKey.generate().publicHex
        val pair = ConnectFrame.Pair(ConnectLimits.VERSION, clientId, "Pixel", "bb".repeat(8), key, ConnectHandshake.Via.Code, mac, commit = "dd".repeat(32))
        assertEquals(pair, ConnectFrames.parse(ConnectFrames.encode(pair)))
        listOf(
            """{"t":"sasi","r":"zz","m":"$mac"}""", """{"t":"sasi","r":"$reveal"}""", """{"t":"sasr","r":"${"ab".repeat(40)}"}""", """{"t":"sasr"}""",
            """{"t":"sasc","ok":"yes","m":"$mac"}""", """{"t":"sasc","m":"$mac"}""", """{"t":"sasc","ok":1,"m":"short"}""",
            """{"t":"pair","v":${ConnectLimits.VERSION},"id":"$clientId","cn":"${"bb".repeat(8)}","ek":"$key","via":"c","m":"$mac","rc":"nothex"}""",
            """{"t":"pair","v":${ConnectLimits.VERSION},"id":"$clientId","cn":"${"bb".repeat(8)}","ek":"$key","via":"c","m":"$mac","rc":5}""",
            "{\"t\":\"sasc\"", "[]", "null", "\u0000",
        ).forEach { assertNull(it, ConnectFrames.parse(it)) }
        // Random text never throws.
        val random = java.util.Random(7)
        repeat(300) {
            val noise = String(CharArray(random.nextInt(200)) { (random.nextInt(95) + 32).toChar() }).replace("\"", "'").replace("\\", "/")
            ConnectFrames.parse(noise)
            ConnectFrames.parse("""{"t":"sasc","ok":${random.nextInt()},"m":"$noise"}""")
        }
    }

    @Test
    fun aPairLinkNameIsOnlyTextAndIsCutAndCleaned() {
        val long = PairLink("192.168.1.20", 41000, hostId, "ab".repeat(16), "cd".repeat(16), "N".repeat(200) + "\u0007")
        val back = PairLink.parse(long.text())!!
        assertTrue(back.name.length <= 30)
        assertTrue(long.text().length < 400)
        val odd = PairLink("192.168.1.20", 41000, hostId, "ab".repeat(16), "cd".repeat(16), "Tv & co = x é")
        assertEquals("Tv & co = x é", PairLink.parse(odd.text())!!.name)
        assertEquals("", PairLink.parse(odd.text().replace("&n=", "&n=%zz"))!!.name)
        assertEquals("", PairLink.parse(PairLink("192.168.1.20", 41000, hostId, "ab".repeat(16), "cd".repeat(16)).text())!!.name)
    }
}
