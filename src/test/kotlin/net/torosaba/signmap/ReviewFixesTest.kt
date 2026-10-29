package net.torosaba.signmap

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.ConnectException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.net.UnknownHostException
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.function.LongSupplier
import java.util.function.Predicate
import java.util.function.Supplier
import java.util.logging.Logger

/** 独立したセキュリティレビューの指摘への対策の確認 */
internal class ReviewFixesTest {

    // ---- 1. TLS で小出しに送られても、全体の期限で切れる ----

    @Test
    fun tlsHandshakeDrippedByteByByteStillEndsAtTheDeadline(@TempDir dir: Path) {
        // TLS のレコードのヘッダーだけ送って、そのあと 1 バイトずつ送る。SO_TIMEOUT (1 回の read) だけでは、いつまでも終わらない
        val tls = TestTls.create(dir, false)
        ServerSocket(0, 5, InetAddress.getLoopbackAddress()).use { server ->
            val t = Thread {
                try {
                    server.accept().use { s ->
                        val input = s.getInputStream()
                        input.read(ByteArray(1024)) // ClientHello
                        val header = byteArrayOf(0x16, 0x03, 0x03, 0x3F, 0xFF.toByte()) // handshake、16 KB のレコード
                        s.getOutputStream().write(header)
                        for (i in 0 until 100) {
                            s.getOutputStream().write(0x02)
                            s.getOutputStream().flush()
                            Thread.sleep(400)
                        }
                    }
                } catch (e: Exception) {
                    // クライアントが切った
                }
            }
            t.isDaemon = true
            t.start()
            val o = SafeHttp.Options()
            o.allowNonPublicForTests = true
            o.sslContext = tls.trustingClientContext
            o.totalTimeoutMillis = 1500
            o.resolver = SafeHttp.fixedResolver(mapOf("site.example" to arrayOf(InetAddress.getLoopbackAddress())))
            val t0 = System.nanoTime()
            val kind = failureOf(SafeHttp(o), "https://site.example:" + server.localPort + "/")
            val ms = (System.nanoTime() - t0) / 1_000_000
            assertEquals(SafeHttp.Kind.TIMEOUT, kind)
            assertTrue(ms < 4000, "期限 (1.5 秒) で切れた: $ms ms")
        }
    }

    @Test
    fun stopClosesOpenSocketsSoBlockedReadsEnd() {
        val connected = CountDownLatch(1)
        ServerSocket(0, 5, InetAddress.getLoopbackAddress()).use { server ->
            val t = Thread {
                try {
                    server.accept().use {
                        connected.countDown()
                        Thread.sleep(30_000) // 何も返さない
                    }
                } catch (e: Exception) {
                    // 閉じられた
                }
            }
            t.isDaemon = true
            t.start()
            val o = SafeHttp.Options()
            o.allowNonPublicForTests = true
            o.totalTimeoutMillis = 60_000
            o.resolver = SafeHttp.fixedResolver(mapOf("site.example" to arrayOf(InetAddress.getLoopbackAddress())))
            val http = SafeHttp(o)
            val elapsed = AtomicLong()
            val caller = Thread {
                val t0 = System.nanoTime()
                failureOf(http, "http://site.example:" + server.localPort + "/")
                elapsed.set((System.nanoTime() - t0) / 1_000_000)
            }
            caller.start()
            assertTrue(connected.await(5, TimeUnit.SECONDS))
            Thread.sleep(300)
            http.closeAll()
            caller.join(5000)
            assertFalse(caller.isAlive, "止めたあとに、止まっている read が残らない")
            assertTrue(elapsed.get() < 5000)
        }
    }

    // ---- 2. サーバー自身のアドレス・設定で除いたアドレスには、つながない ----

    @Test
    fun ownAddressesAndConfiguredRangesAreNeverConnected() {
        val connects = AtomicInteger()
        val o = SafeHttp.Options()
        // この機械のアドレスが「公開」のものだった場合 (ここでは 8.8.8.8 とする)。同じ機械の中を通って、外から守られたサービスに届くので除く
        o.localAddresses = Supplier { listOf(inet("8.8.8.8"), inet("2606:4700:4700::1111")) }
        o.denyList = AddressDenyList(listOf("93.184.216.0/24", "203.0.113.7", "2a00:1450::/32", "not an address", "1.2.3.4/99", "evil.example"))
        o.resolver = SafeHttp.fixedResolver(mapOf(
            "own4.example" to arrayOf(inet("8.8.8.8")),
            "own6.example" to arrayOf(inet("2606:4700:4700::1111")),
            "range.example" to arrayOf(inet("93.184.216.34")),
            "single.example" to arrayOf(inet("203.0.113.7")),
            "range6.example" to arrayOf(inet("2a00:1450:4001:81b::200e")),
            "mixed.example" to arrayOf(inet("1.1.1.1"), inet("8.8.8.8")),
            "fine.example" to arrayOf(inet("1.1.1.1"))))
        o.connector = SafeHttp.Connector { _, _, _ ->
            connects.incrementAndGet()
            throw ConnectException("Connection refused")
        }
        val http = SafeHttp(o)
        for (h in listOf("own4", "own6", "range", "single", "range6", "mixed")) {
            assertEquals(SafeHttp.Kind.UNSAFE_ADDRESS, failureOf(http, "http://$h.example/"), h)
        }
        assertEquals(0, connects.get())
        assertEquals(SafeHttp.Kind.CONNECT_REFUSED, failureOf(http, "http://fine.example/"), "対照: 普通の公開アドレスにはつなごうとする")
        assertEquals(1, connects.get())
        assertEquals(listOf("not an address", "1.2.3.4/99", "evil.example"), o.denyList!!.rejected())
    }

    @Test
    fun denyListRangesAreExact() {
        val l = AddressDenyList(listOf("198.51.100.0/24", "10.0.0.0/8", "2001:db8::/32", "192.0.2.5", "0.0.0.0/0"))
        assertTrue(l.matches(inet("198.51.100.255")))
        assertTrue(l.matches(inet("8.8.8.8")), "0.0.0.0/0 はすべて")
        val narrow = AddressDenyList(listOf("198.51.100.0/25", "2001:db8:1::/48", "192.0.2.5", "::ffff:203.0.113.9"))
        assertTrue(narrow.matches(inet("198.51.100.127")))
        assertFalse(narrow.matches(inet("198.51.100.128")))
        assertTrue(narrow.matches(inet("2001:db8:1:ffff::1")))
        assertFalse(narrow.matches(inet("2001:db8:2::1")))
        assertTrue(narrow.matches(inet("192.0.2.5")))
        assertFalse(narrow.matches(inet("192.0.2.6")))
        assertTrue(narrow.matches(inet("203.0.113.9")), "IPv4 射影の書き方でも、IPv4 のアドレスとして扱う")
        assertEquals(0, AddressDenyList(listOf()).size())
    }

    @Test
    fun connectingToOurOwnAddressIsClosed() {
        // 名前の引き方に関係なく、つないだ先が自分自身のアドレスなら閉じる (ここでは ServerSocket にループバックでつなぐ)
        ServerSocket(0, 5, InetAddress.getLoopbackAddress()).use { server ->
            val o = SafeHttp.Options()
            o.resolver = SafeHttp.fixedResolver(mapOf("pub.example" to arrayOf(inet("8.8.8.8"))))
            o.connector = SafeHttp.Connector { _, _, _ -> Socket(InetAddress.getLoopbackAddress(), server.localPort, InetAddress.getLoopbackAddress(), 0) }
            assertEquals(SafeHttp.Kind.UNSAFE_ADDRESS, failureOf(SafeHttp(o), "http://pub.example/"))
        }
    }

    // ---- 3. 内部アドレスへの転送は、公開の地図に出さない ----

    @Test
    fun unsafeRedirectsAreNotShownOnThePublicMap() {
        val cfg = LinkSettings()
        var s = LinkState.pending(1_000_000).apply(LinkProbe.Result.unknown(LinkReason.REDIRECT_UNSAFE, 302, false), 1_000_000, cfg, 0.0)
        assertEquals(LinkStatus.UNKNOWN, s.status)
        assertFalse(s.needsAttention())
        assertNull(DynmapBackend.layerName(s, true))
        s = LinkState.pending(1_000_000).apply(LinkProbe.Result.unknown(LinkReason.UNSAFE_URL, 0, false), 1_000_000, cfg, 0.0)
        assertNull(DynmapBackend.layerName(s, true))
    }

    // ---- 4. 名前を引く枠が埋まっても、ほかの URL を誤って BROKEN にしない ----

    @Test
    fun dnsSlotsAreLimitedAndSaturationIsNotAFailure() {
        val release = CountDownLatch(1)
        val started = AtomicInteger()
        val resolver = SafeHttp.systemResolver(200, SafeHttp.Lookup { host ->
            started.incrementAndGet()
            // 引き終わらない問い合わせ。getAllByName と同じく、中断されても続ける
            while (release.count > 0) {
                try {
                    release.await(10, TimeUnit.SECONDS)
                } catch (ignored: InterruptedException) {
                    // 中断できない
                }
            }
            throw UnknownHostException(host)
        }, 2)
        val o = SafeHttp.Options()
        o.resolver = resolver
        o.connector = SafeHttp.Connector { _, _, _ ->
            throw ConnectException("Connection refused")
        }
        val http = SafeHttp(o)
        // 2 つが引き終わらず、時間切れ (DNS_TIMEOUT)。その間、枠は返らない
        assertEquals(SafeHttp.Kind.DNS_TIMEOUT, failureOf(http, "http://hang1.example/"))
        assertEquals(SafeHttp.Kind.DNS_TIMEOUT, failureOf(http, "http://hang2.example/"))
        // 枠が埋まったので、3 つ目は引こうとせず DNS_BUSY
        assertEquals(SafeHttp.Kind.DNS_BUSY, failureOf(http, "http://legit.example/"))
        assertEquals(2, started.get())
        release.countDown()

        // LinkProbe は DNS_BUSY を DEFER (失敗に数えない) にする
        val cfg = LinkSettings()
        val o2 = SafeHttp.Options()
        o2.resolver = SafeHttp.systemResolver(100, SafeHttp.Lookup { host ->
            throw UnknownHostException(host)
        }, 0) // 枠が 0 = 常に DNS_BUSY
        val probe = LinkProbe(SafeHttp(o2), UrlFilter(listOf()), cfg, HostThrottle(cfg), setOf(80), LongSupplier { System.currentTimeMillis() }, LinkProbe.Sleeper {
        })
        val r = probe.probe("http://legit.example/a.png")
        assertEquals(LinkProbe.Verdict.DEFER, r.verdict)
        val after = LinkState.pending(1000).apply(r, 1000, cfg, 0.0)
        assertEquals(LinkStatus.PENDING, after.status, "状態は変わらず、あとでやり直す")
        assertEquals(0, after.consecutiveFailures)
    }

    // ---- 5. 追跡の表を、看板にない URL で占められない ----

    private val clock = AtomicLong(10_000_000_000L)

    private fun checker(dir: Path, max: Int): LinkChecker {
        val c = LinkSettings()
        c.maxTrackedUrls = max
        c.startupDelayMinutes = 0
        c.hostRules = ArrayList()
        return LinkChecker(Logger.getLogger("rf-" + System.nanoTime()), dir.resolve("l.json"), c, Predicate { u -> u.startsWith("https://") },
            LinkProbe.Prober { LinkProbe.Result.ok(200) }, HostThrottle(c), LongSupplier { clock.get() }, Runnable {
        })
    }

    @Test
    fun urlsNoLongerOnAnySignAreEvictedFirstEvenIfChecked(@TempDir dir: Path) {
        val c = checker(dir, 100)
        val attacker = ArrayList<String>()
        for (i in 0 until 100) attacker.add(url("spam", i))
        c.track(attacker)
        c.step(clock.get()) // 検査して、OK にする
        c.step(clock.get() + 1)
        // 看板が壊されて、攻撃者の URL は知らされなくなる。しばらくして、正規の看板が増える
        clock.addAndGet(3 * 3_600_000L)
        val legit = ArrayList<String>()
        for (i in 0 until 30) legit.add(url("real", i))
        c.track(legit)
        for (u in legit) assertNotNull(c.state(u), "正規の URL は追跡できる: $u")
        assertTrue(c.tracked() <= 100)
    }

    @Test
    fun urlsStillOnSignsAreNeverEvictedByNewOnes(@TempDir dir: Path) {
        val c = checker(dir, 50)
        val live = ArrayList<String>()
        for (i in 0 until 50) live.add(url("live", i))
        c.track(live)
        for (i in 0 until 40) c.step(clock.addAndGet(1000)) // 検査して、PENDING でなくする
        assertEquals(50, c.counts().ok)
        clock.addAndGet(10 * 60_000L)
        val both = ArrayList<String>(live)
        both.add(url("new", 1))
        c.track(both) // 全部がいま看板にある。新しいものは入らない (既存を落とさない)
        for (u in live) assertNotNull(c.state(u), u)
        assertNull(c.state(url("new", 1)))
    }

    @Test
    fun whenNothingCanBeEvictedWeDoNotRescanOnEveryNewUrl(@TempDir dir: Path) {
        val c = checker(dir, 200)
        val live = ArrayList<String>()
        for (i in 0 until 200) live.add(url("live", i))
        c.track(live)
        clock.addAndGet(60_000L)
        val t0 = System.nanoTime()
        for (i in 0 until 20_000) c.track(listOf(url("flood", i)))
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertTrue(ms < 3000, "満杯のとき、新しい URL のたびに全体を調べない: $ms ms")
        assertEquals(200, c.tracked())
    }

    // ---- 6. § (書式コード) ----

    @Test
    fun sectionSignIsRemovedFromEverythingShown() {
        assertEquals("a4blkc", SafeText.clean("a§4b§l§kc", 100), "§ そのものを除く (残る文字は、ただの文字)")
        assertFalse(SafeText.displayUrl("http://nx.invalid/§4§lSERVER§r§k", 100).contains("§"))
        assertFalse(SafeText.logUrl("http://nx.invalid/§4x").contains("§"))
        val popup = DynmapBackend.problemPopup(
            LinkState.pending(0), MarkerHtml.View(SignRecord(1, 2, 3, "u"), "http://nx.invalid/§4§l.png", MediaKind.IMAGE, "png"),
            "wor§4ld", SignRecord(1, 2, 3, "u"), listOf(), true, 0)
        assertFalse(popup.contains("§"))
    }

    // ---- 7. ホストの判定の揺れ ----

    @Test
    fun trailingDotAndCaseShareTheSameHostBudget() {
        val c = LinkSettings()
        c.hostPerHourLimit = 2
        c.hostMinIntervalSeconds = 0
        val t = HostThrottle(c)
        assertTrue(t.reserve("a.com", 1000) >= 0)
        assertTrue(t.reserve("A.COM.", 1001) >= 0)
        assertEquals(-1, t.reserve("a.com..", 1002), "a.com と a.com. と A.COM は同じホスト")
    }

    companion object {
        private fun failureOf(http: SafeHttp, url: String): SafeHttp.Kind? {
            try {
                http.request("HEAD", URI.create(url), null)
            } catch (f: SafeHttp.HttpFailure) {
                return f.kind
            }
            return null
        }

        private fun url(tag: String, i: Int): String = "https://h$i.example/$tag.png"

        private fun inet(s: String): InetAddress {
            try {
                return InetAddress.getByName(s)
            } catch (e: UnknownHostException) {
                throw IllegalArgumentException(s, e)
            }
        }
    }
}
