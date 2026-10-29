package net.torosaba.marumasign.companion

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

internal class CoreTest {

    private val filter = UrlFilter(listOf("blocked.example"))
    private val settings = Settings()
    private val html = MarkerHtml(filter, settings)

    // ---- MarumaSpec ----

    @Test
    fun parsesMarumaSignText() {
        val s = MarumaSpec.parse("https://example.com/a.png|0|1.5|-2|4|3|0|90|0")
        assertNotNull(s)
        assertEquals("https://example.com/a.png", s!!.url)
        assertEquals(1.5f, s.values[1])
        assertNull(MarumaSpec.parse("hello"))
        assertNull(MarumaSpec.parse("https://example.com/a.png|0|0|0|1|1|0|0"))
        assertNull(MarumaSpec.parse("https://example.com/a.png|0|0|0|NaN|1|0|0|0"))
        assertNull(MarumaSpec.parse("https://example.com/a.png|0|0|0|Infinity|1|0|0|0"))
        assertEquals(1000f, MarumaSpec.parse("https://example.com/a.png|99999|0|0|1|1|0|0|0")!!.values[0])
    }

    // ---- UrlFilter ----

    @Test
    fun allowsPublicHttpUrls() {
        assertTrue(filter.isAllowed("https://example.com/a.png"))
        assertTrue(filter.isAllowed("http://sub.example.com:8080/a.png?x=1#y"))
        assertTrue(filter.isAllowed("https://8.8.8.8/a.png"))
        assertTrue(filter.isAllowed("https://is.gd/Z4Is5l"))
    }

    @Test
    fun rejectsUnsafeUrls() {
        for (u in listOf(
            "file:///etc/passwd", "ftp://example.com/a.png", "javascript:alert(1)", "data:text/html,<script>1</script>",
            "http://localhost/a.png", "http://LOCALHOST./a.png", "http://foo.localhost/a.png", "http://127.0.0.1/a.png",
            "http://127.1/a.png", "http://2130706433/a.png", "http://0x7f.0.0.1/a.png", "http://0177.0.0.1/a.png",
            "http://10.0.0.5/a.png", "http://172.16.0.1/a.png", "http://172.31.255.1/a.png", "http://192.168.1.50/a.png",
            "http://169.254.169.254/latest/meta-data", "http://100.64.0.1/a.png", "http://0.0.0.0/a.png",
            "http://[::1]/a.png", "http://[::ffff:127.0.0.1]/a.png", "http://[fe80::1]/a.png", "http://[fd00::1]/a.png",
            "http://printer.local/a.png", "http://nas.lan/a.png", "http://router/a.png", "http://example.com:22/a.png",
            "http://user@example.com/a.png", "https://example.com/a b.png", "https://example.com/\"onerror=alert(1)",
            "https://blocked.example/a.png", "https://x.blocked.example/a.png", "https://example.com/<script>",
            "", "https://", "https://example.com/" + "a".repeat(2100))) {
            assertFalse(filter.isAllowed(u), u)
        }
        assertNull(filter.parse(null))
    }

    // ---- MarkerHtml ----

    @Test
    fun classifiesByExtension() {
        assertEquals(MediaKind.IMAGE, view("https://example.com/a.PNG").kind)
        assertEquals(MediaKind.VIDEO, view("https://example.com/a.mp4?x=1").kind)
        assertEquals(MediaKind.AUDIO, view("https://example.com/a.mp3").kind)
        assertEquals(MediaKind.MODEL, view("https://example.com/a.glb").kind)
        assertEquals(MediaKind.SPLAT, view("https://example.com/a.ply").kind)
        assertEquals(MediaKind.PDF, view("https://example.com/a.pdf#page=3").kind)
        assertEquals(MediaKind.STRUCTURE, view("https://example.com/a.litematic").kind)
        assertEquals(MediaKind.OTHER, view("https://is.gd/abc").kind)
        assertNull(html.classify(SignRecord(0, 0, 0, "https://example.com/a.exe")))
        settings.allowNoExtension = false
        assertNull(html.classify(SignRecord(0, 0, 0, "https://is.gd/abc")))
        settings.allowNoExtension = true
    }

    @Test
    fun imageMarkerHasSafeAttributes() {
        val h = html.html(view("https://example.com/a.png?x=1&y=2"))
        assertTrue(h.contains("<img src=\"https://example.com/a.png?x=1&amp;y=2\""))
        assertTrue(h.contains("referrerpolicy=\"no-referrer\""))
        assertTrue(h.contains("loading=\"lazy\""))
        assertTrue(h.contains("rel=\"noopener noreferrer nofollow\""))
        assertTrue(h.contains("target=\"_blank\""))
    }

    @Test
    fun nonImageMarkerHasIconAndLink() {
        val h = html.html(view("https://example.com/movie.mp4"))
        assertFalse(h.contains("<img"))
        assertTrue(h.contains(MediaKind.VIDEO.icon))
        assertTrue(h.contains("href=\"https://example.com/movie.mp4\""))
        assertTrue(h.contains("rel=\"noopener noreferrer nofollow\""))
    }

    @Test
    fun htmlNeverContainsRawSignText() {
        // フィルターを通った URL でも、& ' ` などはエスケープされる
        val h = html.html(MarkerHtml.View(SignRecord(0, 0, 0, "x"),
            "https://example.com/a.png?q='><b>&x=`", MediaKind.IMAGE, "png"))
        assertFalse(h.contains("<b>"))
        assertFalse(h.contains("'>"))
        assertEquals("&lt;script&gt;&amp;&quot;&#39;&#96;", MarkerHtml.escape("<script>&\"'`"))
        assertFalse(html.label(view("https://example.com/a.png")).contains("<"))
    }

    @Test
    fun shortensLongUrls() {
        val s = MarkerHtml.shorten("https://example.com/" + "a".repeat(200), 36)
        assertEquals(36, s.length)
        assertTrue(s.startsWith("example.com/"))
    }

    private fun view(url: String): MarkerHtml.View {
        val v = html.classify(SignRecord(1, 2, 3, url))
        assertNotNull(v, url)
        return v!!
    }

    // ---- NBT ----

    @Test
    fun extractsPlainStringMessages() {
        val signs = SignTextExtractor.extract(chunkNbt("minecraft:sign") { o -> strings(o, "https://a.b/c.png|0|0|", "0|1|1|0|0|0", "", "") })
        assertEquals(1, signs.size)
        assertEquals(-10, signs[0].x)
        assertEquals(700, signs[0].z)
        assertEquals("https://a.b/c.png|0|0|0|1|1|0|0|0", signs[0].text)
        assertNotNull(MarumaSpec.parse(signs[0].text))
    }

    @Test
    fun extractsJsonStringMessages() {
        val signs = SignTextExtractor.extract(chunkNbt("minecraft:hanging_sign") { o -> strings(o,
            "{\"text\":\"https://a.b/\",\"extra\":[{\"text\":\"c.png|0|0\"}]}", "\"0|1|1|0|0|0\"", "[\"\"]", "{\"text\":\"\"}") })
        assertEquals(1, signs.size)
        assertEquals("https://a.b/c.png|0|00|1|1|0|0|0", signs[0].text)
    }

    @Test
    fun extractsCompoundMessages() {
        val signs = SignTextExtractor.extract(chunkNbt("minecraft:sign") { o ->
            try {
                o.writeByte(10)
                o.writeInt(2)
                for (t in arrayOf("https://a.b/c.png|", "0|0|0|1|1|0|0|0")) {
                    o.writeByte(8)
                    o.writeUTF("text")
                    o.writeUTF(t)
                    o.writeByte(0)
                }
            } catch (e: IOException) {
                throw RuntimeException(e)
            }
        })
        assertEquals("https://a.b/c.png|0|0|0|1|1|0|0|0", signs[0].text)
    }

    // ---- SignStore ----

    @Test
    fun liveChangesSurviveStaleScanAndCacheRoundTrips(@TempDir dir: Path) {
        val store = SignStore()
        store.applyScan(0, 0, 1000, listOf(SignRecord(5, 64, 5, "https://a.b/1.png")))
        assertEquals(1, store.count())

        store.liveSet(SignRecord(6, 64, 6, "https://a.b/2.png"))
        assertEquals(2, store.count())
        // ファイルが古いまま (更新日時がゲーム中の変更より前) 再走査されても、ゲーム中の追加は残る
        store.applyScan(0, 0, 1000, listOf(SignRecord(5, 64, 5, "https://a.b/1.png")))
        assertEquals(2, store.count())

        assertTrue(store.liveRemove(5, 64, 5))
        store.applyScan(0, 0, 1000, listOf(SignRecord(5, 64, 5, "https://a.b/1.png")))
        assertEquals(1, store.count())

        val file = dir.resolve("cache").resolve("w.json")
        store.save(file)
        val loaded = SignStore()
        loaded.load(file)
        assertEquals(1000L, loaded.mtimeOf(0, 0))
        assertEquals(1, loaded.count())
        assertEquals("https://a.b/2.png", loaded.all()[0].url)

        // ファイルが新しくなれば、ファイルの内容が正になる
        store.applyScan(0, 0, System.currentTimeMillis() + 10_000, listOf())
        assertEquals(0, store.count())
        assertEquals(Long.MIN_VALUE, store.mtimeOf(7, 7))
    }

    @Test
    fun brokenCacheIsReportedAndIgnored(@TempDir dir: Path) {
        val file = dir.resolve("bad.json")
        Files.writeString(file, "{not json")
        assertThrows(IOException::class.java) { SignStore().load(file) }
    }

    @Test
    fun parsesRegionNames() {
        assertArrayEquals(intArrayOf(-3, 12), RegionReader.parseRegionName("r.-3.12.mca"))
        assertNull(RegionReader.parseRegionName("r.a.b.mca"))
        assertNull(RegionReader.parseRegionName("c.1.2.mcc"))
    }

    companion object {
        private fun chunkNbt(idStr: String, messages: (DataOutputStream) -> Unit): ByteArray {
            val bos = ByteArrayOutputStream()
            val o = DataOutputStream(bos)
            o.writeByte(10)
            o.writeUTF("")
            o.writeByte(7) // 読み飛ばされるバイト配列
            o.writeUTF("junk")
            o.writeInt(3)
            o.write(byteArrayOf(1, 2, 3))
            o.writeByte(9) // block_entities
            o.writeUTF("block_entities")
            o.writeByte(10)
            o.writeInt(2)
            // chest (看板ではない)
            o.writeByte(8)
            o.writeUTF("id")
            o.writeUTF("minecraft:chest")
            o.writeByte(3)
            o.writeUTF("x")
            o.writeInt(1)
            o.writeByte(3)
            o.writeUTF("y")
            o.writeInt(2)
            o.writeByte(3)
            o.writeUTF("z")
            o.writeInt(3)
            o.writeByte(11)
            o.writeUTF("arr")
            o.writeInt(2)
            o.writeInt(5)
            o.writeInt(6)
            o.writeByte(0)
            // sign
            o.writeByte(8)
            o.writeUTF("id")
            o.writeUTF(idStr)
            o.writeByte(3)
            o.writeUTF("x")
            o.writeInt(-10)
            o.writeByte(3)
            o.writeUTF("y")
            o.writeInt(64)
            o.writeByte(3)
            o.writeUTF("z")
            o.writeInt(700)
            o.writeByte(10)
            o.writeUTF("front_text")
            o.writeByte(8)
            o.writeUTF("color")
            o.writeUTF("black")
            o.writeByte(9)
            o.writeUTF("messages")
            messages(o)
            o.writeByte(0)
            o.writeByte(0)
            o.writeByte(0)
            return bos.toByteArray()
        }

        private fun strings(o: DataOutputStream, vararg lines: String) {
            try {
                o.writeByte(8)
                o.writeInt(lines.size)
                for (l in lines) o.writeUTF(l)
            } catch (e: IOException) {
                throw RuntimeException(e)
            }
        }
    }
}
