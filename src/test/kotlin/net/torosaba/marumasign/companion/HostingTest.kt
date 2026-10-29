package net.torosaba.marumasign.companion

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class HostingTest {

    private val sign = "https://i.imgur.com/abcdefg.png|0|1.5|0|2|2|0|0|0"

    @Test
    fun replaceUrlKeepsEverythingAfterTheUrl() {
        assertEquals("https://h.example/aaaaaaaaaaaa.png|0|1.5|0|2|2|0|0|0",
            SignLines.replaceUrl(sign, "https://h.example/aaaaaaaaaaaa.png"))
        assertEquals("https://h.example/x.png|1|2|3|4|5|6|7|8|extra|text",
            SignLines.replaceUrl("https://a.example/a.png|1|2|3|4|5|6|7|8|extra|text", "https://h.example/x.png"))
        assertNull(SignLines.replaceUrl("not a sign", "https://h.example/x.png"))
        assertNull(SignLines.replaceUrl("https://a.example/a.png|1|2|3", "https://h.example/x.png"))
    }

    @Test
    fun splitMatchesMarumaSignOnTheFront() {
        val s = SignLines.split("abcdefghij")!!
        // 本体の SignSpec.splitLines と同じ: 余りは前の行から 1 文字ずつ
        assertEquals(listOf("abc", "def", "gh", "ij"), s.front)
        assertEquals(listOf("", "", "", ""), s.back)
        assertEquals("abcdefghij", s.front.joinToString("") + s.back.joinToString(""))
    }

    @Test
    fun splitUsesTheBackWhenTheFrontIsFull() {
        val text = "x".repeat(321) + "y".repeat(9)
        val s = SignLines.split(text)!!
        assertTrue(s.front.all { it.length == 80 })
        assertTrue((s.front + s.back).all { it.length <= SignLines.MAX_LINE_LENGTH })
        assertEquals(text, s.front.joinToString("") + s.back.joinToString(""))
        assertNull(SignLines.split("z".repeat(641)))
    }

    @Test
    fun policyRejectsStreamsAndHtml() {
        val allowed = MediaKind.defaultExtensions()
        assertEquals(HostingPolicy.Reject.STREAM, HostingPolicy.hostableExtension("m3u8", allowed))
        assertEquals(HostingPolicy.Reject.STREAM, HostingPolicy.hostableExtension("mpd", allowed))
        assertEquals(HostingPolicy.Reject.EXTERNAL_FILES, HostingPolicy.hostableExtension("gltf", allowed))
        assertEquals(HostingPolicy.Reject.TYPE, HostingPolicy.hostableExtension("exe", allowed))
        assertNull(HostingPolicy.hostableExtension("png", allowed))
        assertNull(HostingPolicy.hostableExtension("", allowed))

        assertEquals(HostingPolicy.Reject.STREAM, HostingPolicy.checkResponse("mp3", "audio/mpeg", icy = true))
        assertEquals(HostingPolicy.Reject.STREAM, HostingPolicy.checkResponse("", "application/vnd.apple.mpegurl", icy = false))
        assertEquals(HostingPolicy.Reject.STREAM, HostingPolicy.checkResponse("jpg", "multipart/x-mixed-replace", icy = false))
        assertEquals(HostingPolicy.Reject.HTML, HostingPolicy.checkResponse("png", "text/html", icy = false))
        assertEquals(HostingPolicy.Reject.HTML, HostingPolicy.checkResponse("png", "text/plain", icy = false))
        assertEquals(HostingPolicy.Reject.TYPE, HostingPolicy.checkResponse("", "application/octet-stream", icy = false))
        assertNull(HostingPolicy.checkResponse("glb", "application/octet-stream", icy = false))
        assertNull(HostingPolicy.checkResponse("svg", "text/xml", icy = false))
        assertNull(HostingPolicy.checkResponse("", "image/png", icy = false))
        assertEquals("jpg", HostingPolicy.extensionFor("", "image/jpeg"))
        assertEquals("webp", HostingPolicy.extensionFor("webp", "image/png"))
        assertEquals("image/svg+xml", HostingPolicy.contentTypeFor("svg"))
        assertEquals("application/octet-stream", HostingPolicy.contentTypeFor("schem"))
    }

    @Test
    fun baseUrlMustBeShortAndPlain() {
        assertTrue(HostingSettings.validBaseUrl("https://signs.example.net"))
        assertTrue(HostingSettings.validBaseUrl("https://example.net/m"))
        assertFalse(HostingSettings.validBaseUrl("ftp://example.net"))
        assertFalse(HostingSettings.validBaseUrl("https://u@example.net"))
        assertFalse(HostingSettings.validBaseUrl("https://example.net/?a=1"))
        assertFalse(HostingSettings.validBaseUrl("https://example.net/a|b"))
        assertFalse(HostingSettings.validBaseUrl("https://example.net/" + "a".repeat(100)))
    }

    @Test
    fun rangeParsing() {
        assertNull(HostingServer.parseRange(null, 100))
        assertNull(HostingServer.parseRange("bytes=0-1,5-6", 100))
        assertEquals(0L to 9L, HostingServer.parseRange("bytes=0-9", 100))
        assertEquals(90L to 99L, HostingServer.parseRange("bytes=90-", 100))
        assertEquals(90L to 99L, HostingServer.parseRange("bytes=-10", 100))
        assertEquals(0L to 99L, HostingServer.parseRange("bytes=-1000", 100))
        assertEquals(50L to 99L, HostingServer.parseRange("bytes=50-5000", 100))
        assertEquals(-1L to -1L, HostingServer.parseRange("bytes=100-", 100))
        assertEquals(-1L to -1L, HostingServer.parseRange("bytes=9-3", 100))
    }

    @Test
    fun storeRoundTripsAndRejectsBadData(@TempDir dir: Path) {
        val store = HostedStore()
        val f = HostedStore.HostedFile("abcdefgh2345", "png", 10, 1, 2)
        f.sources.add("https://a.example/a.png")
        f.origins["world:1:2:3"] = "https://a.example/a.png"
        store.add(f)
        store.candidates["https://b.example/b.png"] = 5
        store.failures["https://c.example/c.m3u8"] = HostedStore.Failure(HostingPolicy.Reject.STREAM, 9)
        store.rewrites["world:1:2:3"] = HostedStore.Rewrite("world", 1, 2, 3, "https://a.example/a.png", "https://h.example/abcdefgh2345.png")
        store.denied.add("https://d.example/d.png")
        val file = dir.resolve("hosting.json")
        store.save(file)

        val loaded = HostedStore()
        loaded.load(file)
        assertEquals("abcdefgh2345", loaded.bySource["https://a.example/a.png"])
        assertEquals("https://a.example/a.png", loaded.files["abcdefgh2345"]!!.origins["world:1:2:3"])
        assertEquals(5L, loaded.candidates["https://b.example/b.png"])
        assertEquals(HostingPolicy.Reject.STREAM, loaded.failures["https://c.example/c.m3u8"]!!.reason)
        assertEquals("https://h.example/abcdefgh2345.png", loaded.rewrites["world:1:2:3"]!!.to)
        assertTrue("https://d.example/d.png" in loaded.denied)

        java.nio.file.Files.writeString(file, java.nio.file.Files.readString(file).replace("abcdefgh2345", "../../etc/pw"))
        val bad = HostedStore()
        org.junit.jupiter.api.assertThrows<java.io.IOException> { bad.load(file) }
        assertTrue(bad.files.isEmpty())
    }
}
