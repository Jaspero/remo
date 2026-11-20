package co.jaspero.remo

import android.content.Context
import android.net.Uri
import co.jaspero.remo.tv.AdbDisabledException
import co.jaspero.remo.tv.AdbNotAllowedException
import co.jaspero.remo.tv.TvAdb
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Puts apps on the TV: SmartTube from its GitHub releases, an APK from the phone, or one from a link. */
class AppInstaller(context: Context, private val controller: TvController) {
    private val app = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val adb = TvAdb(File(app.filesDir, "adb"))
    private var job: Job? = null

    sealed interface State {
        data object Idle : State
        /** [waitingForTv]: the TV may be showing "Allow debugging?" and needs an answer. */
        data class Working(val step: String, val progress: Float? = null, val waitingForTv: Boolean = false) : State
        data class Done(val label: String, val pkg: String, val addedButton: Boolean) : State
        data class Failed(val message: String, val needsSetup: Boolean = false) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    val busy get() = _state.value is State.Working

    fun reset() {
        if (!busy) _state.value = State.Idle
    }

    fun cancel() {
        job?.cancel()
        _state.value = State.Idle
    }

    fun installSmartTube() = start { session ->
        _state.value = State.Working("Finding the latest SmartTube…")
        val url = withContext(Dispatchers.IO) { smartTubeUrl(session.abis) }
        download(url)
    }

    fun installFromPhone(uri: Uri) = start {
        _state.value = State.Working("Reading the file…")
        withContext(Dispatchers.IO) {
            val f = tempFile()
            app.contentResolver.openInputStream(uri)?.use { input -> f.outputStream().use { input.copyTo(it) } }
                ?: throw IOException("Couldn't read that file")
            f
        }
    }

    fun installFromLink(link: String) = start { download(link.trim()) }

    /** Opens a sideloaded app; the remote protocol can only open apps that have a link. */
    fun launch(pkg: String) {
        val host = controller.current.value?.host ?: return
        scope.launch {
            runCatching { adb.session(host) { it.launch(pkg) } }
                .onFailure { controller.launchLink("market://launch?id=$pkg") }
        }
    }

    /**
     * Connects first (so the TV prompt is answered before a long download), then gets the APK
     * from [obtain], installs it and adds an app button for it.
     */
    private fun start(obtain: suspend (TvAdb.Session) -> File) {
        if (busy) return
        val tv = controller.current.value ?: run {
            _state.value = State.Failed("Pair a TV first.")
            return
        }
        job = scope.launch {
            var apk: File? = null
            try {
                _state.value = State.Working("Connecting to ${tv.name}…", waitingForTv = true)
                val (label, pkg) = adb.session(tv.host) { session ->
                    val file = obtain(session).also { apk = it }
                    val (label, pkg, version) = apkInfo(file)
                    _state.value = State.Working("Sending $label to the TV…", 0f)
                    session.install(file, pkg, version) { p ->
                        _state.value = if (p < 1f) State.Working("Sending $label to the TV…", p)
                        else State.Working("Installing $label…")
                    }
                    label to pkg
                }
                val added = pkg.isNotEmpty() && controller.addSideloadedPreset(label, pkg)
                _state.value = State.Done(label, pkg, added)
            } catch (e: CancellationException) {
                throw e
            } catch (e: AdbDisabledException) {
                _state.value = State.Failed("Couldn't connect. Switch on debugging on the TV (steps above), then try again.", needsSetup = true)
            } catch (e: AdbNotAllowedException) {
                _state.value = State.Failed("The TV didn't allow it. Try again and choose OK (tick \"Always allow\") on the TV.", needsSetup = true)
            } catch (e: Exception) {
                _state.value = State.Failed(e.message ?: e.javaClass.simpleName)
            } finally {
                apk?.delete()
            }
        }
    }

    private suspend fun download(url: String): File = withContext(Dispatchers.IO) {
        val f = tempFile()
        var conn = URL(url).openConnection() as HttpURLConnection
        // Follow redirects by hand: GitHub sends release downloads to another host.
        repeat(5) {
            conn.instanceFollowRedirects = true
            conn.connectTimeout = 10_000
            conn.readTimeout = 30_000
            val code = conn.responseCode
            if (code in 300..399) {
                val next = conn.getHeaderField("Location") ?: throw IOException("Bad redirect")
                conn.disconnect()
                conn = URL(URL(url), next).openConnection() as HttpURLConnection
            } else {
                if (code != 200) throw IOException("Download failed (HTTP $code)")
                val total = conn.contentLengthLong
                conn.inputStream.use { input ->
                    f.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        var done = 0L
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done += n
                            if (total > 0) _state.value = State.Working("Downloading…", done.toFloat() / total)
                        }
                    }
                }
                return@withContext f
            }
        }
        throw IOException("Too many redirects")
    }

    /** The newest stable SmartTube build for the TV's processor, from the official GitHub releases. */
    private fun smartTubeUrl(abis: List<String>): String {
        val conn = URL("https://api.github.com/repos/yuliskov/SmartTube/releases?per_page=10").openConnection() as HttpURLConnection
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        conn.connectTimeout = 10_000
        conn.readTimeout = 20_000
        if (conn.responseCode != 200) throw IOException("Couldn't reach GitHub (HTTP ${conn.responseCode})")
        val releases = JSONArray(conn.inputStream.bufferedReader().readText())
        for (i in 0 until releases.length()) {
            val r = releases.getJSONObject(i)
            if (r.optBoolean("prerelease") || r.optBoolean("draft")) continue
            val assets = r.getJSONArray("assets")
            val apks = (0 until assets.length()).map { assets.getJSONObject(it) }
                .filter { it.getString("name").endsWith(".apk") && "stable" in it.getString("name") }
            val pick = abis.firstNotNullOfOrNull { abi -> apks.firstOrNull { it.getString("name").contains("_$abi.apk") } }
                ?: apks.firstOrNull { "universal" in it.getString("name") }
            if (pick != null) return pick.getString("browser_download_url")
        }
        throw IOException("No stable SmartTube release found")
    }

    /** The app's name, package and version, read from the APK on the phone. */
    private fun apkInfo(f: File): Triple<String, String, Long> {
        val pm = app.packageManager
        @Suppress("DEPRECATION")
        val info = pm.getPackageArchiveInfo(f.path, 0) ?: return Triple("the app", "", -1L)
        val version = if (android.os.Build.VERSION.SDK_INT >= 28) info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong()
        val ai = info.applicationInfo ?: return Triple(info.packageName, info.packageName, version)
        ai.sourceDir = f.path
        ai.publicSourceDir = f.path
        val label = runCatching { ai.loadLabel(pm).toString() }.getOrDefault(info.packageName)
        return Triple(label, info.packageName, version)
    }

    private fun tempFile(): File {
        val dir = File(app.cacheDir, "apks").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        return File(dir, "install-${System.currentTimeMillis()}.apk")
    }
}
