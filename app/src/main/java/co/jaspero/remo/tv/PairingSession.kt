package co.jaspero.remo.tv

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.IOException
import java.math.BigInteger
import java.net.InetSocketAddress
import java.security.MessageDigest
import java.security.interfaces.RSAPublicKey
import java.security.cert.X509Certificate
import javax.net.ssl.SSLSocket

class PairingException(message: String) : IOException(message)
class WrongCodeException : IOException("That code didn't match. Check the code on the TV and try again.")

/**
 * Pairing with a Google TV / Android TV (the "polo" protocol on port 6467). [start] makes the TV
 * show a 6-character code; [finish] proves we saw it, after which the TV accepts our certificate
 * on the remote port.
 */
class PairingSession(
    private val host: String,
    private val identity: ClientIdentity,
    private val clientName: String,
    private val port: Int = PORT,
) : Closeable {
    private var socket: SSLSocket? = null
    private var serverCert: X509Certificate? = null

    /** Connects and runs the handshake up to the point where the TV shows the code. */
    suspend fun start() = withContext(Dispatchers.IO) {
        val s = identity.sslContext.socketFactory.createSocket() as SSLSocket
        socket = s
        s.connect(InetSocketAddress(host, port), 5000)
        s.soTimeout = 15000
        s.tcpNoDelay = true
        s.startHandshake()
        serverCert = s.session.peerCertificates.firstOrNull() as? X509Certificate
            ?: throw PairingException("The TV didn't present a certificate")

        send {
            message(F_PAIRING_REQUEST) {
                string(1, "atvremote")
                string(2, clientName)
            }
        }
        while (true) {
            val msg = receive()
            when {
                msg.has(F_PAIRING_REQUEST_ACK) -> send {
                    message(F_OPTIONS) {
                        message(1) { int(1, ENCODING_HEXADECIMAL); int(2, 6) }
                        int(3, ROLE_INPUT)
                    }
                }
                msg.has(F_OPTIONS) -> send {
                    message(F_CONFIGURATION) {
                        message(1) { int(1, ENCODING_HEXADECIMAL); int(2, 6) }
                        int(2, ROLE_INPUT)
                    }
                }
                msg.has(F_CONFIGURATION_ACK) -> return@withContext
                else -> throw PairingException("Unexpected reply from the TV: $msg")
            }
        }
    }

    /** Sends the code shown on the TV. Returns the TV's key pin to remember. */
    suspend fun finish(code: String): String = withContext(Dispatchers.IO) {
        val clean = code.trim().uppercase()
        if (!Regex("[0-9A-F]{6}").matches(clean)) throw WrongCodeException()
        val server = serverCert ?: throw PairingException("Pairing wasn't started")
        val secret = secretFor(identity.certificate, server, clean) ?: throw WrongCodeException()
        send { message(F_SECRET) { bytes(1, secret) } }
        val reply = try {
            receive()
        } catch (e: PairingException) {
            if (e.message?.contains("402") == true) throw WrongCodeException() else throw e
        }
        if (!reply.has(F_SECRET_ACK)) throw PairingException("The TV didn't confirm pairing")
        ClientIdentity.pin(server)
    }

    override fun close() {
        runCatching { socket?.close() }
        socket = null
    }

    private fun send(block: ProtoWriter.() -> Unit) {
        val out = socket?.outputStream ?: throw IOException("Not connected")
        out.write(ProtoWriter.frame(proto { int(1, 2); int(2, STATUS_OK); block() }))
        out.flush()
    }

    private fun receive(): ProtoMessage {
        val input = socket?.inputStream ?: throw IOException("Not connected")
        val msg = ProtoMessage.readDelimited(input)
        val status = msg.int(2)
        if (status != STATUS_OK) {
            throw PairingException(
                when (status) {
                    402 -> "The TV rejected the code (402)"
                    401 -> "The TV rejected the pairing settings (401)"
                    else -> "The TV reported an error ($status)"
                },
            )
        }
        return msg
    }

    companion object {
        const val PORT = 6467

        private const val STATUS_OK = 200
        private const val F_PAIRING_REQUEST = 10
        private const val F_PAIRING_REQUEST_ACK = 11
        private const val F_OPTIONS = 20
        private const val F_CONFIGURATION = 30
        private const val F_CONFIGURATION_ACK = 31
        private const val F_SECRET = 40
        private const val F_SECRET_ACK = 41
        private const val ENCODING_HEXADECIMAL = 3
        private const val ROLE_INPUT = 1

        /**
         * SHA-256 over both RSA keys and the last 4 hex digits of the code. The first byte of the
         * hash must equal the first 2 hex digits, which lets a mistyped code be caught locally.
         * Returns null when the code can't be right.
         */
        fun secretFor(client: X509Certificate, server: X509Certificate, code: String): ByteArray? {
            val c = client.publicKey as RSAPublicKey
            val s = server.publicKey as RSAPublicKey
            val md = MessageDigest.getInstance("SHA-256")
            md.update(c.modulus.unsignedBytes())
            md.update(c.publicExponent.unsignedBytes())
            md.update(s.modulus.unsignedBytes())
            md.update(s.publicExponent.unsignedBytes())
            md.update(hex(code.substring(2)))
            val hash = md.digest()
            return if ((hash[0].toInt() and 0xFF) == code.substring(0, 2).toInt(16)) hash else null
        }

        private fun BigInteger.unsignedBytes(): ByteArray {
            val b = toByteArray()
            return if (b.size > 1 && b[0] == 0.toByte()) b.copyOfRange(1, b.size) else b
        }

        private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }
}
