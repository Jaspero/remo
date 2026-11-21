package co.jaspero.remo.tv

import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.math.BigInteger
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.security.interfaces.RSAPrivateCrtKey
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.RSAPublicKeySpec
import java.util.Base64

/**
 * This phone's ADB key. The files use the same layout as the adb tool (PKCS#8 PEM private key,
 * and the public key in adb's own format, base64 plus a name), so a key the TV already allowed
 * keeps working.
 */
class AdbKey(val privateKey: PrivateKey, val publicKeyLine: String) {
    val publicKey: PublicKey by lazy {
        val k = privateKey as RSAPrivateCrtKey
        KeyFactory.getInstance("RSA").generatePublic(RSAPublicKeySpec(k.modulus, k.publicExponent))
    }

    /** ADB signs the 20-byte token as if it were a SHA-1 digest: PKCS#1 v1.5 over DigestInfo(SHA-1) + token. */
    fun sign(token: ByteArray): ByteArray = Signature.getInstance("NONEwithRSA").run {
        initSign(privateKey)
        update(SHA1_DIGEST_INFO)
        update(token)
        sign()
    }

    companion object {
        val SHA1_DIGEST_INFO = byteArrayOf(0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14)

        fun loadOrCreate(dir: File, name: String): AdbKey {
            val priv = File(dir, "adbkey")
            val pub = File(dir, "adbkey.pub")
            if (priv.exists() && pub.exists()) {
                runCatching { return load(priv, pub) }
            }
            dir.mkdirs()
            val kp = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.genKeyPair()
            val pem = "-----BEGIN PRIVATE KEY-----\n" +
                Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(kp.private.encoded) +
                "\n-----END PRIVATE KEY-----\n"
            priv.writeText(pem)
            val line = Base64.getEncoder().encodeToString(adbPublicKey(kp.public as RSAPublicKey)) + " " + name
            pub.writeText(line)
            return AdbKey(kp.private, line)
        }

        fun load(priv: File, pub: File): AdbKey {
            val b64 = priv.readText().lines().filterNot { it.startsWith("-----") }.joinToString("")
            val key = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(Base64.getDecoder().decode(b64)))
            return AdbKey(key, pub.readText().trim())
        }

        /** The RSAPublicKey struct adbd expects: word count, n0inv, modulus and R² mod n (little-endian words), exponent. */
        fun adbPublicKey(key: RSAPublicKey): ByteArray {
            val words = 64
            val r32 = BigInteger.ONE.shiftLeft(32)
            val n = key.modulus
            val n0inv = n.remainder(r32).modInverse(r32).negate().mod(r32)
            val rr = BigInteger.ONE.shiftLeft(words * 32).modPow(BigInteger.valueOf(2), n)
            val buf = ByteBuffer.allocate(4 + 4 + words * 4 * 2 + 4).order(ByteOrder.LITTLE_ENDIAN)
            buf.putInt(words)
            buf.putInt(n0inv.toInt())
            fun putWords(v: BigInteger) {
                var x = v
                repeat(words) {
                    buf.putInt(x.remainder(r32).toInt())
                    x = x.shiftRight(32)
                }
            }
            putWords(n)
            putWords(rr)
            buf.putInt(key.publicExponent.toInt())
            return buf.array()
        }
    }
}

class AdbTlsRequiredException : IOException("This TV only allows wireless debugging with a pairing code, which Remo doesn't support yet.")

/**
 * A minimal ADB client: connect and authenticate, run commands, stream an install and push a
 * file. One stream at a time, and every chunk waits for the TV's OKAY before the next is sent;
 * some TVs drop the whole connection when a client runs ahead of that.
 */
class AdbClient private constructor(private val socket: Socket) : Closeable {
    private val input = DataInputStream(socket.getInputStream().buffered())
    private val output = BufferedOutputStream(socket.getOutputStream())
    private var nextLocalId = 1

    /** Largest payload the TV accepts in one message (from its CNXN). */
    var maxPayload = 4096
        private set
    var features: Set<String> = emptySet()
        private set

    private class Message(val command: Int, val arg0: Int, val arg1: Int, val data: ByteArray)

    override fun close() {
        runCatching { socket.close() }
    }

    // ---- Commands -----------------------------------------------------------------------------

    /** Runs a command and returns its output. */
    fun exec(command: String): String = open("exec:$command").readAll()

    /**
     * Streams an APK into `cmd package install`. Returns the installer's output ("Success" or a
     * failure line), or "" if the TV ended the stream without saying anything.
     */
    fun installStreamed(apk: File, onProgress: (Float) -> Unit): String {
        val stream = open("exec:cmd package install -S ${apk.length()} -r")
        try {
            apk.inputStream().use { stream.writeAll(it, apk.length(), onProgress) }
        } catch (e: StreamClosedException) {
            return stream.text()
        }
        return stream.readAll()
    }

    /** Copies a file to the TV with the sync protocol. Throws with the TV's reason on failure. */
    fun push(file: File, remotePath: String, onProgress: (Float) -> Unit) {
        val s = open("sync:")
        val pathAndMode = "$remotePath,420".toByteArray() // 0644
        s.write(syncHeader("SEND", pathAndMode.size) + pathAndMode)
        val total = file.length().coerceAtLeast(1)
        var sent = 0L
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                s.write(syncHeader("DATA", n) + buf.copyOf(n))
                sent += n
                onProgress(sent.toFloat() / total)
            }
        }
        s.write(syncHeader("DONE", (System.currentTimeMillis() / 1000).toInt()))
        val reply = s.readExactly(8)
        val id = String(reply, 0, 4)
        val len = ByteBuffer.wrap(reply, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
        if (id != "OKAY") {
            val msg = if (id == "FAIL" && len in 1..65536) String(s.readExactly(len)) else id
            throw IOException("Copying to the TV failed: $msg")
        }
        runCatching { s.write(syncHeader("QUIT", 0)) }
        s.close()
    }

    private fun syncHeader(id: String, value: Int): ByteArray =
        ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).put(id.toByteArray()).putInt(value).array()

    // ---- Streams ------------------------------------------------------------------------------

    private class StreamClosedException : IOException("The TV closed the stream")

    private inner class Stream(val localId: Int, val remoteId: Int) {
        private val received = ByteArrayOutputStream()
        private var readPos = 0
        var closed = false
            private set

        fun text(): String = received.toString(Charsets.UTF_8.name())

        /** Sends [data] in chunks the TV accepts, waiting for its OKAY after each one. */
        fun write(data: ByteArray) {
            var off = 0
            while (off < data.size) {
                val len = minOf(maxPayload, data.size - off)
                writeChunk(data, off, len)
                off += len
            }
        }

        fun writeAll(input: InputStream, total: Long, onProgress: (Float) -> Unit) {
            val buf = ByteArray(maxPayload)
            var sent = 0L
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                writeChunk(buf, 0, n)
                sent += n
                onProgress(sent.toFloat() / total.coerceAtLeast(1))
            }
        }

        private fun writeChunk(data: ByteArray, off: Int, len: Int) {
            if (closed) throw StreamClosedException()
            send(A_WRTE, localId, remoteId, data, off, len)
            while (true) {
                val m = next()
                when (m.command) {
                    A_OKAY -> return
                    A_WRTE -> receive(m)
                    A_CLSE -> { closed = true; throw StreamClosedException() }
                }
            }
        }

        private fun receive(m: Message) {
            received.write(m.data)
            send(A_OKAY, localId, remoteId)
        }

        /** The next message for this stream; messages for other streams are ignored. */
        private fun next(): Message {
            while (true) {
                val m = read()
                if (m.arg1 == localId || m.command == A_CLSE && m.arg1 == 0) return m
            }
        }

        fun readAll(): String {
            while (!closed) {
                val m = next()
                when (m.command) {
                    A_WRTE -> receive(m)
                    A_CLSE -> close()
                }
            }
            return text()
        }

        fun readExactly(n: Int): ByteArray {
            while (received.size() - readPos < n) {
                if (closed) throw IOException("The TV closed the stream")
                val m = next()
                when (m.command) {
                    A_WRTE -> receive(m)
                    A_CLSE -> closed = true
                }
            }
            val all = received.toByteArray()
            return all.copyOfRange(readPos, readPos + n).also { readPos += n }
        }

        fun close() {
            if (!closed) {
                closed = true
            }
            runCatching { send(A_CLSE, localId, remoteId) }
        }
    }

    private fun open(destination: String): Stream {
        val id = nextLocalId++
        send(A_OPEN, id, 0, (destination + "\u0000").toByteArray())
        while (true) {
            val m = read()
            if (m.arg1 != id && !(m.command == A_CLSE && m.arg1 == 0)) continue
            when (m.command) {
                A_OKAY -> return Stream(id, m.arg0)
                A_CLSE -> throw IOException("The TV refused \"${destination.substringBefore(' ')}\"")
            }
        }
    }

    // ---- Wire ---------------------------------------------------------------------------------

    private fun send(command: Int, arg0: Int, arg1: Int, data: ByteArray = EMPTY, off: Int = 0, len: Int = data.size) {
        var sum = 0
        for (i in off until off + len) sum += data[i].toInt() and 0xFF
        val header = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(command).putInt(arg0).putInt(arg1).putInt(len).putInt(sum).putInt(command.inv())
        output.write(header.array())
        output.write(data, off, len)
        output.flush()
    }

    private fun read(): Message {
        val header = ByteArray(24)
        input.readFully(header)
        val b = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        val command = b.int
        val arg0 = b.int
        val arg1 = b.int
        val len = b.int
        b.int // checksum
        val magic = b.int
        if (magic != command.inv()) throw IOException("Bad ADB message from the TV")
        if (len < 0 || len > 16 * 1024 * 1024) throw IOException("ADB message too large: $len")
        val data = ByteArray(len)
        input.readFully(data)
        return Message(command, arg0, arg1, data)
    }

    companion object {
        const val A_CNXN = 0x4e584e43
        const val A_AUTH = 0x48545541
        const val A_OPEN = 0x4e45504f
        const val A_OKAY = 0x59414b4f
        const val A_CLSE = 0x45534c43
        const val A_WRTE = 0x45545257
        const val A_STLS = 0x534c5453
        const val AUTH_TOKEN = 1
        const val AUTH_SIGNATURE = 2
        const val AUTH_RSAPUBLICKEY = 3
        const val VERSION = 0x01000000
        const val MAX_PAYLOAD = 256 * 1024
        private val EMPTY = ByteArray(0)

        /**
         * Connects and authenticates. The first time, the TV shows "Allow USB debugging?" and the
         * connection waits for an answer for up to [authTimeoutMs].
         */
        fun connect(host: String, port: Int, key: AdbKey, connectTimeoutMs: Int = 4000, authTimeoutMs: Int = 120_000): AdbClient {
            val socket = Socket()
            try {
                socket.tcpNoDelay = true
                socket.connect(InetSocketAddress(host, port), connectTimeoutMs)
                socket.soTimeout = authTimeoutMs
                val c = AdbClient(socket)
                c.send(A_CNXN, VERSION, MAX_PAYLOAD, "host::\u0000".toByteArray())
                var signed = false
                var sentKey = false
                while (true) {
                    val m = c.read()
                    when (m.command) {
                        A_AUTH -> if (m.arg0 == AUTH_TOKEN) {
                            if (!signed) {
                                signed = true
                                c.send(A_AUTH, AUTH_SIGNATURE, 0, key.sign(m.data))
                            } else if (!sentKey) {
                                sentKey = true
                                c.send(A_AUTH, AUTH_RSAPUBLICKEY, 0, (key.publicKeyLine + "\u0000").toByteArray())
                            }
                        }
                        A_STLS -> throw AdbTlsRequiredException()
                        A_CNXN -> {
                            c.maxPayload = minOf(m.arg1.takeIf { it > 0 } ?: 4096, MAX_PAYLOAD)
                            val banner = String(m.data).trimEnd('\u0000')
                            c.features = banner.substringAfter("features=", "").substringBefore(';').split(',').filter { it.isNotEmpty() }.toSet()
                            socket.soTimeout = 60_000
                            return c
                        }
                    }
                }
            } catch (e: Exception) {
                runCatching { socket.close() }
                throw e
            }
        }
    }
}
