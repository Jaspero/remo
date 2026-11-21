package co.jaspero.remo.tv

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.EOFException
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
    private val key: AdbKey by lazy {
        AdbKey.loadOrCreate(dir, "Remo@" + android.os.Build.MODEL.replace(' ', '-'))
    }

    class Session internal constructor(private val host: String, private val connect: () -> AdbClient) {
        private var client: AdbClient = connect()

        /** Runs [block] on the connection, reconnecting once if the TV dropped it. */
        private fun <T> withClient(block: (AdbClient) -> T): T = try {
            block(client)
        } catch (e: IOException) {
            if (e is AdbTlsRequiredException) throw e
            client.close()
            client = connect()
            block(client)
        }

        val abis: List<String> by lazy {
            withClient { c ->
                c.exec("getprop ro.product.cpu.abilist").trim().split(',').map { it.trim() }.filter { it.isNotEmpty() }
                    .ifEmpty { listOf(c.exec("getprop ro.product.cpu.abi").trim()) }
            }
        }

        /**
         * Installs the APK: streamed into the package installer first; if the TV ends that without
         * an answer, the APK is copied to the TV and installed with `pm install`.
         * [pkg] and [versionCode] identify the APK, to check whether it got installed anyway.
         */
        fun install(apk: File, pkg: String, versionCode: Long, onProgress: (Float) -> Unit) {
            val streamed = try {
                if ("cmd" in client.features || client.features.isEmpty()) client.installStreamed(apk, onProgress) else ""
            } catch (e: IOException) {
                ""
            }.trim()
            if (streamed.startsWith("Success")) return
            // A real refusal ("Failure [INSTALL_FAILED_…]") won't go better the other way.
            if ("INSTALL_" in streamed) throw IOException(explain(streamed))
            if (pkg.isNotEmpty() && installedVersion(pkg) == versionCode) return

            val remote = "/data/local/tmp/remo-install.apk"
            try {
                onProgress(0f)
                withClient { it.push(apk, remote, onProgress) }
                val out = withClient { it.exec("pm install -r \"$remote\"") }.trim()
                if (out.contains("Success")) return
                if (pkg.isNotEmpty() && installedVersion(pkg) == versionCode) return
                throw IOException(explain(out.ifEmpty { streamed }))
            } finally {
                runCatching { withClient { it.exec("rm -f \"$remote\"") } }
            }
        }

        private fun installedVersion(pkg: String): Long? = runCatching {
            val out = withClient { it.exec("dumpsys package $pkg") }
            Regex("""versionCode=(\d+)""").find(out)?.groupValues?.get(1)?.toLongOrNull()
        }.getOrNull()

        /** Opens an installed app the way the TV's home screen does. */
        fun launch(pkg: String) {
            val out = withClient { it.exec("monkey -p $pkg -c android.intent.category.LEANBACK_LAUNCHER 1") }
            if ("No activities found" in out || "Error" in out) {
                withClient { it.exec("monkey -p $pkg -c android.intent.category.LAUNCHER 1") }
            }
        }

        internal fun close() = client.close()
    }

    /**
     * Connects and runs [block]. Connecting waits for the TV's "Allow debugging?" prompt the first
     * time, so give the user up to two minutes to answer it.
     */
    suspend fun <T> session(host: String, block: suspend (Session) -> T): T = withContext(Dispatchers.IO) {
        val session = try {
            Session(host) { AdbClient.connect(host, PORT, key) }
        } catch (e: AdbTlsRequiredException) {
            throw e
        } catch (e: ConnectException) {
            throw AdbDisabledException()
        } catch (e: NoRouteToHostException) {
            throw AdbDisabledException()
        } catch (e: SocketTimeoutException) {
            throw AdbNotAllowedException()
        } catch (e: EOFException) {
            // The TV hung up during the handshake: the prompt was declined, or it refuses this key.
            throw AdbNotAllowedException()
        }
        try {
            block(session)
        } finally {
            session.close()
        }
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
