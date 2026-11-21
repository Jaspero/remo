package co.jaspero.remo.tv

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.File
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.security.SecureRandom
import java.security.Signature
import kotlin.concurrent.thread

/**
 * A strict pretend adbd: it insists on authentication, sends small max payloads, and hangs up if
 * a client sends a WRTE before the previous one was acknowledged, the way some TVs behave.
 */
private class StrictAdbd(
    private val allowedKey: AdbKey?,
    private val maxPayload: Int = 4096,
    private val features: String = "shell_v2,cmd,stat_v2",
) {
    val server = ServerSocket(0)
    val port get() = server.localPort
    @Volatile var violation: String? = null
    @Volatile var installedBytes: ByteArray? = null
    val pushed = HashMap<String, ByteArray>()
    val commands = mutableListOf<String>()
    @Volatile var promptedForKey = false

    private class Msg(val cmd: Int, val a0: Int, val a1: Int, val data: ByteArray)

    fun start() = thread(isDaemon = true) {
        runCatching { server.accept().use { serve(it) } }
    }

    private fun read(input: DataInputStream): Msg {
        val h = ByteArray(24); input.readFully(h)
        val b = ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN)
        val cmd = b.int; val a0 = b.int; val a1 = b.int; val len = b.int; val sum = b.int; val magic = b.int
        check(magic == cmd.inv()) { "bad magic" }
        val d = ByteArray(len); input.readFully(d)
        check(d.sumOf { it.toInt() and 0xFF } == sum) { "bad checksum" }
        if (len > maxPayload && cmd == AdbClient.A_WRTE) error("payload $len over max $maxPayload")
        return Msg(cmd, a0, a1, d)
    }

    private fun send(out: OutputStream, cmd: Int, a0: Int, a1: Int, data: ByteArray = ByteArray(0)) {
        val h = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(cmd).putInt(a0).putInt(a1).putInt(data.size).putInt(data.sumOf { it.toInt() and 0xFF }).putInt(cmd.inv())
        out.write(h.array()); out.write(data); out.flush()
    }

    private fun serve(s: Socket) {
        val input = DataInputStream(s.getInputStream().buffered())
        val out = s.getOutputStream().buffered()
        check(read(input).cmd == AdbClient.A_CNXN)
        val token = ByteArray(20).also { SecureRandom().nextBytes(it) }
        send(out, AdbClient.A_AUTH, AdbClient.AUTH_TOKEN, 0, token)
        val sig = read(input)
        check(sig.cmd == AdbClient.A_AUTH && sig.a0 == AdbClient.AUTH_SIGNATURE)
        val ok = allowedKey != null && Signature.getInstance("NONEwithRSA").run {
            initVerify(allowedKey.publicKey); update(AdbKey.SHA1_DIGEST_INFO); update(token); verify(sig.data)
        }
        if (!ok) {
            send(out, AdbClient.A_AUTH, AdbClient.AUTH_TOKEN, 0, token)
            val pub = read(input)
            check(pub.a0 == AdbClient.AUTH_RSAPUBLICKEY)
            promptedForKey = true // the user taps "Allow"
        }
        send(out, AdbClient.A_CNXN, AdbClient.VERSION, maxPayload, "device::ro.product.model=FakeTV;features=$features".toByteArray())

        var remoteIdSeq = 100
        while (true) {
            val open = read(input)
            if (open.cmd != AdbClient.A_OPEN) continue
            val dest = String(open.data).trimEnd('\u0000')
            val local = open.a0
            val remote = remoteIdSeq++
            commands += dest
            send(out, AdbClient.A_OKAY, remote, local)
            fun reply(text: String) {
                send(out, AdbClient.A_WRTE, remote, local, text.toByteArray())
                check(read(input).cmd == AdbClient.A_OKAY)
                send(out, AdbClient.A_CLSE, remote, local)
            }
            /** Receives exactly [n] bytes, acknowledging each WRTE only after it is processed. */
            fun receive(n: Long, buf: ByteArrayOutputStream) {
                while (buf.size() < n) {
                    val m = read(input)
                    if (m.cmd != AdbClient.A_WRTE) error("expected WRTE, got ${m.cmd}")
                    buf.write(m.data)
                    // Strict: a client that sends the next WRTE before this OKAY is caught here.
                    Thread.sleep(2)
                    if (input.available() > 0) {
                        violation = "WRTE sent before OKAY"
                        s.close()
                        return
                    }
                    send(out, AdbClient.A_OKAY, remote, local)
                }
            }
            when {
                dest.startsWith("exec:cmd package install -S ") -> {
                    val size = dest.removePrefix("exec:cmd package install -S ").substringBefore(' ').toLong()
                    val buf = ByteArrayOutputStream()
                    receive(size, buf)
                    if (violation != null) return
                    installedBytes = buf.toByteArray()
                    reply("Success\n")
                }
                dest == "exec:getprop ro.product.cpu.abilist" -> reply("arm64-v8a,armeabi-v7a,armeabi\n")
                dest == "sync:" -> {
                    val all = ByteArrayOutputStream()
                    var pos = 0
                    fun take(n: Int): ByteArray {
                        receive(pos + n.toLong(), all)
                        check(violation == null) { violation!! }
                        return all.toByteArray().copyOfRange(pos, pos + n).also { pos += n }
                    }
                    var path = ""
                    val data = ByteArrayOutputStream()
                    while (true) {
                        val h = take(8)
                        val id = String(h, 0, 4)
                        val v = ByteBuffer.wrap(h, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
                        when (id) {
                            "SEND" -> path = String(take(v))
                            "DATA" -> data.write(take(v))
                            "DONE" -> break
                            else -> error("unexpected sync id $id")
                        }
                    }
                    pushed[path.substringBefore(',')] = data.toByteArray()
                    send(out, AdbClient.A_WRTE, remote, local, ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).put("OKAY".toByteArray()).putInt(0).array())
                    check(read(input).cmd == AdbClient.A_OKAY)
                    // QUIT (acknowledged), then the client's CLSE.
                    val quit = read(input)
                    check(quit.cmd == AdbClient.A_WRTE && String(quit.data, 0, 4) == "QUIT")
                    send(out, AdbClient.A_OKAY, remote, local)
                    check(read(input).cmd == AdbClient.A_CLSE)
                }
                else -> reply("ok: $dest\n")
            }
        }
    }
}

class AdbClientTest {
    private fun tmpDir(): File = Files.createTempDirectory("adbkey").toFile()

    private fun apk(size: Int): File = File.createTempFile("test", ".apk").apply {
        writeBytes(ByteArray(size) { (it * 31 + 7).toByte() })
        deleteOnExit()
    }

    @Test
    fun publicKeyFormatMatchesDadb() {
        val dir = tmpDir()
        dadb.AdbKeyPair.generate(File(dir, "adbkey"), File(dir, "adbkey.pub"))
        val theirs = File(dir, "adbkey.pub").readText().substringBefore(' ')
        val ours = AdbKey.load(File(dir, "adbkey"), File(dir, "adbkey.pub"))
        val mine = java.util.Base64.getEncoder().encodeToString(AdbKey.adbPublicKey(ours.publicKey as java.security.interfaces.RSAPublicKey))
        assertEquals(theirs, mine)
    }

    /** The old library ran ahead of the TV's OKAYs; a strict TV hangs up on it. */
    @Test
    fun dadbStreamingInstallTripsAStrictTv() {
        val dir = tmpDir()
        dadb.AdbKeyPair.generate(File(dir, "adbkey"), File(dir, "adbkey.pub"))
        val allowed = AdbKey.load(File(dir, "adbkey"), File(dir, "adbkey.pub"))
        val tv = StrictAdbd(allowedKey = allowed, maxPayload = 4096).apply { start() }
        val d = dadb.Dadb.create("127.0.0.1", tv.port, dadb.AdbKeyPair.read(File(dir, "adbkey"), File(dir, "adbkey.pub")))
        val result = runCatching { d.install(apk(300_000)) }
        runCatching { d.close() }
        assertTrue("dadb should have failed, got $result", result.isFailure)
        assertEquals("WRTE sent before OKAY", tv.violation)
    }

    @Test
    fun firstConnectionSendsKeyThenStreamsInstallWithFlowControl() {
        val key = AdbKey.loadOrCreate(tmpDir(), "Remo@test")
        val tv = StrictAdbd(allowedKey = null, maxPayload = 4096).apply { start() }
        val file = apk(300_000)
        AdbClient.connect("127.0.0.1", tv.port, key).use { c ->
            assertTrue(tv.promptedForKey)
            assertEquals(4096, c.maxPayload)
            assertEquals("arm64-v8a,armeabi-v7a,armeabi", c.exec("getprop ro.product.cpu.abilist").trim())
            var last = 0f
            val out = c.installStreamed(file) { last = it }
            assertEquals(null, tv.violation)
            assertEquals("Success", out.trim())
            assertEquals(1f, last)
            assertArrayEquals(file.readBytes(), tv.installedBytes)
        }
    }

    @Test
    fun allowedKeySkipsThePromptAndPushWorks() {
        val dir = tmpDir()
        val key = AdbKey.loadOrCreate(dir, "Remo@test")
        // Reload from disk: the saved files must give the same, already-allowed key.
        val reloaded = AdbKey.loadOrCreate(dir, "Remo@test")
        assertEquals(key.publicKeyLine, reloaded.publicKeyLine)
        val tv = StrictAdbd(allowedKey = key, maxPayload = 1024).apply { start() }
        val file = apk(150_000)
        AdbClient.connect("127.0.0.1", tv.port, reloaded).use { c ->
            assertFalse(tv.promptedForKey)
            c.push(file, "/data/local/tmp/x.apk") {}
            assertEquals(null, tv.violation)
            assertArrayEquals(file.readBytes(), tv.pushed["/data/local/tmp/x.apk"])
            assertEquals("ok: exec:pm install -r x", c.exec("pm install -r x").trim())
        }
    }
}
