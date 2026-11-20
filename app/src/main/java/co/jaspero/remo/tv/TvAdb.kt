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

        /**
         * Installs the APK. Streaming it straight into the package installer is fastest, but some
         * TVs close that stream without a word, so then it's copied to the TV and installed with
         * `pm install` (as the adb tool does on such devices), which reports its reason on failure.
         * [pkg] and [versionCode] identify the APK, to check whether it got installed anyway.
         */
        fun install(apk: File, pkg: String, versionCode: Long, onProgress: (Float) -> Unit) {
            val streamError = try {
                dadb.install(counting(apk, onProgress), apk.length(), "-r")
                return
            } catch (e: IOException) {
                e
            }
            if (pkg.isNotEmpty() && installedVersion(pkg) == versionCode) return

            val remote = "/data/local/tmp/remo-install.apk"
            try {
                onProgress(0f)
                dadb.push(counting(apk, onProgress), remote, 420 /* 0644 */, System.currentTimeMillis())
                val out = dadb.shell("pm install -r \"$remote\"").allOutput.trim()
                if (out.contains("Success")) return
                if (pkg.isNotEmpty() && installedVersion(pkg) == versionCode) return
                throw IOException(explain(out.ifEmpty { streamError.message.orEmpty() }))
            } finally {
                runCatching { dadb.shell("rm -f \"$remote\"") }
            }
        }

        private fun counting(apk: File, onProgress: (Float) -> Unit): okio.Source {
            val total = apk.length().coerceAtLeast(1)
            var sent = 0L
            return object : ForwardingSource(apk.source()) {
                override fun read(sink: Buffer, byteCount: Long): Long {
                    val n = super.read(sink, byteCount)
                    if (n > 0) {
                        sent += n
                        onProgress(sent.toFloat() / total)
                    }
                    return n
                }
            }
        }

        private fun installedVersion(pkg: String): Long? =
            Regex("""versionCode=(\d+)""").find(dadb.shell("dumpsys package $pkg").output)?.groupValues?.get(1)?.toLongOrNull()

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

        /** Turns the package installer's failure code into what to do about it. */
        fun explain(output: String): String {
            val code = Regex("""(INSTALL_[A-Z_]+)""").find(output)?.groupValues?.get(1)
            val advice = when (code) {
                "INSTALL_FAILED_VERIFICATION_FAILURE", "INSTALL_FAILED_VERIFICATION_TIMEOUT" ->
                    "Google Play Protect blocked it. On the TV, open Settings → System → Developer options, turn off \"Verify apps over USB\", and try again."
                "INSTALL_FAILED_INSUFFICIENT_STORAGE" ->
                    "The TV is out of space. Uninstall or clear the cache of an app you don't use, then try again."
                "INSTALL_FAILED_NO_MATCHING_ABIS", "INSTALL_FAILED_CPU_ABI_INCOMPATIBLE" ->
                    "This build doesn't run on the TV's processor."
                "INSTALL_FAILED_UPDATE_INCOMPATIBLE" ->
                    "A copy of this app from somewhere else is already on the TV. Uninstall it there first, then try again."
                "INSTALL_FAILED_USER_RESTRICTED" ->
                    "The TV doesn't allow installing over debugging. Look for \"Install via USB\" in Developer options and turn it on."
                "INSTALL_FAILED_VERSION_DOWNGRADE" ->
                    "The TV already has a newer version of this app."
                "INSTALL_FAILED_OLDER_SDK" ->
                    "The app needs a newer Android version than the TV has."
                else -> null
            }
            val detail = output.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }.orEmpty()
            return when {
                advice != null -> "$advice ($code)"
                detail.isNotEmpty() -> "The TV refused the app: $detail"
                else -> "The TV refused the app without giving a reason."
            }
        }
    }
}
