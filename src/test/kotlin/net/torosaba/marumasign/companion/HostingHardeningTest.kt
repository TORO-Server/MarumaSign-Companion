package net.torosaba.marumasign.companion

import com.google.gson.JsonParser
import org.bukkit.configuration.file.YamlConfiguration
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.Socket
import java.net.URI
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.HexFormat
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.logging.Logger

/** ホストの上限・作者の記録・読み込みの分離・承認・監査ログ */
internal class HostingHardeningTest {

    private val logger = Logger.getLogger("HostingHardeningTest")
    private val servers = ArrayList<SafeHttpTest.RawServer>()
    private val rigs = ArrayList<Rig>()
    private val alice: UUID = UUID.fromString("00000000-0000-0000-0000-00000000000a")
    private val bob: UUID = UUID.fromString("00000000-0000-0000-0000-00000000000b")

    @AfterEach
    fun close() {
        for (r in rigs) r.m.stop()
        for (s in servers) s.close()
    }

    private fun serve(h: SafeHttpTest.RawServer.Handler) = SafeHttpTest.RawServer(h).also { servers.add(it) }

    /** パスごとに違う中身を返す応答 (同じ中身は 1 つのファイルにまとまるので) */
    private fun body(request: String, size: Int = 64): ByteArray {
        val path = request.substringAfter(' ').substringBefore(' ')
        val head = "PNG$path".toByteArray(StandardCharsets.US_ASCII)
        return head + ByteArray(maxOf(0, size - head.size))
    }

    private fun reply(s: Socket, bytes: ByteArray) {
        s.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Type: image/png\r\nContent-Length: ${bytes.size}\r\n\r\n".toByteArray(StandardCharsets.ISO_8859_1))
        s.getOutputStream().write(bytes)
        s.getOutputStream().flush()
    }

    private fun await(what: String, condition: () -> Boolean) {
        val until = System.nanoTime() + 30_000_000_000L
        while (!condition()) {
            assertTrue(System.nanoTime() < until, "待ったが、ならなかった: $what")
            Thread.sleep(10)
        }
    }

    /** 実物の HostFetcher と HostingManager を、ループバックのサーバーとファイルの保存先で動かす */
    private inner class Rig(dir: Path, configure: HostingSettings.() -> Unit = {}) {
        val cfg = HostingSettings().apply {
            publicBaseUrl = "https://h.example"
            directory = dir.resolve("hosted").toString()
            port = 0
            verifyAfterHours = 0
            perHourLimit = 1000
            configure()
        }
        val now = AtomicLong(1_000_000_000_000L)
        val signs = CopyOnWriteArrayList<SignRecord>()
        val auditFile: Path = dir.resolve("hosting-audit.jsonl")
        val stateFile: Path = dir.resolve("hosting.json")

        val m: HostingManager

        init {
            val o = SafeHttp.Options()
            o.allowNonPublicForTests = true
            o.connectTimeoutMillis = 2000
            val lo = arrayOf(InetAddress.getLoopbackAddress())
            o.resolver = SafeHttp.fixedResolver(mapOf("site.example" to lo, "other.example" to lo))
            val link = LinkSettings()
            link.hostMinIntervalSeconds = 0
            link.hostPerHourLimit = 10_000
            val fetcher = HostFetcher(SafeHttp(o), { URI.create(it) }, HostThrottle(link), 5, MediaKind.defaultExtensions(), cfg::hostPermitted)
            m = HostingManager(null, logger, cfg, stateFile, fetcher, MediaKind.defaultExtensions(),
                signs = { mapOf("world" to signs.toList()) },
                linkState = { LinkState(LinkStatus.OK, LinkReason.OK, 200, 0, 0, now.get() - 1, now.get() - 1, 0, now.get() + 1000, now.get()) },
                onRewritten = { _, _, _, _, _, _ -> },
                clock = { now.get() })
            m.start()
            m.onWorker { }
            rigs.add(this)
        }

        val store: HostedStore get() = m.store

        fun tick() = m.onWorker { m.tick() }

        /** 看板を置く。author を渡すと、そのプレイヤーが書いたことになる */
        fun sign(x: Int, url: String, author: UUID? = null) {
            signs.add(SignRecord(x, 64, 0, url))
            if (author != null) m.signWritten("world", x, 64, 0, url, author)
        }

        fun key(x: Int) = "world:$x:64:0"

        /** 読み込みが全部終わるまで待って、結果を受け取る */
        fun settle() {
            await("読み込みが終わる") { m.inFlightCount == 0 }
            m.onWorker { }
        }

        fun tickAndSettle() {
            tick()
            settle()
        }

        fun stop() {
            rigs.remove(this)
            m.stop()
        }
    }

    private fun url(s: SafeHttpTest.RawServer, path: String, host: String = "site.example") = "http://$host:${s.port}$path"

    // ---- 読み込みを、処理のスレッドから分ける ----

    @Test
    fun aSlowResponseDoesNotHoldUpSavingOrRewriting(@TempDir dir: Path) {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val s = serve { sock, req ->
            started.countDown()
            release.await(60, TimeUnit.SECONDS)
            reply(sock, body(req))
        }
        val r = Rig(dir)
        val u = url(s, "/slow.png")
        r.sign(1, u)
        try {
            r.tick()
            assertTrue(started.await(30, TimeUnit.SECONDS), "読み込みは始まっている")
            assertEquals(1, r.m.inFlightCount)
            assertTrue(Files.exists(r.stateFile), "応答を待っていても、状態は保存できる")

            // 処理のスレッドは止まっていない: 次の処理も、看板の書き換えの結果の受け取りも進む
            val rewrite = HostedStore.Rewrite("world", 9, 64, 0, "https://a.example/a.png", "https://a.example/b.png")
            r.store.rewrites[rewrite.key] = rewrite
            val t0 = System.nanoTime()
            r.tick()
            r.m.onWorker { r.m.rewritten(listOf(rewrite to SignRewriter.Outcome.CHANGED)) }
            val millis = (System.nanoTime() - t0) / 1_000_000
            assertTrue(millis < 20_000, "応答を待たずに進む: $millis ms")
            assertNull(r.store.rewrites[rewrite.key])
            assertEquals(1, s.requests.size, "同じ URL を重ねて読まない")
        } finally {
            release.countDown()
        }
        r.settle()
        assertEquals(1, r.store.files.size)
        assertNotNull(r.store.bySource[u])
        assertFalse(r.store.candidates.containsKey(u))
    }

    @Test
    fun concurrentDownloadsAreBounded(@TempDir dir: Path) {
        val active = AtomicInteger()
        val peak = AtomicInteger()
        val two = CountDownLatch(2)
        val s = serve { sock, req ->
            peak.accumulateAndGet(active.incrementAndGet()) { a, b -> maxOf(a, b) }
            two.countDown()
            two.await(30, TimeUnit.SECONDS)
            Thread.sleep(30)
            active.decrementAndGet()
            reply(sock, body(req))
        }
        val r = Rig(dir) { downloadConcurrency = 2 }
        for (i in 1..6) r.sign(i, url(s, "/$i.png"))
        await("6 つともホストする") {
            r.tick()
            r.store.files.size == 6
        }
        assertEquals(2, peak.get(), "同時に 2 つまで")
        assertEquals(6, s.requests.size)
    }

    @Test
    fun aRemovedSignsDownloadIsNotHosted(@TempDir dir: Path) {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val s = serve { sock, req ->
            started.countDown()
            release.await(60, TimeUnit.SECONDS)
            reply(sock, body(req))
        }
        val r = Rig(dir)
        r.sign(1, url(s, "/a.png"), alice)
        r.tick()
        assertTrue(started.await(30, TimeUnit.SECONDS))
        r.signs.clear()
        r.tick()
        release.countDown()
        r.settle()
        assertTrue(r.store.files.isEmpty(), "読んでいるあいだに看板がなくなった URL はホストしない")
        Files.list(dir.resolve("hosting-tmp")).use { assertEquals(0L, it.count(), "一時ファイルを残さない") }
    }

    @Test
    fun stoppingDoesNotWaitForAStalledDownload(@TempDir dir: Path) {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val s = serve { sock, req ->
            started.countDown()
            release.await(60, TimeUnit.SECONDS)
            reply(sock, body(req))
        }
        val r = Rig(dir)
        r.sign(1, url(s, "/a.png"))
        r.tick()
        assertTrue(started.await(30, TimeUnit.SECONDS))
        val t0 = System.nanoTime()
        r.stop()
        assertTrue((System.nanoTime() - t0) / 1_000_000 < 10_000, "止めるときに応答を待たない")
        release.countDown()
    }

    // ---- 作者の記録とプレイヤーごとの上限 ----

    @Test
    fun aPlayersFileCountIsLimitedWithoutConnecting(@TempDir dir: Path) {
        val s = serve { sock, req -> reply(sock, body(req)) }
        val r = Rig(dir) { perPlayerFiles = 1 }
        val first = url(s, "/first.png")
        val second = url(s, "/second.png")
        val others = url(s, "/others.png")
        r.sign(1, first, alice)
        r.tickAndSettle()
        assertEquals(alice.toString(), r.store.files.values.single().owner)

        r.sign(2, second, alice)
        r.sign(3, others, bob)
        r.tickAndSettle()
        r.tickAndSettle()
        assertEquals(0, s.requests.count { it.contains("second.png") }, "上限に達した人の URL は読みにも行かない")
        assertTrue(r.store.candidates.containsKey(second), "候補のまま待つ (失敗にしない)")
        assertFalse(r.store.failures.containsKey(second))
        assertEquals(bob.toString(), r.store.files[r.store.bySource[others]]!!.owner, "ほかの人の分は止まらない")

        // 取り下げれば、空く
        r.m.onWorker { }
        val done = CountDownLatch(1)
        r.m.remove(r.store.bySource[first]!!) { done.countDown() }
        assertTrue(done.await(30, TimeUnit.SECONDS))
        r.tickAndSettle()
        assertEquals(alice.toString(), r.store.files[r.store.bySource[second]]!!.owner)
    }

    @Test
    fun aPlayersBytesAreLimitedAndAShortageIsRetriedSoon(@TempDir dir: Path) {
        val size = 800 * 1024
        val s = serve { sock, req -> reply(sock, body(req, size)) }
        val r = Rig(dir) { perPlayerMb = 1 }
        val first = url(s, "/first.png")
        val second = url(s, "/second.png")
        r.sign(1, first, alice)
        r.tickAndSettle()
        assertEquals(1, r.store.files.size)

        r.sign(2, second, alice)
        r.tickAndSettle()
        assertEquals(1, r.store.files.size, "残りより大きいファイルは入らない")
        assertTrue(r.store.candidates.containsKey(second), "7 日の失敗にしない")
        assertFalse(r.store.failures.containsKey(second))
        val tried = s.requests.count { it.contains("second.png") }
        assertEquals(1, tried)

        // すぐにはやり直さない
        r.tickAndSettle()
        assertEquals(tried, s.requests.count { it.contains("second.png") })

        // 少し待つと、やり直す。空いていれば入る
        val done = CountDownLatch(1)
        r.m.remove(r.store.bySource[first]!!) { done.countDown() }
        assertTrue(done.await(30, TimeUnit.SECONDS))
        r.now.addAndGet(HostingManager.ROOM_RETRY_MILLIS + 1)
        r.tickAndSettle()
        assertEquals(alice.toString(), r.store.files[r.store.bySource[second]]!!.owner)
    }

    @Test
    fun aGlobalShortageIsRetriedSoonButAFileOverTheFileLimitIsNot(@TempDir dir: Path) {
        val size = 800 * 1024
        val s = serve { sock, req -> reply(sock, body(req, if (req.contains("huge")) 2 * 1024 * 1024 else size)) }
        val shortage = Rig(dir.resolve("a")) {
            maxFileMb = 1
            maxTotalMb = 1
        }
        val first = url(s, "/first.png")
        val second = url(s, "/second.png")
        shortage.sign(1, first)
        shortage.tickAndSettle()
        assertEquals(1, shortage.store.files.size)
        shortage.sign(2, second)
        shortage.tickAndSettle()
        assertEquals(1, shortage.store.files.size)
        assertTrue(shortage.store.candidates.containsKey(second))
        assertFalse(shortage.store.failures.containsKey(second), "空きが足りないだけなら、7 日待たない")
        shortage.now.addAndGet(HostingManager.ROOM_RETRY_MILLIS + 1)
        shortage.tickAndSettle()
        assertEquals(2, s.requests.count { it.contains("second.png") }, "短い時間でやり直す")

        val tooLarge = Rig(dir.resolve("b")) {
            maxFileMb = 1
            maxTotalMb = 4096
        }
        val huge = url(s, "/huge.png")
        tooLarge.sign(1, huge)
        tooLarge.tickAndSettle()
        val failure = tooLarge.store.failures[huge]!!
        assertEquals(HostingPolicy.Reject.TOO_LARGE, failure.reason)
        assertEquals(tooLarge.now.get() + 7 * 24 * 3_600_000L, failure.retryAt, "1 ファイルの上限を超えるものは、これまでどおり")
    }

    @Test
    fun theGlobalFileCountIsLimited(@TempDir dir: Path) {
        val s = serve { sock, req -> reply(sock, body(req)) }
        val r = Rig(dir) { maxTotalFiles = 2 }
        for (i in 1..4) {
            r.sign(i, url(s, "/$i.png"))
            r.tickAndSettle()
        }
        assertEquals(2, r.store.files.size)
        assertEquals(2, s.requests.size, "上限に達したら読みにも行かない")
        assertEquals(2, r.store.candidates.size, "候補のまま待つ")
    }

    @Test
    fun signsWithoutAnAuthorKeepBeingHostedAndAreNotCounted(@TempDir dir: Path) {
        val s = serve { sock, req -> reply(sock, body(req)) }
        val r = Rig(dir) { perPlayerFiles = 1 }
        val a = url(s, "/a.png")
        val b = url(s, "/b.png")
        val shared = url(s, "/shared.png")
        r.sign(1, a)
        r.sign(2, b)
        r.tickAndSettle()
        r.tickAndSettle()
        assertEquals(2, r.store.files.size, "作者が分からない看板は、上限に関係なくホストする")
        assertTrue(r.store.files.values.all { it.owner == null })

        // 同じ URL の看板に、作者が分からないものが混ざっていれば、だれの分にも数えない
        r.sign(3, shared)
        r.sign(4, shared, alice)
        r.tickAndSettle()
        assertNull(r.store.files[r.store.bySource[shared]]!!.owner)

        // プレイヤーの分は、ちゃんと 1 つ目を受け付ける (以前のファイルに食われていない)
        val own = url(s, "/own.png")
        r.sign(5, own, alice)
        r.tickAndSettle()
        assertEquals(alice.toString(), r.store.files[r.store.bySource[own]]!!.owner)
    }

    @Test
    fun theEarliestAuthorWithRoomIsCounted(@TempDir dir: Path) {
        val s = serve { sock, req -> reply(sock, body(req)) }
        val r = Rig(dir) { perPlayerFiles = 1 }
        val own = url(s, "/own.png")
        val shared = url(s, "/shared.png")
        r.sign(1, own, alice)
        r.tickAndSettle()
        r.now.addAndGet(1000)
        // 先に書いた alice は上限なので、後から書いた bob の分になる
        r.sign(2, shared, alice)
        r.now.addAndGet(1000)
        r.sign(3, shared, bob)
        r.tickAndSettle()
        assertEquals(bob.toString(), r.store.files[r.store.bySource[shared]]!!.owner)
    }

    @Test
    fun onePlayerCannotTakeEverySlotOrTheWholeHour(@TempDir dir: Path) {
        val release = CountDownLatch(1)
        val s = serve { sock, req ->
            release.await(60, TimeUnit.SECONDS)
            reply(sock, body(req))
        }
        val r = Rig(dir) {
            downloadConcurrency = 2
            perHourLimit = 9
            perPlayerFiles = 0
        }
        for (i in 1..20) r.sign(i, url(s, "/a$i.png"), alice)
        r.sign(21, url(s, "/b.png"), bob)
        r.tick()
        await("2 人の分が 1 件ずつ読まれる") { s.requests.size == 2 }
        assertEquals(1, s.requests.count { it.contains("/b.png") }, "alice の分が多くても、bob の分も読む")
        release.countDown()
        r.settle()
        r.tickAndSettle()
        r.tickAndSettle()
        r.tickAndSettle()
        r.tickAndSettle()
        assertEquals(3, s.requests.count { it.contains("/a") }, "1 時間に読むのは、全体の上限 (9) の 3 分の 1 まで")
    }

    @Test
    fun theFirstAuthorStaysWhenTheSameUrlIsRewrittenAndAChangedSignForgetsTheAuthor(@TempDir dir: Path) {
        val r = Rig(dir) { requireApproval = true }
        val a = "https://a.example/a.png"
        r.signs.add(SignRecord(1, 64, 0, a))
        r.m.signWritten("world", 1, 64, 0, a, alice)
        r.tick()
        assertEquals(alice.toString(), r.store.authors[r.key(1)]!!.uuid)
        r.m.signWritten("world", 1, 64, 0, a, bob)
        r.tick()
        assertEquals(alice.toString(), r.store.authors[r.key(1)]!!.uuid, "数字を直しただけなら、書いた人は変わらない")

        // 別の URL に書き直された
        r.signs.clear()
        r.signs.add(SignRecord(1, 64, 0, "https://a.example/other.png"))
        r.m.signWritten("world", 1, 64, 0, "https://a.example/other.png", bob)
        r.tick()
        assertEquals(bob.toString(), r.store.authors[r.key(1)]!!.uuid)

        // プレイヤーの操作なしで別の URL に変わった看板の記録は、当てにしない
        r.signs.clear()
        r.signs.add(SignRecord(1, 64, 0, "https://a.example/pasted.png"))
        r.tick()
        assertNull(r.store.authors[r.key(1)])

        // ホスト先に書き換えた看板の記録は残す
        r.m.signWritten("world", 2, 64, 0, a, alice)
        r.signs.add(SignRecord(2, 64, 0, "https://h.example/aaaaaaaaaaaa.png"))
        r.tick()
        assertEquals(a, r.store.authors[r.key(2)]!!.url)
        // ホスト先の URL を書いた看板は、作者を記録しない
        r.m.signWritten("world", 3, 64, 0, "https://h.example/aaaaaaaaaaaa.png", bob)
        r.tick()
        assertNull(r.store.authors[r.key(3)])

        // 見つからない看板の記録は、しばらく残してから消す
        r.signs.clear()
        r.tick()
        assertNotNull(r.store.authors[r.key(2)])
        r.now.addAndGet((r.cfg.unhostAfterDays + 1) * 24 * 3_600_000L)
        r.tick()
        assertNull(r.store.authors[r.key(2)])
    }

    @Test
    fun authorsOfWorldsThatAreNotLoadedAreKept(@TempDir dir: Path) {
        val r = Rig(dir) { requireApproval = true }
        r.store.authors["nether:1:64:0"] = HostedStore.SignAuthor(alice.toString(), "https://a.example/a.png", 1)
        r.now.addAndGet(100 * 24 * 3_600_000L)
        r.tick()
        assertNotNull(r.store.authors["nether:1:64:0"], "読み込まれていないワールドの看板は、なくなったのではない")
    }

    // ---- 保存 ----

    @Test
    fun ownersAuthorsAndApprovalsSurviveARestart(@TempDir dir: Path) {
        val s = serve { sock, req -> reply(sock, body(req)) }
        val first = Rig(dir)
        val u = url(s, "/a.png")
        first.sign(1, u, alice)
        first.tickAndSettle()
        first.store.approved.add("https://b.example/b.png")
        val id = first.store.bySource[u]!!
        first.stop()

        val second = Rig(dir)
        assertEquals(alice.toString(), second.store.files[id]!!.owner)
        assertTrue(second.store.files[id]!!.sha256.matches(Regex("[0-9a-f]{64}")))
        assertEquals(alice.toString(), second.store.authors[second.key(1)]!!.uuid)
        assertTrue("https://b.example/b.png" in second.store.approved)
    }

    @Test
    fun hostingJsonFromEarlierVersionsIsReadAsIs(@TempDir dir: Path) {
        // 以前の版が書いた形 (作者・承認の行がない)
        val json = dir.resolve("hosting.json")
        Files.writeString(json, """
            {"version":1,
             "files":[{"id":"abcdefgh2345","ext":"png","size":10,"hostedAt":1,"lastUsedAt":2,
                       "sources":["https://a.example/a.png"],"origins":{"world:1:2:3":"https://a.example/a.png"}}],
             "candidates":{"https://b.example/b.png":5},
             "failures":{"https://c.example/c.m3u8":{"reason":"STREAM","retryAt":9}},
             "rewrites":[],
             "denied":["https://d.example/d.png"]}
        """.trimIndent())
        val store = HostedStore()
        FileHostingStorage(logger, dir.resolve("hosted"), json).load(store)
        assertTrue(Files.exists(json), "壊れたものとして退避しない")
        val f = store.files["abcdefgh2345"]!!
        assertNull(f.owner)
        assertEquals("", f.sha256)
        assertEquals("https://a.example/a.png", f.origins["world:1:2:3"])
        assertEquals(5L, store.candidates["https://b.example/b.png"])
        assertEquals(HostingPolicy.Reject.STREAM, store.failures["https://c.example/c.m3u8"]!!.reason)
        assertTrue("https://d.example/d.png" in store.denied)
        assertTrue(store.authors.isEmpty())
        assertTrue(store.approved.isEmpty())

        // 書き戻しても、以前の版が読める形のまま (version は変えない)
        store.save(json)
        val root = JsonParser.parseString(Files.readString(json)).asJsonObject
        assertEquals(1, root.get("version").asInt)
        assertEquals(setOf("id", "ext", "size", "hostedAt", "lastUsedAt", "sources", "origins"), root.getAsJsonArray("files")[0].asJsonObject.keySet())
    }

    @Test
    fun brokenAuthorRecordsDoNotLoseTheRest(@TempDir dir: Path) {
        val json = dir.resolve("hosting.json")
        Files.writeString(json, """
            {"version":1,"files":[{"id":"abcdefgh2345","ext":"png","size":10,"hostedAt":1,"lastUsedAt":2,"owner":"not-a-uuid","sha256":"zz",
                                   "sources":[],"origins":{}}],
             "candidates":{},"failures":{},"rewrites":[],"denied":[],"approved":[],
             "authors":{"world:1:2:3":{"uuid":"not-a-uuid","url":"https://a.example/a.png","at":1},
                        "world:4:5:6":{"uuid":"00000000-0000-0000-0000-00000000000a","url":"https://a.example/a.png","at":1},
                        "bad key":{"uuid":"00000000-0000-0000-0000-00000000000a","url":"https://a.example/a.png","at":1},
                        "world:7:8:9":"nonsense"}}
        """.trimIndent())
        val store = HostedStore()
        store.load(json)
        assertNull(store.files["abcdefgh2345"]!!.owner)
        assertEquals("", store.files["abcdefgh2345"]!!.sha256)
        assertEquals(setOf("world:4:5:6"), store.authors.keys)
    }

    // ---- ホストしてよいホスト ----

    @Test
    fun hostRules() {
        val open = HostingSettings()
        assertTrue(open.hostPermitted("anything.example"), "allow-hosts が空なら、これまでどおり")
        assertFalse(open.hostPermitted(null))
        assertFalse(open.hostPermitted(""))

        val c = HostingSettings()
        c.allowHostsSet = true
        c.allowHosts = listOf("imgur.com")
        c.excludeHosts = listOf("bad.imgur.com")
        assertTrue(c.hostPermitted("imgur.com"))
        assertTrue(c.hostPermitted("i.IMGUR.com"))
        assertTrue(c.hostPermitted("imgur.com."), "末尾の . は同じホスト")
        assertFalse(c.hostPermitted("notimgur.com"))
        assertFalse(c.hostPermitted("imgur.com.example"))
        assertFalse(c.hostPermitted("bad.imgur.com"), "exclude-hosts が先")
        assertFalse(c.hostPermitted("x.bad.imgur.com."))
    }

    @Test
    fun allowHostsInTheConfig() {
        val y = YamlConfiguration()
        y.set("hosting.allow-hosts", listOf(" Imgur.COM ", "*.ibb.co", "example.net."))
        val h = HostingSettings.read(y) { throw AssertionError(it) }
        assertTrue(h.allowHostsSet)
        assertEquals(listOf("imgur.com", "ibb.co", "example.net"), h.allowHosts)
        assertTrue(h.hostPermitted("i.ibb.co"))
        assertFalse(h.hostPermitted("other.example"))

        // 読める行がひとつもなくても、制限なしには戻らない
        val y2 = YamlConfiguration()
        y2.set("hosting.allow-hosts", listOf("not a host!"))
        val warnings = ArrayList<String>()
        val h2 = HostingSettings.read(y2) { warnings.add(it) }
        assertTrue(h2.allowHostsSet)
        assertTrue(h2.allowHosts.isEmpty())
        assertFalse(h2.hostPermitted("imgur.com"))
        assertEquals(2, warnings.size)
    }

    @Test
    fun anAllowListStopsNewUrlsButLeavesHostedFilesAlone(@TempDir dir: Path) {
        val s = serve { sock, req -> reply(sock, body(req)) }
        val hosted = url(s, "/hosted.png", "other.example")
        val first = Rig(dir)
        first.sign(1, hosted)
        first.tickAndSettle()
        val id = first.store.bySource[hosted]!!
        first.stop()

        val r = Rig(dir) {
            allowHostsSet = true
            allowHosts = listOf("site.example")
        }
        assertNotNull(r.store.files[id], "ホスト済みのファイルはそのまま")
        val allowed = url(s, "/ok.png")
        val blocked = url(s, "/no.png", "other.example")
        r.sign(1, hosted)
        r.sign(2, allowed)
        r.sign(3, blocked)
        r.store.candidates[blocked] = 1 // 設定を変える前からの候補
        r.tickAndSettle()
        assertNotNull(r.store.bySource[allowed])
        assertNull(r.store.bySource[blocked])
        assertFalse(r.store.candidates.containsKey(blocked), "許可していないホストは候補から外す")
        assertEquals(0, s.requests.count { it.contains("no.png") })
        assertNotNull(r.store.files[id])
    }

    // ---- 承認 ----

    @Test
    fun approvalModeWaitsForAnAdmin(@TempDir dir: Path) {
        val s = serve { sock, req -> reply(sock, body(req)) }
        val r = Rig(dir) { requireApproval = true }
        val good = url(s, "/good.png")
        val bad = url(s, "/bad.png")
        r.sign(1, good, alice)
        r.sign(2, bad, alice)
        r.tickAndSettle()
        assertEquals(0, s.requests.size, "承認されるまで読まない")
        assertEquals(setOf(good, bad), r.m.pendingApprovals().toSet())

        fun act(call: (String, String, (String?) -> Unit) -> Unit, url: String): String? {
            val done = CountDownLatch(1)
            var result: String? = null
            call(HostedStore.urlKey(url), "admin") { result = it; done.countDown() }
            assertTrue(done.await(30, TimeUnit.SECONDS))
            return result
        }
        assertEquals(good, act(r.m::approve, good))
        assertEquals(bad, act(r.m::reject, bad))
        assertNull(act(r.m::approve, "https://not.example/pending.png"), "承認待ちにない URL")
        assertTrue(bad in r.store.denied)
        assertEquals(listOf<String>(), r.m.pendingApprovals())

        r.tickAndSettle()
        assertNotNull(r.store.bySource[good])
        assertFalse(good in r.store.approved, "ホストしたら、承認の記録は要らない")
        assertEquals(0, s.requests.count { it.contains("bad.png") })
        r.tickAndSettle()
        assertFalse(r.store.candidates.containsKey(bad), "断った URL は候補に戻らない")
    }

    @Test
    fun approvalIsOffByDefaultAndKeysAreStable() {
        assertFalse(HostingSettings().requireApproval)
        assertTrue(HostedStore.urlKey("https://a.example/a.png").matches(HostedStore.ID))
        assertEquals(HostedStore.urlKey("https://a.example/a.png"), HostedStore.urlKey("https://a.example/a.png"))
        assertFalse(HostedStore.urlKey("https://a.example/a.png") == HostedStore.urlKey("https://a.example/b.png"))
    }

    // ---- 監査ログ ----

    private fun auditLines(r: Rig) = Files.readAllLines(r.auditFile).map { JsonParser.parseString(it).asJsonObject }

    @Test
    fun hostedContentIsAudited(@TempDir dir: Path) {
        val s = serve { sock, req -> reply(sock, body(req)) }
        val r = Rig(dir)
        val u = url(s, "/a.png")
        val bytes = body("GET /a.png HTTP/1.1")
        r.sign(1, u, alice)
        r.tickAndSettle()
        val id = r.store.bySource[u]!!
        val sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))
        var lines = auditLines(r)
        assertEquals(1, lines.size)
        val hosted = lines[0]
        assertEquals(AuditEntry.HOSTED, hosted.get("kind").asString)
        assertEquals(id, hosted.get("fileId").asString)
        assertEquals(sha, hosted.get("sha256").asString)
        assertEquals(bytes.size.toLong(), hosted.get("size").asLong)
        assertEquals(u, hosted.get("url").asString)
        assertEquals(r.key(1), hosted.get("sign").asString)
        assertEquals(alice.toString(), hosted.get("player").asString)

        // 看板を書き換えたら、その看板の分も残す
        val f = r.store.files[id]!!
        val rewrite = HostedStore.Rewrite("world", 1, 64, 0, u, r.m.urlOf(f))
        r.m.onWorker {
            r.m.rewritten(listOf(rewrite to SignRewriter.Outcome.DONE))
            r.m.tick()
        }
        lines = auditLines(r)
        assertEquals(AuditEntry.SIGN_REWRITTEN, lines[1].get("kind").asString)
        assertEquals(r.key(1), lines[1].get("sign").asString)
        assertEquals(alice.toString(), lines[1].get("player").asString)
        assertEquals(u, lines[1].get("url").asString)

        // 取り下げも残す。前の行は書き換えない
        val done = CountDownLatch(1)
        r.m.remove(id) { done.countDown() }
        assertTrue(done.await(30, TimeUnit.SECONDS))
        lines = auditLines(r)
        assertEquals(listOf(AuditEntry.HOSTED, AuditEntry.SIGN_REWRITTEN, AuditEntry.REMOVED), lines.map { it.get("kind").asString })
    }

    @Test
    fun aSecondUrlWithTheSameContentIsAuditedAndNotCharged(@TempDir dir: Path) {
        val same = "same".toByteArray(StandardCharsets.US_ASCII)
        val s = serve { sock, _ -> reply(sock, same) }
        val r = Rig(dir) { perPlayerFiles = 5 }
        r.sign(1, url(s, "/a.png"), alice)
        r.tickAndSettle()
        r.sign(2, url(s, "/b.png"), bob)
        r.tickAndSettle()
        assertEquals(1, r.store.files.size)
        assertEquals(alice.toString(), r.store.files.values.single().owner, "同じ中身は、最初の人の分のまま")
        assertEquals(listOf(AuditEntry.HOSTED, AuditEntry.SAME_CONTENT), auditLines(r).map { it.get("kind").asString })
        assertEquals(bob.toString(), auditLines(r)[1].get("player").asString)
    }

    @Test
    fun unusedFilesAreAuditedWhenTheyAreUnhosted(@TempDir dir: Path) {
        val s = serve { sock, req -> reply(sock, body(req)) }
        val r = Rig(dir)
        r.sign(1, url(s, "/a.png"), alice)
        r.tickAndSettle()
        r.signs.clear()
        r.now.addAndGet((r.cfg.unhostAfterDays + 1) * 24 * 3_600_000L)
        r.tick()
        assertTrue(r.store.files.isEmpty())
        assertEquals(AuditEntry.UNHOSTED, auditLines(r).last().get("kind").asString)
    }

    @Test
    fun theDatabaseStorageKeepsOwnersAndAudit(@TempDir dir: Path) {
        val s = serve { sock, req -> reply(sock, body(req)) }
        val name = UUID.randomUUID().toString()
        val url = "jdbc:h2:mem:$name;MODE=MySQL;DB_CLOSE_DELAY=-1"
        val configure: HostingSettings.() -> Unit = {
            storage = HostingSettings.DATABASE
            dbUrl = url
            dbUser = "sa"
        }
        val first = Rig(dir, configure)
        val u = url(s, "/a.png")
        first.sign(1, u, alice)
        first.tickAndSettle()
        val id = first.store.bySource[u]!!
        first.stop()

        val db = Db(url, "sa", "", logger)
        val row = db.use { c ->
            db.query(c, "SELECT kind, file_id, sign_key, player_uuid, url FROM msc_hosted_audit") { rs ->
                assertTrue(rs.next())
                listOf(rs.getString(1), rs.getString(2).trim(), rs.getString(3), rs.getString(4), rs.getString(5))
            }
        }
        assertEquals(listOf(AuditEntry.HOSTED, id, "world:1:64:0", alice.toString(), u), row)
        db.close()

        val second = Rig(dir, configure)
        assertEquals(alice.toString(), second.store.files[id]!!.owner)
        assertEquals(alice.toString(), second.store.authors[second.key(1)]!!.uuid)
    }

    // ---- 設定 ----

    @Test
    fun defaultsAreSafeWhenTheProductionConfigHasNoNewKeys() {
        val h = HostingSettings.read(YamlConfiguration()) { throw AssertionError(it) }
        assertEquals(100, h.perPlayerFiles)
        assertEquals(256, h.perPlayerMb)
        assertEquals(20_000, h.maxTotalFiles)
        assertEquals(2, h.downloadConcurrency)
        assertFalse(h.allowHostsSet)
        assertFalse(h.requireApproval)
        assertTrue(h.hostPermitted("anything.example"))

        HostingHardeningTest::class.java.getResourceAsStream("/config.yml").use { input ->
            val yaml = YamlConfiguration.loadConfiguration(InputStreamReader(input!!, StandardCharsets.UTF_8))
            val bundled = HostingSettings.read(yaml) { throw AssertionError(it) }
            assertEquals(h.perPlayerFiles, bundled.perPlayerFiles, "config.yml の値と、コードの既定は同じ")
            assertEquals(h.perPlayerMb, bundled.perPlayerMb)
            assertEquals(h.maxTotalFiles, bundled.maxTotalFiles)
            assertEquals(h.downloadConcurrency, bundled.downloadConcurrency)
            assertEquals(h.requireApproval, bundled.requireApproval)
            assertEquals(h.allowHostsSet, bundled.allowHostsSet)
        }

        val y = YamlConfiguration()
        y.set("hosting.download-concurrency", 100)
        y.set("hosting.quota.per-player-files", -1)
        y.set("hosting.quota.per-player-mb", 0)
        y.set("hosting.max-total-files", 0)
        val clamped = HostingSettings.read(y) { throw AssertionError(it) }
        assertEquals(8, clamped.downloadConcurrency)
        assertEquals(0, clamped.perPlayerFiles)
        assertEquals(0, clamped.perPlayerMb)
        assertEquals(1, clamped.maxTotalFiles)
    }

    @Test
    fun quotaArithmetic() {
        val f1 = HostedStore.HostedFile("aaaaaaaaaaaa", "png", 100, 1, 1).also { it.owner = "a" }
        val f2 = HostedStore.HostedFile("bbbbbbbbbbbb", "png", 200, 1, 1)
        val q = HostingQuota(2, 1000, listOf(f1, f2), listOf(HostingQuota.Reservation("a", 300), HostingQuota.Reservation(null, 999)))
        assertEquals(2, q.filesOf("a"), "読んでいる途中の分も数える")
        assertEquals(400L, q.bytesOf("a"))
        assertEquals(0L, q.room("a"), "数の上限")
        assertEquals(1000L, q.room("b"))
        assertEquals(1000L, HostingQuota(0, 1000, listOf(f1)).room("b"))
        assertEquals(900L, HostingQuota(0, 1000, listOf(f1)).room("a"))
        assertEquals(Long.MAX_VALUE, HostingQuota(5, 0, listOf(f1)).room("a"), "0 は上限なし")
        assertEquals(0L, HostingQuota(5, 50, listOf(f1)).room("a"), "使いすぎでも負にしない")

        val author = { uuid: String, at: Long -> HostedStore.SignAuthor(uuid, "https://a.example/a.png", at) }
        val open = HostingQuota(1, 1000, listOf())
        assertEquals(HostingQuota.Choice(null, Long.MAX_VALUE), open.choose(listOf()))
        assertEquals(HostingQuota.Choice(null, Long.MAX_VALUE), open.choose(listOf(author("a", 1), null)))
        assertEquals("a", open.choose(listOf(author("b", 2), author("a", 1)))!!.owner, "先に書いた人")
        assertEquals("b", open.choose(listOf(author("b", 2), author("a", 1))) { it != "a" }!!.owner)
        assertNull(open.choose(listOf(author("a", 1))) { false })
    }
}
