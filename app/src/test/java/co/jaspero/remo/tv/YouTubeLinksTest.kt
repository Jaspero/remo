package co.jaspero.remo.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class YouTubeLinksTest {
    private fun url(text: String) = YouTubeLinks.find(text)?.url

    @Test
    fun sharedFromTheYouTubeApp() {
        assertEquals("https://www.youtube.com/watch?v=dQw4w9WgXcQ", url("https://youtu.be/dQw4w9WgXcQ?si=AbCdEf123"))
        assertEquals("https://www.youtube.com/watch?v=dQw4w9WgXcQ&t=42", url("Check this out https://youtu.be/dQw4w9WgXcQ?si=x&t=42"))
    }

    @Test
    fun browserAndOtherForms() {
        assertEquals("https://www.youtube.com/watch?v=dQw4w9WgXcQ", url("https://m.youtube.com/watch?v=dQw4w9WgXcQ&feature=share"))
        assertEquals("https://www.youtube.com/watch?v=dQw4w9WgXcQ", url("https://www.youtube.com/shorts/dQw4w9WgXcQ"))
        assertEquals("https://www.youtube.com/watch?v=dQw4w9WgXcQ", url("https://www.youtube.com/live/dQw4w9WgXcQ?si=1"))
        assertEquals("https://www.youtube.com/watch?v=dQw4w9WgXcQ&list=PL1234567890&t=90",
            url("https://www.youtube.com/watch?v=dQw4w9WgXcQ&list=PL1234567890&t=1m30s"))
        assertEquals("https://www.youtube.com/watch?v=dQw4w9WgXcQ", url("https://music.youtube.com/watch?v=dQw4w9WgXcQ"))
    }

    @Test
    fun rejectsNonVideosAndLookalikes() {
        assertNull(url("https://www.youtube.com/@somechannel"))
        assertNull(url("https://evil.example/watch?v=dQw4w9WgXcQ"))
        assertNull(url("https://youtube.com.evil.example/watch?v=dQw4w9WgXcQ"))
        assertNull(url("https://youtu.be/short"))
        assertNull(url("no link here"))
        // Anything that could reach the shell is rejected by the id pattern.
        assertNull(url("https://youtu.be/abc';reboot;'"))
    }

    @Test
    fun times() {
        assertEquals(3723, YouTubeLinks.parseTime("1h2m3s"))
        assertEquals(45, YouTubeLinks.parseTime("45s"))
        assertNull(YouTubeLinks.parseTime("soon"))
    }
}
