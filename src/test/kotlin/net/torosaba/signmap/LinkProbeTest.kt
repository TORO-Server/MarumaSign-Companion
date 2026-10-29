package net.torosaba.signmap

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** リンク切れの判定 (ローカルのサーバーに対して、実際に HEAD / GET を送って確かめる) */
class LinkProbeTest {

    fun interface Route {
        @Throws(IOException::class)
        fun handle(method: String, ex: HttpExchange)
    }

    /** ループバックの HTTP サーバー。経路ごとに応答を決め、受けたリクエストを覚える */
    class Site {
        val server: HttpServer = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        val port: Int = server.address.port
        val hits: MutableList<String> = CopyOnWriteArrayList()

        init {
            server.executor = Executors.newCachedThreadPool { r ->
                val t = Thread(r)
                t.isDaemon = true
                t
            }
            server.start()
        }

        fun on(path: String, r: Route): Site {
            server.createContext(path) { ex ->
                hits.add(ex.requestMethod + " " + ex.requestURI.path)
                try {
                    r.handle(ex.requestMethod, ex)
                } finally {
                    ex.close()
                }
            }
            return this
        }

        fun count(prefix: String): Long {
            return hits.stream().filter { h -> h.startsWith(prefix) }.count()
        }
    }

    private val sites = ArrayList<Site>()

    @AfterEach
    fun stopSites() {
        for (s in sites) s.server.stop(0)
    }

    private fun site(): Site {
        val s = Site()
        sites.add(s)
        return s
    }

    private val now = AtomicLong(1_000_000_000L)
    private val sleeps: MutableList<Long> = CopyOnWriteArrayList()

    private fun cfg(): LinkSettings {
        val c = LinkSettings()
        c.hostMinIntervalSeconds = 0
        c.hostRules = ArrayList()
        return c
    }

    private fun probe(cfg: LinkSettings, totalMillis: Int, ports: Set<Int>?, table: Map<String, Array<InetAddress>>): LinkProbe {
        val o = SafeHttp.Options()
        o.allowNonPublicForTests = true
        o.totalTimeoutMillis = totalMillis
        o.connectTimeoutMillis = 2000
        o.resolver = SafeHttp.fixedResolver(table)
        return LinkProbe(SafeHttp(o), UrlFilter(listOf()), cfg, HostThrottle(cfg), ports, { now.get() }, { sleeps.add(it) })
    }

    private fun probe(vararg s: Site): LinkProbe {
        val ports = HashSet<Int>()
        for (x in s) ports.add(x.port)
        return probe(cfg(), 5000, ports, mapOf(
            "site.example" to arrayOf(LOOPBACK),
            "other.example" to arrayOf(LOOPBACK),
            "third.example" to arrayOf(LOOPBACK)))
    }

    // ---- 基本 ----

    @Test
    fun okWhenHeadIsOk() {
        val s = site().on("/a.png") { _, ex -> reply(ex, 200, "image/png", null) }
        val r = probe(s).probe(url(s, "/a.png"))
        assertResult(r, LinkProbe.Verdict.OK, LinkReason.OK)
        assertEquals(200, r.httpCode)
        assertEquals(0L, s.count("GET"), "HEAD で足りるなら GET は送らない")
    }

    @Test
    fun notFoundAndGoneAreBroken() {
        val s = site().on("/nf") { _, ex -> reply(ex, 404, "text/html", null) }.on("/gone") { _, ex -> reply(ex, 410, null, null) }
        assertResult(probe(s).probe(url(s, "/nf")), LinkProbe.Verdict.BROKEN, LinkReason.HTTP_404)
        assertResult(probe(s).probe(url(s, "/gone")), LinkProbe.Verdict.BROKEN, LinkReason.HTTP_410)
        assertEquals(1L, s.count("GET /nf"), "404 は GET で確かめ直す")
        assertEquals(0L, s.count("GET /gone"), "410 はそのまま")
    }

    @Test
    fun headNotAllowedFallsBackToRangeGet() {
        val s = site().on("/x.png") { m, ex ->
            if (m == "HEAD") reply(ex, 405, null, null)
            else {
                assertEquals("bytes=0-1023", ex.requestHeaders.getFirst("Range"))
                reply(ex, 206, "image/png", null)
            }
        }
        assertResult(probe(s).probe(url(s, "/x.png")), LinkProbe.Verdict.OK, LinkReason.OK)
        assertEquals(1L, s.count("GET /x.png"))
    }

    @Test
    fun headNotFoundButGetOkIsOk() {
        val s = site().on("/y.png") { m, ex -> reply(ex, if (m == "HEAD") 404 else 200, "image/png", null) }
        assertResult(probe(s).probe(url(s, "/y.png")), LinkProbe.Verdict.OK, LinkReason.OK)
    }

    @Test
    fun headAndGetBothNotFoundIsBroken() {
        val s = site().on("/z.png") { _, ex -> reply(ex, 404, null, null) }
        assertResult(probe(s).probe(url(s, "/z.png")), LinkProbe.Verdict.BROKEN, LinkReason.HTTP_404)
    }

    @Test
    fun forbiddenIsUnknownUnlessConfigured() {
        val s = site().on("/f") { _, ex -> reply(ex, 403, null, null) }.on("/u") { _, ex -> reply(ex, 401, null, null) }
        val r = probe(s).probe(url(s, "/f"))
        assertResult(r, LinkProbe.Verdict.UNKNOWN, LinkReason.ACCESS_DENIED)
        assertFalse(r.countsAsFailure)
        assertResult(probe(s).probe(url(s, "/u")), LinkProbe.Verdict.UNKNOWN, LinkReason.ACCESS_DENIED)
        val c = cfg()
        c.treat403AsBroken = true
        val strict = probe(c, 5000, setOf(s.port), mapOf("site.example" to arrayOf(LOOPBACK)))
        assertResult(strict.probe(url(s, "/f")), LinkProbe.Verdict.BROKEN, LinkReason.ACCESS_DENIED)
        assertResult(strict.probe(url(s, "/u")), LinkProbe.Verdict.UNKNOWN, LinkReason.ACCESS_DENIED)
    }

    @Test
    fun serverErrorsAndOddStatusAreUnknownAndCount() {
        val s = site().on("/e500") { _, ex -> reply(ex, 500, null, null) }.on("/e503") { _, ex -> reply(ex, 503, null, null) }
            .on("/e418") { _, ex -> reply(ex, 418, null, null) }
        val r = probe(s).probe(url(s, "/e500"))
        assertResult(r, LinkProbe.Verdict.UNKNOWN, LinkReason.SERVER_ERROR)
        assertTrue(r.countsAsFailure)
        assertResult(probe(s).probe(url(s, "/e503")), LinkProbe.Verdict.UNKNOWN, LinkReason.SERVER_ERROR)
        assertResult(probe(s).probe(url(s, "/e418")), LinkProbe.Verdict.UNKNOWN, LinkReason.UNEXPECTED_RESPONSE)
    }

    @Test
    fun rateLimitedMakesTheHostWaitAndIsNotAFailure() {
        val s = site().on("/r") { _, ex ->
            ex.responseHeaders.add("Retry-After", "120")
            reply(ex, 429, null, null)
        }
        val c = cfg()
        val throttle = HostThrottle(c)
        val o = SafeHttp.Options()
        o.allowNonPublicForTests = true
        o.resolver = SafeHttp.fixedResolver(mapOf("site.example" to arrayOf(LOOPBACK)))
        val p = LinkProbe(SafeHttp(o), UrlFilter(listOf()), c, throttle, setOf(s.port), { now.get() }, { sleeps.add(it) })
        val r = p.probe(url(s, "/r"))
        assertResult(r, LinkProbe.Verdict.UNKNOWN, LinkReason.RATE_LIMITED)
        assertFalse(r.countsAsFailure)
        assertEquals(120_000L, r.retryAfterMillis)
        assertFalse(throttle.isReady("site.example", now.get() + 60_000), "Retry-After のあいだは、そのホストを待たせる")
        assertTrue(throttle.isReady("site.example", now.get() + 130_000))
        assertEquals(0L, s.count("GET"), "429 のホストに、確かめ直しの GET は送らない")
    }

    @Test
    fun timeoutIsUnknownAndCounts() {
        val s = site().on("/slow") { _, ex ->
            try {
                Thread.sleep(3000)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            }
            reply(ex, 200, null, null)
        }
        val c = cfg()
        val p = probe(c, 1000, setOf(s.port), mapOf("site.example" to arrayOf(LOOPBACK)))
        val t0 = System.nanoTime()
        val r = p.probe(url(s, "/slow"))
        assertResult(r, LinkProbe.Verdict.UNKNOWN, LinkReason.TIMEOUT)
        assertTrue(r.countsAsFailure)
        assertTrue((System.nanoTime() - t0) / 1_000_000 < 3500)
    }

    // ---- 転送 ----

    @Test
    fun followsRedirects() {
        val s = site()
            .on("/ok") { _, ex -> reply(ex, 301, null, "/final.png") }
            .on("/final.png") { _, ex -> reply(ex, 200, "image/png", null) }
            .on("/dead") { _, ex -> reply(ex, 302, null, "/missing.png") }
            .on("/missing.png") { _, ex -> reply(ex, 404, null, null) }
            .on("/abs") { _, ex -> reply(ex, 307, null, "http://other.example:" + ex.localAddress.port + "/final.png") }
        assertResult(probe(s).probe(url(s, "/ok")), LinkProbe.Verdict.OK, LinkReason.OK)
        assertResult(probe(s).probe(url(s, "/dead")), LinkProbe.Verdict.BROKEN, LinkReason.HTTP_404)
        assertResult(probe(s).probe(url(s, "/abs")), LinkProbe.Verdict.OK, LinkReason.OK)
    }

    @Test
    fun redirectLoopIsBroken() {
        val s = site().on("/a") { _, ex -> reply(ex, 302, null, "/b") }.on("/b") { _, ex -> reply(ex, 302, null, "/a") }
            .on("/self") { _, ex -> reply(ex, 302, null, "/self") }
        assertResult(probe(s).probe(url(s, "/a")), LinkProbe.Verdict.BROKEN, LinkReason.REDIRECT_LOOP)
        assertResult(probe(s).probe(url(s, "/self")), LinkProbe.Verdict.BROKEN, LinkReason.REDIRECT_LOOP)
    }

    @Test
    fun tooManyRedirectsStopsAtTheLimit() {
        val s = site().on("/hop/") { _, ex ->
            val n = Integer.parseInt(ex.requestURI.path.substring("/hop/".length))
            reply(ex, 302, null, "/hop/" + (n + 1))
        }
        val r = probe(s).probe(url(s, "/hop/0"))
        assertResult(r, LinkProbe.Verdict.UNKNOWN, LinkReason.REDIRECT_LIMIT)
        assertEquals(6L, s.count("HEAD"), "最初の 1 回 + 転送 5 回まで")
    }

    @Test
    fun unsafeRedirectTargetsAreNotFollowed() {
        val s = site()
        for (target in listOf("http://127.0.0.1:" + s.port + "/x", "http://localhost:" + s.port + "/x", "http://[::1]:" + s.port + "/x",
            "http://10.0.0.5/x", "http://192.168.1.1/x", "http://169.254.169.254/latest/meta-data", "http://2130706433/x", "http://0177.0.0.1/x",
            "http://0x7f.1/x", "file:///etc/passwd", "ftp://site.example/x", "javascript:alert(1)", "gopher://site.example/",
            "http://user@site.example/x", "http://site.example:22/x", "http://site.example:" + (s.port + 1) + "/x",
            "http://site.example/a b", "http://nas.local/x", "http://router/x")) {
            val path = "/r" + Math.abs(target.hashCode())
            s.on(path) { _, ex -> reply(ex, 302, null, target) }
            val r = probe(s).probe(url(s, path))
            assertResult(r, LinkProbe.Verdict.UNKNOWN, LinkReason.REDIRECT_UNSAFE)
            assertFalse(r.countsAsFailure)
        }
        // どこにもつないでいない (最初のサーバーへの HEAD だけ)
        assertEquals(s.hits.stream().filter { h -> h.startsWith("HEAD /r") }.count(), s.hits.size.toLong())
    }

    @Test
    fun redirectToNameThatResolvesToPrivateAddressIsNotConnected() {
        val s = site().on("/start") { _, ex -> reply(ex, 302, null, "http://internal.example:" + ex.localAddress.port + "/x") }
        val opened = ArrayList<InetAddress>()
        val o = SafeHttp.Options()
        o.resolver = SafeHttp.fixedResolver(mapOf("public.example" to arrayOf(InetAddress.getByName("8.8.8.8")),
            "internal.example" to arrayOf(InetAddress.getByName("10.0.0.5"))))
        o.allowSelfConnectForTests = true // 公開に見えるアドレスへの接続を、ループバックのサーバーへ向け直しているため
        o.connector = SafeHttp.Connector { a, _, _ ->
            opened.add(a)
            Socket(LOOPBACK, s.port)
        }
        val c = cfg()
        val p = LinkProbe(SafeHttp(o), UrlFilter(listOf()), c, HostThrottle(c), setOf(s.port), { now.get() }, { sleeps.add(it) })
        val r = p.probe("http://public.example:" + s.port + "/start")
        assertResult(r, LinkProbe.Verdict.UNKNOWN, LinkReason.REDIRECT_UNSAFE)
        assertEquals(listOf(InetAddress.getByName("8.8.8.8")), opened, "内部のアドレスには、つなごうとしない")
    }

    @Test
    fun hostThatResolvesToPrivateAddressIsNotChecked() {
        val connects = AtomicInteger()
        val o = SafeHttp.Options()
        o.resolver = SafeHttp.fixedResolver(mapOf("evil.example" to arrayOf(InetAddress.getByName("127.0.0.1"))))
        o.connector = SafeHttp.Connector { _, _, _ ->
            connects.incrementAndGet()
            throw IOException()
        }
        val c = cfg()
        val p = LinkProbe(SafeHttp(o), UrlFilter(listOf()), c, HostThrottle(c), setOf(80), { now.get() }, { sleeps.add(it) })
        val r = p.probe("http://evil.example/a.png")
        assertResult(r, LinkProbe.Verdict.UNKNOWN, LinkReason.UNSAFE_URL)
        assertEquals(0, connects.get())
    }

    @Test
    fun httpsToHttpDowngradeIsJudgedButItsBodyIsNotRead(@TempDir dir: Path) {
        val tls = TestTls.create(dir, false)
        val http = site().on("/gone") { m, ex -> reply(ex, if (m == "HEAD") 404 else 200, "image/png", null) }
            .on("/ok") { _, ex -> reply(ex, 200, "image/png", null) }
        val https = tls.startServer { ex ->
            val path = ex.requestURI.path
            ex.responseHeaders.add("Location", "http://site.example:" + http.port + (if (path == "/start-gone") "/gone" else "/ok"))
            ex.sendResponseHeaders(302, -1)
            ex.close()
        }
        try {
            val tlsPort = https.address.port
            val o = SafeHttp.Options()
            o.allowNonPublicForTests = true
            o.sslContext = tls.trustingClientContext
            o.resolver = SafeHttp.fixedResolver(mapOf("site.example" to arrayOf(LOOPBACK)))
            val c = cfg()
            val p = LinkProbe(SafeHttp(o), UrlFilter(listOf()), c, HostThrottle(c), setOf(tlsPort, http.port), { now.get() }, { sleeps.add(it) })
            // ダウングレード先で HEAD が 404 のとき、GET に切り替えない (本文は読まない) ので、BROKEN のまま
            assertResult(p.probe("https://site.example:$tlsPort/start-gone"), LinkProbe.Verdict.BROKEN, LinkReason.HTTP_404)
            assertEquals(0L, http.count("GET"))
            // ダウングレード先が HEAD で OK なら、OK
            assertResult(p.probe("https://site.example:$tlsPort/start-ok"), LinkProbe.Verdict.OK, LinkReason.OK)
        } finally {
            https.stop(0)
        }
    }

    // ---- 内容 ----

    @Test
    fun htmlInsteadOfMediaIsBroken() {
        val s = site().on("/pic.png") { _, ex -> reply(ex, 200, "text/html; charset=utf-8", null) }
            .on("/clip.mp4") { _, ex -> reply(ex, 200, "text/html", null) }
            .on("/page.html") { _, ex -> reply(ex, 200, "text/html", null) }
            .on("/noext") { _, ex -> reply(ex, 200, "text/html", null) }
            .on("/ok.png") { _, ex -> reply(ex, 200, "image/png", null) }
        assertResult(probe(s).probe(url(s, "/pic.png")), LinkProbe.Verdict.BROKEN, LinkReason.HTML_INSTEAD_OF_MEDIA)
        assertResult(probe(s).probe(url(s, "/clip.mp4")), LinkProbe.Verdict.BROKEN, LinkReason.HTML_INSTEAD_OF_MEDIA)
        assertResult(probe(s).probe(url(s, "/page.html")), LinkProbe.Verdict.OK, LinkReason.OK)
        assertResult(probe(s).probe(url(s, "/noext")), LinkProbe.Verdict.OK, LinkReason.OK)
        assertResult(probe(s).probe(url(s, "/ok.png")), LinkProbe.Verdict.OK, LinkReason.OK)
    }

    @Test
    fun knownRemovedPlaceholderRedirectIsBroken() {
        val s = site().on("/gone.png") { _, ex -> reply(ex, 302, null, "/removed.png") }
            .on("/removed.png") { _, ex -> reply(ex, 200, "image/png", null) }
            .on("/fine.png") { _, ex -> reply(ex, 200, "image/png", null) }
        val c = cfg()
        c.hostRules = ArrayList(listOf(LinkSettings.HostRule("site.example", 0, listOf("/removed.png"))))
        val p = probe(c, 5000, setOf(s.port), mapOf("site.example" to arrayOf(LOOPBACK)))
        assertResult(p.probe(url(s, "/gone.png")), LinkProbe.Verdict.BROKEN, LinkReason.REMOVED_PLACEHOLDER)
        assertResult(p.probe(url(s, "/fine.png")), LinkProbe.Verdict.OK, LinkReason.OK)
        // 決まりがなければ、置き換えの画像も 200 なので OK になってしまう (だから決まりを足せるようにしてある)
        assertResult(probe(s).probe(url(s, "/gone.png")), LinkProbe.Verdict.OK, LinkReason.OK)
    }

    // ---- 接続の失敗 ----

    @Test
    fun dnsFailureConnectionRefusedAndTlsFailureAreBroken(@TempDir dir: Path) {
        val s = site()
        assertResult(probe(s).probe("http://nxdomain.example:" + s.port + "/a.png"), LinkProbe.Verdict.BROKEN, LinkReason.DNS_FAILED)
        val dead = ServerSocket(0, 1, LOOPBACK).use { tmp -> tmp.localPort }
        val p = probe(cfg(), 3000, setOf(dead), mapOf("site.example" to arrayOf(LOOPBACK)))
        assertResult(p.probe("http://site.example:$dead/a.png"), LinkProbe.Verdict.BROKEN, LinkReason.CONNECT_REFUSED)

        val tls = TestTls.create(dir, false)
        val https = tls.startServer { ex ->
            ex.sendResponseHeaders(200, -1)
            ex.close()
        }
        try {
            val port = https.address.port
            val o = SafeHttp.Options()
            o.allowNonPublicForTests = true // 証明書は既定のトラストストアで検証する (自己署名なので失敗する)
            o.resolver = SafeHttp.fixedResolver(mapOf("site.example" to arrayOf(LOOPBACK)))
            val c = cfg()
            val tlsProbe = LinkProbe(SafeHttp(o), UrlFilter(listOf()), c, HostThrottle(c), setOf(port), { now.get() }, { sleeps.add(it) })
            assertResult(tlsProbe.probe("https://site.example:$port/a.png"), LinkProbe.Verdict.BROKEN, LinkReason.TLS_ERROR)
        } finally {
            https.stop(0)
        }
    }

    // ---- URL の確認 ----

    @Test
    fun unsafeUrlsAreNeverConnected() {
        val s = site().on("/") { _, ex -> reply(ex, 200, null, null) }
        val p = probe(s)
        val bad = ArrayList(listOf(
            "http://user@site.example:" + s.port + "/a.png", "http://user:pw@site.example:" + s.port + "/",
            "http://site.example:22/a.png", "http://site.example:9999/a.png", "http://site.example:25/",
            "http://2130706433/a.png", "http://0177.0.0.1/a.png", "http://0x7f.1/a.png", "http://127.1/a.png", "http://127.0.0.1:" + s.port + "/",
            "http://[::1]:" + s.port + "/", "http://localhost:" + s.port + "/", "http://10.1.2.3/", "http://192.168.0.1/",
            "javascript:alert(1)", "data:text/html,x", "file:///etc/passwd", "ftp://site.example/a.png", "//site.example/a.png",
            "https://example.com＠evil.com/a.png", "http://site.example/a\\b", "http://site.example:" + s.port + "/a\r\nHost: evil.example",
            "http://site.example:" + s.port + "/a b", "http://site.example:" + s.port + "/\u0000", "http://site.example:" + s.port + "/a\u007f",
            "http://site.example:" + s.port + "/" + "a".repeat(2100), "", "not a url", "http://", "http:///a.png"))
        for (u in bad) {
            val r = p.probe(u)
            assertResult(r, LinkProbe.Verdict.UNKNOWN, LinkReason.UNSAFE_URL)
            assertFalse(r.countsAsFailure, u)
        }
        assertEquals(0, s.hits.size, "危ない URL には、1 回もつながない: " + s.hits)
    }

    @Test
    fun strictParseAllowsOnlyTheFourPorts() {
        val p = probe(cfg(), 1000, null, mapOf())
        for (u in listOf("http://example.com/a", "https://example.com/a", "http://example.com:80/a", "https://example.com:443/a",
            "http://example.com:8080/a", "https://example.com:8443/a")) {
            assertNotNull(p.strictParse(u), u)
        }
        for (u in listOf("http://example.com:8000/a", "https://example.com:444/a", "http://example.com:81/", "http://example.com:3306/",
            "http://example.com:22/", "http://example.com:0/")) {
            assertNull(p.strictParse(u), u)
        }
    }

    // ---- ホストごとの間隔と上限 ----

    @Test
    fun sameHostIsSpacedAndCappedPerHour() {
        val s = site().on("/") { _, ex -> reply(ex, 200, null, null) }
        val c = cfg()
        c.hostMinIntervalSeconds = 2
        c.hostPerHourLimit = 3
        val throttle = HostThrottle(c)
        val o = SafeHttp.Options()
        o.allowNonPublicForTests = true
        o.resolver = SafeHttp.fixedResolver(mapOf("site.example" to arrayOf(LOOPBACK)))
        val p = LinkProbe(SafeHttp(o), UrlFilter(listOf()), c, throttle, setOf(s.port), { now.get() }, { sleeps.add(it) })

        assertResult(p.probe(url(s, "/1")), LinkProbe.Verdict.OK, LinkReason.OK)
        assertTrue(sleeps.isEmpty(), "最初はすぐ")
        assertResult(p.probe(url(s, "/2")), LinkProbe.Verdict.OK, LinkReason.OK)
        assertEquals(1, sleeps.size, "2 回目は間隔が空くまで待つ")
        assertTrue(sleeps[0] >= 2000 && sleeps[0] <= 2700, "2 秒 + ゆらぎ: " + sleeps[0])
        now.addAndGet(10_000)
        assertResult(p.probe(url(s, "/3")), LinkProbe.Verdict.OK, LinkReason.OK)
        now.addAndGet(10_000)
        // 1 時間 3 件の上限
        val deferred = p.probe(url(s, "/4"))
        assertEquals(LinkProbe.Verdict.DEFER, deferred.verdict)
        assertEquals(3L, s.count("HEAD"))
        now.addAndGet(3_700_000)
        assertResult(p.probe(url(s, "/5")), LinkProbe.Verdict.OK, LinkReason.OK)
    }

    @Test
    fun hostRuleLengthensTheInterval() {
        val c = cfg()
        c.hostMinIntervalSeconds = 2
        c.hostRules = ArrayList(listOf(LinkSettings.HostRule("imgur.com", 5, listOf("/removed.png"))))
        assertEquals(5000, c.minIntervalMillis("i.imgur.com"))
        assertEquals(5000, c.minIntervalMillis("imgur.com"))
        assertEquals(2000, c.minIntervalMillis("notimgur.com"))
        assertEquals(2000, c.minIntervalMillis("example.org"))
    }

    companion object {
        private val LOOPBACK: InetAddress = InetAddress.getLoopbackAddress()

        @Throws(IOException::class)
        fun reply(ex: HttpExchange, code: Int, type: String?, location: String?) {
            if (type != null) ex.responseHeaders.add("Content-Type", type)
            if (location != null) ex.responseHeaders.add("Location", location)
            ex.sendResponseHeaders(code, -1)
        }

        private fun url(s: Site, path: String): String {
            return "http://site.example:" + s.port + path
        }

        private fun assertResult(r: LinkProbe.Result, verdict: LinkProbe.Verdict, reason: LinkReason) {
            assertEquals(verdict, r.verdict, r.toString())
            assertEquals(reason, r.reason, r.toString())
        }
    }
}
