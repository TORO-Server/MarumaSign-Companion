package net.torosaba.marumasign.companion

import com.google.gson.JsonParser
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** ホストのホスト別の上限と、3D 表示のデータにホスト先の画像を使うこと */
internal class HostedImageTest {

    @Test
    fun hostRuleCanRaiseTheHourlyLimitForOneHost() {
        val c = LinkSettings()
        c.hostPerHourLimit = 2
        c.hostMinIntervalSeconds = 0
        c.hostRules = ArrayList(listOf(LinkSettings.HostRule("imgur.com", 0, listOf(), 5)))
        assertEquals(5, c.hostLimit("i.imgur.com"), "サブドメインにも当てはまる")
        assertEquals(2, c.hostLimit("example.com"), "決まりのないホストは全体の既定")
        val t = HostThrottle(c)
        for (i in 0 until 5) assertTrue(t.reserve("i.imgur.com", 1000L + i) >= 0, "imgur は 5 回まで")
        assertEquals(-1, t.reserve("i.imgur.com", 1010), "6 回目は止める (数えるのはホスト名ごと)")
        assertTrue(t.reserve("example.com", 1000) >= 0)
        assertTrue(t.reserve("example.com", 1001) >= 0)
        assertEquals(-1, t.reserve("example.com", 1002), "ほかのホストは 2 回まで")
    }

    @Test
    fun ruleWithoutPerHourLimitUsesTheGlobalOne() {
        val c = LinkSettings()
        c.hostPerHourLimit = 7
        c.hostRules = ArrayList(listOf(LinkSettings.HostRule("imgur.com", 5, listOf("/removed.png"))))
        assertEquals(7, c.hostLimit("imgur.com"))
    }

    private fun view(url: String, ext: String, kind: MediaKind): MarkerHtml.View =
        MarkerHtml.View(SignRecord(1, 64, 2, url, null, 17), url, kind, ext)

    private fun export(dir: Path, views: List<MarkerHtml.View>, hosted: (MarkerHtml.View) -> String?): List<List<String>> {
        val file = dir.resolve("m.json")
        WebExporter.write(file, views, Settings(), hosted)
        val signs = JsonParser.parseString(Files.readString(file)).asJsonObject.getAsJsonArray("signs")
        return signs.map { s -> val a = s.asJsonArray; listOf(a[5].asString, a[6].asString) }
    }

    @Test
    fun imageComesFromTheHostedCopyButTheLinkStaysTheSigns(@TempDir dir: Path) {
        val v = view("https://imgur.com/abcde12.png", "png", MediaKind.IMAGE)
        val out = export(dir, listOf(v)) { "https://assets.torosaba.net/aaaaaaaaaaaa.png" }
        assertEquals("https://assets.torosaba.net/aaaaaaaaaaaa.png", out[0][0])
        assertEquals("https://imgur.com/abcde12.png", out[0][1], "リンクは看板のまま (ホスト先は閲覧者が直接開けない)")
    }

    @Test
    fun withoutHostedCopyTheOriginalImageIsUsed(@TempDir dir: Path) {
        val v = view("https://imgur.com/abcde12.png", "png", MediaKind.IMAGE)
        val out = export(dir, listOf(v)) { null }
        assertEquals("https://i.imgur.com/abcde12.png", out[0][0], "imgur のページの URL は画像のホストにする")
    }

    @Test
    fun notAnImageKindNeverGetsAHostedImage(@TempDir dir: Path) {
        val v = view("https://example.com/clip.mp4", "mp4", MediaKind.of("mp4"))
        var asked = false
        val out = export(dir, listOf(v)) { asked = true; "https://assets.torosaba.net/bbbbbbbbbbbb.mp4" }
        assertEquals("", out[0][0])
        assertTrue(!asked, "画像として描かない種類には、ホスト先を聞かない")
    }

    @Test
    fun textureExtensions() {
        assertTrue(WebExporter.isTextureExtension("PNG"))
        assertTrue(WebExporter.isTextureExtension("webp"))
        assertTrue(!WebExporter.isTextureExtension("mp4"))
        assertTrue(!WebExporter.isTextureExtension("svg"))
    }
}
