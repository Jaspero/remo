package co.jaspero.remo.tv

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.net.InetSocketAddress
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import kotlin.concurrent.thread

/**
 * A pretend TV for trying the app in an emulator (it reaches the PC as 10.0.2.2). Only runs when
 * REMO_FAKE_TV is set: REMO_FAKE_TV=/tmp/faketv ./gradlew :app:testDebugUnitTest --tests '*FakeTvServer*'
 * The pairing code is written to $REMO_FAKE_TV/code and everything the phone sends to $REMO_FAKE_TV/log.
 */
class FakeTvServer {
    @Test
    fun serve() {
        val dir = System.getenv("REMO_FAKE_TV")
        assumeTrue(dir != null)
        val out = File(dir!!).apply { mkdirs() }
        val log = File(out, "log")
        fun note(s: String) = synchronized(this) { log.appendText(s + "\n") }
        val tv = ClientIdentity.generate("Fake TV")
        val ctx = SSLContext.getInstance("TLS").apply {
            init(arrayOf(FixedKeyManager(tv.privateKey, arrayOf(tv.certificate))), arrayOf(AcceptAnyTrustManager), SecureRandom())
        }
        fun listen(port: Int) = (ctx.serverSocketFactory.createServerSocket() as SSLServerSocket).apply {
            reuseAddress = true
            needClientAuth = true
            bind(InetSocketAddress("0.0.0.0", port))
        }
        val paired = mutableSetOf<X509Certificate>()
        fun SSLSocket.send(b: ByteArray) { outputStream.write(ProtoWriter.frame(b)); outputStream.flush() }

        thread {
            val srv = listen(6467)
            while (true) {
                val s = srv.accept() as SSLSocket
                thread {
                    runCatching {
                        s.use {
                            s.startHandshake()
                            val client = s.session.peerCertificates[0] as X509Certificate
                            fun reply(block: ProtoWriter.() -> Unit) = s.send(proto { int(1, 2); int(2, 200); block() })
                            note("pair: request ${s.read().message(10)?.string(2)}")
                            reply { message(11) { string(1, "Fake TV") } }
                            s.read(); reply { message(20) { message(1) { int(1, 3); int(2, 6) }; int(3, 1) } }
                            s.read()
                            val suffix = "%04X".format(SecureRandom().nextInt(65536))
                            val first = (0..255).first { PairingSession.secretFor(client, tv.certificate, "%02X$suffix".format(it)) != null }
                            val code = "%02X$suffix".format(first)
                            File(out, "code").writeText(code)
                            note("pair: showing code $code")
                            reply { message(31) {} }
                            val secret = s.read().message(40)!!.bytes(1)!!
                            if (secret.contentEquals(PairingSession.secretFor(client, tv.certificate, code))) {
                                synchronized(paired) { paired += client }
                                reply { message(41) { bytes(1, secret) } }
                                note("pair: OK")
                            } else {
                                s.send(proto { int(1, 2); int(2, 402) })
                                note("pair: bad secret")
                            }
                        }
                    }.onFailure { note("pair: ${it.message}") }
                }
            }
        }

        val srv = listen(6466)
        while (true) {
            val s = srv.accept() as SSLSocket
            thread {
                runCatching {
                    s.use {
                        s.startHandshake()
                        val client = s.session.peerCertificates[0] as X509Certificate
                        if (synchronized(paired) { client !in paired }) {
                            note("remote: unknown phone, hanging up")
                            return@thread
                        }
                        note("remote: connected")
                        s.send(proto { message(1) { int(1, 622); message(2) { string(1, "Fake TV"); string(2, "Remo") } } })
                        s.read(); s.send(proto { message(2) { int(1, 622) } }); s.read()
                        s.send(proto { message(40) { bool(1, true) } })
                        s.send(proto { message(50) { int(6, 100); int(7, 18) } })
                        s.send(proto { message(20) { message(1) { int(1, 0); string(12, "com.netflix.ninja") } } })
                        thread {
                            runCatching {
                                while (true) { Thread.sleep(5000); s.send(proto { message(8) { int(1, 1) } }) }
                            }
                        }
                        while (true) {
                            val m = s.read()
                            when {
                                m.has(10) -> note("key ${m.message(10)!!.int(1)} dir ${m.message(10)!!.int(2)}")
                                m.has(90) -> note("launch ${m.message(90)!!.string(1)}")
                                m.has(21) -> note("text '${m.message(21)!!.messages(3).first().message(2)!!.string(3)}'")
                                m.has(9) -> Unit
                                else -> note("other $m")
                            }
                        }
                    }
                }.onFailure { note("remote: closed (${it.message})") }
            }
        }
    }

    private fun SSLSocket.read() = ProtoMessage.readDelimited(inputStream)
}
