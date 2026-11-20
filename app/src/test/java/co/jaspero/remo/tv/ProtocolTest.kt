package co.jaspero.remo.tv

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.math.BigInteger
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPublicKey
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import kotlin.concurrent.thread

/**
 * Runs the real pairing and remote code against a fake TV that speaks the protocol the way the
 * Android TV Remote Service does (framing, field numbers, mutual TLS with self-signed certs).
 */
class ProtocolTest {
    private val tvIdentity = ClientIdentity.generate("Fake TV")
    private val phone = ClientIdentity.generate("Remo test")

    private fun server(): SSLServerSocket {
        val ctx = SSLContext.getInstance("TLS")
        ctx.init(arrayOf(FixedKeyManager(tvIdentity.privateKey, arrayOf(tvIdentity.certificate))), arrayOf(AcceptAnyTrustManager), SecureRandom())
        return (ctx.serverSocketFactory.createServerSocket(0) as SSLServerSocket).apply { needClientAuth = true }
    }

    private fun SSLSocket.send(bytes: ByteArray) {
        outputStream.write(ProtoWriter.frame(bytes))
        outputStream.flush()
    }

    private fun SSLSocket.read() = ProtoMessage.readDelimited(inputStream)

    @Test
    fun protoRoundTrip() {
        val bytes = proto {
            int(1, 622)
            int(2, -1)
            string(3, "héllo")
            message(4) { bool(1, true); bytes(2, byteArrayOf(1, 2, 3)) }
            message(5) {}
        }
        val m = ProtoMessage.parse(bytes)
        assertEquals(622, m.int(1))
        assertEquals(-1, m.int(2))
        assertEquals("héllo", m.string(3))
        assertTrue(m.message(4)!!.bool(1))
        assertArrayEquals(byteArrayOf(1, 2, 3), m.message(4)!!.bytes(2))
        assertTrue("an empty submessage still counts as present", m.has(5))
        assertFalse(m.message(5)!!.bool(1))
    }

    /** Same hash as androidtvremote2: hex strings of the RSA numbers, as the TV computes it. */
    @Test
    fun secretMatchesReferenceAlgorithm() {
        fun hexBytes(n: BigInteger, padOdd: Boolean): ByteArray {
            var h = n.toString(16).uppercase()
            if (h.length % 2 == 1) h = "0$h"
            return ByteArray(h.length / 2) { h.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        }
        val c = phone.certificate.publicKey as RSAPublicKey
        val s = tvIdentity.certificate.publicKey as RSAPublicKey
        val suffix = "A1B2"
        val md = MessageDigest.getInstance("SHA-256")
        listOf(c.modulus, c.publicExponent, s.modulus, s.publicExponent).forEach { md.update(hexBytes(it, true)) }
        md.update(byteArrayOf(0xA1.toByte(), 0xB2.toByte()))
        val expected = md.digest()
        val code = "%02X".format(expected[0]) + suffix
        assertArrayEquals(expected, PairingSession.secretFor(phone.certificate, tvIdentity.certificate, code))
        val wrongFirst = "%02X".format((expected[0] + 1) and 0xFF) + suffix
        assertEquals(null, PairingSession.secretFor(phone.certificate, tvIdentity.certificate, wrongFirst))
    }

    @Test
    fun pairingAgainstFakeTv() = runBlocking {
        val srv = server()
        var shownCode = ""
        var receivedClientCert: X509Certificate? = null
        val tvThread = thread {
            srv.accept().use { raw ->
                val s = raw as SSLSocket
                s.startHandshake()
                receivedClientCert = s.session.peerCertificates[0] as X509Certificate
                fun reply(block: ProtoWriter.() -> Unit) = s.send(proto { int(1, 2); int(2, 200); block() })

                val req = s.read()
                assertEquals("atvremote", req.message(10)!!.string(1))
                reply { message(11) { string(1, "Fake TV") } }

                val opts = s.read().message(20)!!
                assertEquals(3, opts.message(1)!!.int(1)) // hexadecimal
                assertEquals(6, opts.message(1)!!.int(2))
                assertEquals(1, opts.int(3)) // input role
                reply { message(20) { message(1) { int(1, 3); int(2, 6) }; int(3, 1) } }

                val cfg = s.read().message(30)!!
                assertEquals(1, cfg.int(2))
                // The TV picks a code: the last 4 digits are random, the first 2 come from the hash.
                val suffix = "7C3E"
                val hash = PairingSession.secretFor(receivedClientCert!!, tvIdentity.certificate, "00$suffix")
                    ?: run {
                        // secretFor only returns on a match; compute the first byte by trying all.
                        val first = (0..255).first { PairingSession.secretFor(receivedClientCert!!, tvIdentity.certificate, "%02X$suffix".format(it)) != null }
                        PairingSession.secretFor(receivedClientCert!!, tvIdentity.certificate, "%02X$suffix".format(first))!!
                    }
                shownCode = "%02X".format(hash[0]) + suffix
                reply { message(31) {} }

                val secret = s.read().message(40)!!.bytes(1)!!
                assertArrayEquals(hash, secret)
                reply { message(41) { bytes(1, secret) } }
            }
        }

        val pairing = PairingSession("127.0.0.1", phone, "Remo test", srv.localPort)
        withTimeout(10_000) { pairing.start() }
        assertEquals(6, shownCode.length)
        // A typo is caught locally and the session stays usable.
        val typo = (if (shownCode[0] == 'F') "0" else "F") + shownCode.substring(1)
        try {
            pairing.finish(typo)
            fail("wrong code accepted")
        } catch (_: WrongCodeException) {
        }
        val pin = withTimeout(10_000) { pairing.finish(shownCode.lowercase()) }
        pairing.close()
        tvThread.join(5000)
        srv.close()
        assertEquals(ClientIdentity.pin(tvIdentity.certificate), pin)
        assertEquals(phone.certificate, receivedClientCert)
    }

    @Test
    fun remoteSessionAgainstFakeTv() = runBlocking {
        val srv = server()
        val fromPhone = LinkedBlockingQueue<ProtoMessage>()
        val tvThread = thread {
            srv.accept().use { raw ->
                val s = raw as SSLSocket
                s.send(proto { message(1) { int(1, 622); message(2) { string(1, "BRAVIA"); string(2, "Sony") } } })
                val cfg = s.read().message(1)!!
                assertEquals(RemoteSession.FEATURES and 622, cfg.int(1))
                assertEquals("atvremote", cfg.message(2)!!.string(5))
                s.send(proto { message(2) { int(1, 622) } })
                assertEquals(RemoteSession.FEATURES and 622, s.read().message(2)!!.int(1))
                s.send(proto { message(40) { bool(1, true) } })
                s.send(proto { message(50) { int(6, 100); int(7, 23); bool(8, false) } })
                s.send(proto { message(20) { message(1) { int(1, 5); string(12, "com.netflix.ninja") }; message(2) { int(1, 141) } } })
                s.send(proto { message(22) { message(2) { int(1, 141); string(2, "abc"); string(6, "Search") } } })
                s.send(proto { message(8) { int(1, 77) } })
                assertEquals(77, s.read().message(9)!!.int(1))
                try {
                    while (true) fromPhone.put(s.read())
                } catch (_: Exception) {
                }
            }
        }

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val session = RemoteSession("127.0.0.1", phone, ClientIdentity.pin(tvIdentity.certificate), scope, srv.localPort)
        withTimeout(10_000) { session.open() }
        withTimeout(5_000) {
            while (session.state.value.textField == null || session.state.value.volume == null) kotlinx.coroutines.delay(20)
        }
        val st = session.state.value
        assertEquals("BRAVIA", st.model)
        assertEquals(true, st.isOn)
        assertEquals(RemoteSession.Volume(23, 100, false), st.volume)
        assertEquals("com.netflix.ninja", st.currentApp)
        assertEquals(RemoteSession.TextField("abc", "Search"), st.textField)
        assertTrue(session.hasImeTarget)

        session.key(Keys.CENTER)
        session.key(Keys.CENTER, RemoteSession.Direction.START_LONG)
        session.launch("https://www.netflix.com/title")
        session.insertText("hi")

        fun next() = fromPhone.poll(5, TimeUnit.SECONDS) ?: error("nothing received")
        next().message(10)!!.let { assertEquals(23, it.int(1)); assertEquals(3, it.int(2)) }
        next().message(10)!!.let { assertEquals(23, it.int(1)); assertEquals(1, it.int(2)) }
        assertEquals("https://www.netflix.com/title", next().message(90)!!.string(1))
        next().message(21)!!.let { edit ->
            assertEquals(5, edit.int(1))
            assertEquals(141, edit.int(2))
            val info = edit.messages(3).single()
            assertEquals(1, info.int(1))
            val obj = info.message(2)!!
            assertEquals("hi", obj.string(3))
            assertEquals(2, obj.int(1))
            assertEquals(2, obj.int(2))
        }

        session.close()
        tvThread.join(5000)
        srv.close()
        scope.cancel()
    }

    @Test
    fun wrongTvIsRefused() = runBlocking {
        val srv = server()
        val tvThread = thread { runCatching { srv.accept().use { (it as SSLSocket).startHandshake(); it.inputStream.read() } } }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val session = RemoteSession("127.0.0.1", phone, "not-the-paired-tv", scope, srv.localPort)
        try {
            session.open()
            fail("connected to the wrong TV")
        } catch (_: TvIdentityChangedException) {
        }
        tvThread.join(5000)
        srv.close()
        scope.cancel()
    }

    @Test
    fun unpairedPhoneIsReportedAsNotPaired() = runBlocking {
        // The TV hangs up without saying anything, as it does for a certificate it doesn't know.
        val srv = server()
        val tvThread = thread { runCatching { srv.accept().use { (it as SSLSocket).startHandshake() } } }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val session = RemoteSession("127.0.0.1", phone, null, scope, srv.localPort)
        try {
            session.open()
            fail("expected NotPairedException")
        } catch (_: NotPairedException) {
        }
        assertNotNull(session.closed.isCompleted)
        tvThread.join(5000)
        srv.close()
        scope.cancel()
    }
}
