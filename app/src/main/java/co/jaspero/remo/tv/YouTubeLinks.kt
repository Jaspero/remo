package co.jaspero.remo.tv

import java.net.URI

/** Finds a YouTube video in shared text and turns it into a plain watch link SmartTube understands. */
object YouTubeLinks {
    private val ID = Regex("^[A-Za-z0-9_-]{11}$")
    private val URL_IN_TEXT = Regex("""https?://[^\s"'<>]+""")
    private val HOSTS = setOf("youtube.com", "www.youtube.com", "m.youtube.com", "music.youtube.com", "youtu.be", "www.youtu.be")

    data class Video(val id: String, val startSeconds: Int? = null, val playlist: String? = null) {
        /** Only [A-Za-z0-9_-] and fixed separators, so it is safe to pass through a shell. */
        val url: String
            get() = buildString {
                append("https://www.youtube.com/watch?v=").append(id)
                playlist?.let { append("&list=").append(it) }
                startSeconds?.takeIf { it > 0 }?.let { append("&t=").append(it) }
            }
    }

    /** The first YouTube video linked in [text] (a shared message often has a title before the link), or null. */
    fun find(text: String): Video? = URL_IN_TEXT.findAll(text).firstNotNullOfOrNull { parse(it.value.trimEnd('.', ',', ')', '!')) }

    fun parse(link: String): Video? {
        val uri = runCatching { URI(link) }.getOrNull() ?: return null
        val host = uri.host?.lowercase() ?: return null
        if (host !in HOSTS) return null
        val query = parseQuery(uri.rawQuery)
        val path = uri.path.orEmpty().trim('/').split('/').filter { it.isNotEmpty() }
        val id = when {
            host.endsWith("youtu.be") -> path.firstOrNull()
            path.firstOrNull() == "watch" -> query["v"]
            path.firstOrNull() in setOf("shorts", "live", "embed", "v", "e") -> path.getOrNull(1)
            else -> query["v"]
        }?.takeIf { ID.matches(it) } ?: return null
        val playlist = query["list"]?.takeIf { Regex("^[A-Za-z0-9_-]{2,64}$").matches(it) }
        return Video(id, parseTime(query["t"] ?: query["start"]), playlist)
    }

    /** "90", "90s", "1m30s", "1h2m3s" → seconds. */
    fun parseTime(t: String?): Int? {
        if (t.isNullOrEmpty()) return null
        t.toIntOrNull()?.let { return it }
        val m = Regex("""^(?:(\d+)h)?(?:(\d+)m)?(?:(\d+)s)?$""").matchEntire(t) ?: return null
        val (h, min, s) = m.destructured
        return (h.toIntOrNull() ?: 0) * 3600 + (min.toIntOrNull() ?: 0) * 60 + (s.toIntOrNull() ?: 0)
    }

    private fun parseQuery(raw: String?): Map<String, String> =
        raw.orEmpty().split('&').mapNotNull { part ->
            val i = part.indexOf('=')
            if (i <= 0) null else part.substring(0, i) to java.net.URLDecoder.decode(part.substring(i + 1), "UTF-8")
        }.toMap()
}
