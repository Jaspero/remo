package co.jaspero.remo.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class SavedTv(
    val name: String,
    val host: String,
    /** The TV's key fingerprint from pairing; connections to anything else are refused. */
    val pin: String,
)

data class Preset(
    val id: String,
    val name: String,
    val link: String,
    /** The app's package on the TV, used to highlight the app that's open. */
    val pkg: String,
    val background: Long,
    val foreground: Long,
    val builtIn: Boolean = false,
)

data class Settings(
    val volumeKeys: Boolean = true,
    val haptics: Boolean = true,
    val touchpad: Boolean = false,
    val showMedia: Boolean = true,
)

object BuiltInPresets {
    val all = listOf(
        Preset("youtube", "YouTube", "https://www.youtube.com", "com.google.android.youtube.tv", 0xFFFF0033, 0xFFFFFFFF, true),
        Preset("netflix", "Netflix", "https://www.netflix.com/title", "com.netflix.ninja", 0xFF141414, 0xFFE50914, true),
        Preset("disney", "Disney+", "https://www.disneyplus.com", "com.disney.disneyplus", 0xFF0B2A8C, 0xFFFFFFFF, true),
        Preset("kodi", "Kodi", "market://launch?id=org.xbmc.kodi", "org.xbmc.kodi", 0xFF1A9FD6, 0xFFFFFFFF, true),
    )

    private val palette = listOf(
        0xFF6D4AFF to 0xFFFFFFFF, 0xFF00A86B to 0xFFFFFFFF, 0xFFFF8A00 to 0xFF1A1A1A,
        0xFF1DB954 to 0xFF0B0B0B, 0xFF00A8E1 to 0xFFFFFFFF, 0xFFE5A00D to 0xFF1F1F1F, 0xFFEC407A to 0xFFFFFFFF,
    )

    /**
     * A user preset from what they typed: a link (anything with "://") or a package name, which
     * is opened with market://launch, the way the TV launches apps that have no deep link.
     */
    fun custom(name: String, target: String): Preset {
        val t = target.trim()
        val isLink = "://" in t
        val pkg = if (isLink) Regex("[?&]id=([\\w.]+)").find(t)?.groupValues?.get(1).orEmpty() else t
        val (bg, fg) = palette[(name.hashCode() and 0x7FFFFFFF) % palette.size]
        return Preset(
            id = "custom-" + System.currentTimeMillis(),
            name = name.trim(),
            link = if (isLink) t else "market://launch?id=$t",
            pkg = pkg,
            background = bg,
            foreground = fg,
        )
    }
}

class Store(context: Context) {
    private val prefs = context.getSharedPreferences("remo", Context.MODE_PRIVATE)

    fun tvs(): List<SavedTv> = parse(prefs.getString("tvs", null)) {
        SavedTv(it.getString("name"), it.getString("host"), it.optString("pin"))
    }

    fun saveTvs(list: List<SavedTv>) = prefs.edit().putString("tvs", JSONArray(list.map {
        JSONObject().put("name", it.name).put("host", it.host).put("pin", it.pin)
    }).toString()).apply()

    var currentTvName: String?
        get() = prefs.getString("current", null)
        set(v) = prefs.edit().putString("current", v).apply()

    fun presets(): List<Preset> {
        val raw = prefs.getString("presets", null) ?: return BuiltInPresets.all
        return parse(raw) {
            Preset(
                it.getString("id"), it.getString("name"), it.getString("link"), it.optString("pkg"),
                it.getLong("bg"), it.getLong("fg"), it.optBoolean("builtIn"),
            )
        }
    }

    fun savePresets(list: List<Preset>) = prefs.edit().putString("presets", JSONArray(list.map {
        JSONObject().put("id", it.id).put("name", it.name).put("link", it.link).put("pkg", it.pkg)
            .put("bg", it.background).put("fg", it.foreground).put("builtIn", it.builtIn)
    }).toString()).apply()

    fun settings() = Settings(
        volumeKeys = prefs.getBoolean("volumeKeys", true),
        haptics = prefs.getBoolean("haptics", true),
        touchpad = prefs.getBoolean("touchpad", false),
        showMedia = prefs.getBoolean("showMedia", true),
    )

    fun saveSettings(s: Settings) = prefs.edit()
        .putBoolean("volumeKeys", s.volumeKeys)
        .putBoolean("haptics", s.haptics)
        .putBoolean("touchpad", s.touchpad)
        .putBoolean("showMedia", s.showMedia)
        .apply()

    private fun <T> parse(raw: String?, map: (JSONObject) -> T): List<T> {
        if (raw.isNullOrEmpty()) return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            List(arr.length()) { map(arr.getJSONObject(it)) }
        }.getOrDefault(emptyList())
    }
}
