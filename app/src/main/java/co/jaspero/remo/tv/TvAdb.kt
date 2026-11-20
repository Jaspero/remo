package co.jaspero.remo.tv

import dadb.AdbKeyPair
import dadb.Dadb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okio.Buffer
import okio.ForwardingSource
import okio.source
import java.io.File
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException

/** ADB on the TV isn't switched on (Developer options → USB debugging). */
class AdbDisabledException : IOException("Debugging isn't switched on on the TV.")

/** The TV showed "Allow debugging?" and it was declined or timed out. */
class AdbNotAllowedException : IOException("The TV didn't allow the connection.")

/**
 * Installs and opens apps on the TV over ADB (network port 5555), which the remote protocol can't
 * do. The TV asks once to allow this phone's key; with "Always allow" it never asks again.
 */
class TvAdb(private val dir: File) {
    private val keyPair: AdbKeyPair by lazy {
        val priv = File(dir, "adbkey")
        val pub = File(dir, "adbkey.pub")
        if (!priv.exists() || !pub.exists()) {
            AdbKeyPair.generate(priv, pub)
            // The comment after the key is the name the TV shows in its prompt.
            pub.writeText(pub.readText().replace(" unknown@unknown", " Remo@${android.os.Build.MODEL.replace(' ', '-')}"))
        }
        AdbKeyPair.read(priv, pub)
    }

    class Session internal constructor(private val dadb: Dadb) {
        val abis: List<String> by lazy {
            dadb.shell("getprop ro.product.cpu.abilist").output.trim().split(',').map { it.trim() }.filter { it.isNotEmpty() }
                .ifEmpty { listOf(dadb.shell("getprop ro.product.cpu.abi").output.trim()) }
        }

        /** Streams the APK to the TV's package installer. Throws with the installer's reason on failure. */
        fun install(apk: File, onProgress: (Float) -> Unit) {
            val total = apk.length()
            var sent = 0L
            val source = object : ForwardingSource(apk.source()) {
                override fun read(sink: Buffer, byteCount: Long): Long {
                    val n = super.read(sink, byteCount)
                    if (n > 0) {
                        sent += n
                        onProgress(sent.toFloat() / total)
                    }
                    return n
                }
            }
            try {
                source.use { dadb.install(it, total, "-r") }
            } catch (e: IOException) {
                throw IOException("The TV refused the app: " + (e.message?.trim() ?: "unknown reason"), e)
            }
        }

        /** Opens an installed app the way the TV's home screen does. */
        fun launch(pkg: String) {
            val out = dadb.shell("monkey -p $pkg -c android.intent.category.LEANBACK_LAUNCHER 1").allOutput
            if ("No activities found" in out || "Error" in out) {
                dadb.shell("monkey -p $pkg -c android.intent.category.LAUNCHER 1")
            }
        }
    }

    /**
     * Connects and runs [block]. Connecting waits for the TV's "Allow debugging?" prompt the first
     * time, so give the user up to two minutes to answer it.
     */
    suspend fun <T> session(host: String, block: suspend (Session) -> T): T = withContext(Dispatchers.IO) {
        val dadb = Dadb.create(host, PORT, keyPair, connectTimeout = 4000, socketTimeout = 120_000)
        try {
            val s = Session(dadb)
            try {
                s.abis // forces the connection and authorisation
            } catch (e: Exception) {
                throw when (rootCause(e)) {
                    is ConnectException, is NoRouteToHostException -> AdbDisabledException()
                    is SocketTimeoutException -> AdbNotAllowedException()
                    else -> if (e.message?.contains("auth", ignoreCase = true) == true) AdbNotAllowedException() else e
                }
            }
            block(s)
        } finally {
            runCatching { dadb.close() }
        }
    }

    private fun rootCause(e: Throwable): Throwable {
        var t = e
        while (t.cause != null && t.cause !== t) t = t.cause!!
        return t
    }

    companion object {
        const val PORT = 5555
    }
}
