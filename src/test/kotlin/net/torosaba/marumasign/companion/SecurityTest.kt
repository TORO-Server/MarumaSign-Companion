package net.torosaba.marumasign.companion

import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.net.InetAddress
import java.net.URI
import java.net.UnknownHostException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.logging.Logger

/** 公開サイトに出すための安全対策の確認 */
class SecurityTest {

    // ---- DNS で引いたアドレスの確認 ----

    @Test
    fun nonPublicAddressesAreRejected() {
        for (s in listOf("127.0.0.1", "10.1.2.3", "172.16.0.1", "172.31.255.255", "192.168.1.50", "169.254.169.254",
            "100.64.0.1", "100.127.255.255", "0.0.0.0", "224.0.0.1", "255.255.255.255", "240.0.0.1", "192.0.2.1",
            "198.18.0.1", "198.51.100.5", "203.0.113.9", "::1", "::", "fe80::1", "fc00::1", "fd12:3456::1",
            "2001:db8::1", "2002:c0a8:0101::1", "64:ff9b::c0a8:1", "::ffff:192.168.0.1", "::ffff:127.0.0.1", "ff02::1")) {
            assertFalse(HostGuard.isPublicAddress(ip(s)), s)
        }
    }

    @Test
    fun publicAddressesAreAccepted() {
        for (s in listOf("8.8.8.8", "1.1.1.1", "93.184.216.34", "172.15.0.1", "172.32.0.1", "100.63.0.1", "100.128.0.1",
            "2606:4700:4700::1111", "2a00:1450:4001:81b::200e")) {
            assertTrue(HostGuard.isPublicAddress(ip(s)), s)
        }
    }

    @Test
    fun hostGuardFailsClosedUntilVerified() {
        val done = CountDownLatch(1)
        val guard = HostGuard(LOG, true, { done.countDown() }) { host ->
            when (host) {
                "good.example" -> arrayOf(ip("93.184.216.34"))
                "mixed.example" -> arrayOf(ip("93.184.216.34"), ip("192.168.0.1"))
                "rebind.example" -> arrayOf(ip("127.0.0.1"))
                else -> throw UnknownHostException(host)
            }
        }
        guard.start()
        try {
            // 確かめる前は、公開と分かっていないので false
            assertFalse(guard.isPublic("good.example"))
            assertFalse(guard.isPublic("mixed.example"))
            assertFalse(guard.isPublic("rebind.example"))
            assertFalse(guard.isPublic("nxdomain.example"))
            assertTrue(done.await(10, TimeUnit.SECONDS))
            assertTrue(guard.isPublic("good.example"))
            assertTrue(guard.isPublic("GOOD.example"))
            // 1 つでも公開でないアドレスを含めば除く
            assertFalse(guard.isPublic("mixed.example"))
            assertFalse(guard.isPublic("rebind.example"))
            assertFalse(guard.isPublic("nxdomain.example"))
            assertFalse(guard.isPublic(""))
            assertFalse(guard.isPublic(null))
        } finally {
            guard.stop()
        }
    }

    @Test
    fun disabledGuardAllowsEverything() {
        assertTrue(HostGuard(LOG, false) {
        }.isPublic("anything.example"))
    }

    // ---- 短縮 URL の行き先 ----

    private fun resolver(dir: Path, replies: Map<String, ShortLinkResolver.Reply>, asked: MutableList<String>): ShortLinkResolver {
        return ShortLinkResolver(LOG, UrlFilter(listOf()), setOf("is.gd", "x.gd"), dir.resolve("s.json"), {
        }, { uri ->
            asked.add(uri.toString())
            replies[uri.toString()] ?: throw IOException("unexpected request $uri")
        }, 0)
    }

    @Test
    fun resolverOnlyConnectsToShortLinkHosts(@TempDir dir: Path) {
        val asked = ArrayList<String>()
        val replies = mapOf(
            "https://is.gd/abc" to ShortLinkResolver.Reply(301, "https://i.imgur.com/x.png"),
            "https://is.gd/chain" to ShortLinkResolver.Reply(302, "https://x.gd/next"),
            "https://x.gd/next" to ShortLinkResolver.Reply(302, "https://example.com/a.png"),
            "https://is.gd/private" to ShortLinkResolver.Reply(302, "http://192.168.0.1/a.png"),
            "https://is.gd/local" to ShortLinkResolver.Reply(302, "http://localhost/a.png"),
            "https://is.gd/js" to ShortLinkResolver.Reply(302, "javascript:alert(1)"),
            "https://is.gd/loop" to ShortLinkResolver.Reply(302, "https://is.gd/loop"),
            "https://is.gd/relative" to ShortLinkResolver.Reply(302, "/other"),
            "https://is.gd/other" to ShortLinkResolver.Reply(302, "https://example.org/z.jpg"),
            "https://is.gd/gone" to ShortLinkResolver.Reply(404, null),
            "https://is.gd/nolocation" to ShortLinkResolver.Reply(302, null))
        val r = resolver(dir, replies, asked)

        assertEquals("https://i.imgur.com/x.png", r.resolve("https://is.gd/abc"))
        // 次も短縮サービスなら追う。行き先が短縮サービスでなければ、そこにはつながない
        assertEquals("https://example.com/a.png", r.resolve("https://is.gd/chain"))
        assertFalse(asked.stream().anyMatch { u -> u.contains("example.com") || u.contains("imgur") }, asked.toString())
        assertNull(r.resolve("https://is.gd/private"))
        assertNull(r.resolve("https://is.gd/local"))
        assertNull(r.resolve("https://is.gd/js"))
        assertNull(r.resolve("https://is.gd/loop")) // 転送は 3 回まで
        assertEquals("https://example.org/z.jpg", r.resolve("https://is.gd/relative"))
        assertNull(r.resolve("https://is.gd/gone"))
        assertNull(r.resolve("https://is.gd/nolocation"))
        // 短縮サービスでないものにはつながない
        asked.clear()
        assertNull(r.resolve("https://example.com/a.png"))
        assertNull(r.resolve("http://is.gd/abc")) // http は不可
        assertNull(r.resolve("https://is.gd:8443/abc")) // ポートも不可
        assertNull(r.resolve("https://evil.example/https://is.gd/abc"))
        assertTrue(asked.isEmpty(), asked.toString())
        assertFalse(r.isShort("https://is.gd.evil.example/abc"))
        assertFalse(r.isShort("https://user@is.gd/abc"))
        assertTrue(r.isShort("https://IS.GD/abc"))
    }

    @Test
    fun resolverCachePersistsAndIsRevalidatedOnLoad(@TempDir dir: Path) {
        val file = dir.resolve("s.json")
        Files.writeString(file, """
            {"version":1,"links":{"https://is.gd/ok":["https://i.imgur.com/ok.png",1],
            "https://is.gd/bad":["http://192.168.0.1/x.png",1],"https://is.gd/failed":["",1]}}""".trimIndent(), StandardCharsets.UTF_8)
        val r = ShortLinkResolver(LOG, UrlFilter(listOf()), setOf("is.gd"), file) {
        }
        r.load()
        assertEquals("https://i.imgur.com/ok.png", r.resolved("https://is.gd/ok"))
        // 保存したファイルが書き換えられていても、使う前にもう一度 URL の確認を通す
        assertNull(r.resolved("https://is.gd/bad"))
        assertNull(r.resolved("https://is.gd/failed"))
    }

    // ---- 3D 表示のデータ ----

    private val settings = Settings()
    private val html = MarkerHtml(UrlFilter(listOf()), settings)

    private fun view(url: String, orient: Int): MarkerHtml.View {
        return html.classify(SignRecord(10, 64, -20, url, floatArrayOf(0f, 1f, 0f, 2f, 3f, 0f, 90f, 0f), orient))!!
    }

    @Test
    fun imageUrlsAreHttpsBrowserImagesWithOrientationOnly() {
        assertEquals("https://example.com/a.png", WebExporter.imageUrl(view("https://example.com/a.png", 0)))
        assertEquals("https://example.com/a.webp", WebExporter.imageUrl(view("https://example.com/a.webp", 19)))
        assertNull(WebExporter.imageUrl(view("http://example.com/a.png", 0)), "http は 3D の画像にしない")
        assertNull(WebExporter.imageUrl(view("https://example.com/a.png", Orient.NONE)), "向きが分からない看板は描かない")
        assertNull(WebExporter.imageUrl(view("https://example.com/a.mp4", 0)))
        assertNull(WebExporter.imageUrl(view("https://example.com/a.pdf", 0)))
        assertNull(WebExporter.imageUrl(view("https://example.com/a.svg", 0)), "svg はテクスチャにしない (スクリプトを含みうる)")
        assertEquals("https://i.imgur.com/AbCdE12.png", WebExporter.imageUrl(view("https://imgur.com/AbCdE12.png", 3)))
        assertEquals("https://www.example.com/AbCdE12.png", WebExporter.rewriteImageHost("https://www.example.com/AbCdE12.png"))
    }

    @Test
    fun exportedJsonIsWellFormedAndEscaped(@TempDir dir: Path) {
        val views = listOf(
            view("https://example.com/a.png?x=1&y=%3C2%3E&z=%26", 4),
            view("https://example.com/b.mp4", 17))
        val file = dir.resolve("marumasign").resolve("overworld.json")
        WebExporter.write(file, views, settings)
        val text = Files.readString(file, StandardCharsets.UTF_8)
        assertFalse(text.contains("<") || text.contains(">"), "HTML として解釈されうる文字を含まない")
        val root = JsonParser.parseString(text).asJsonObject
        assertEquals(WebExporter.FORMAT, root.get("format").asInt)
        assertEquals(MediaKind.values().size, root.getAsJsonArray("kinds").size())
        val signs = root.getAsJsonArray("signs")
        assertEquals(2, signs.size())
        val first = signs.get(0).asJsonArray
        assertEquals(15, first.size())
        assertEquals(10, first.get(0).asInt)
        assertEquals(4, first.get(3).asInt)
        assertEquals(2f, first.get(10).asFloat)
        assertEquals("", signs.get(1).asJsonArray.get(5).asString, "動画には画像の URL を付けない")
        assertFalse(Files.exists(file.resolveSibling("overworld.json.tmp")))
    }

    // ---- Web 側のスクリプト ----

    @Test
    fun scriptNeverBuildsHtmlOrRunsCode() {
        val js: String
        SecurityTest::class.java.getResourceAsStream("/web/marumasign3d.js").use { input ->
            assertNotNull(input)
            js = String(input!!.readAllBytes(), StandardCharsets.UTF_8)
        }
        // コメントを除いて確かめる
        val code = js.replace(Regex("(?s)/\\*.*?\\*/"), "").replace(Regex("(?m)//.*\$"), "")
        for (forbidden in listOf("innerHTML", "outerHTML", "insertAdjacentHTML", "document.write", "eval(", "new Function",
            "setTimeout(\"", "setInterval(\"", "createContextualFragment", "srcdoc", "javascript:", "localStorage", "document.cookie",
            "XMLHttpRequest", "WebSocket", "postMessage", "importScripts")) {
            assertFalse(code.contains(forbidden), forbidden)
        }
        // 外へ出る通信は、画像の fetch (CORS・Cookie なし・Referer なし・転送を追わない) と、同じ場所のデータだけ
        assertTrue(code.contains("credentials: \"omit\""))
        assertTrue(code.contains("referrerPolicy: \"no-referrer\""))
        assertTrue(code.contains("redirect: \"error\""))
        assertTrue(code.contains("mode: \"cors\""))
    }

    @Test
    fun scriptResourceMatchesInstallPath() {
        assertNotNull(BlueMapBackend::class.java.getResource(BlueMapBackend.SCRIPT_RESOURCE))
        assertEquals("js/marumasign3d.js", BlueMapBackend.SCRIPT_PATH)
        assertEquals(URI.create("js/marumasign3d.js").path, BlueMapBackend.SCRIPT_PATH)
    }

    companion object {
        private val LOG: Logger = Logger.getLogger("test")

        private fun ip(s: String): InetAddress {
            return InetAddress.getByName(s)
        }
    }
}
