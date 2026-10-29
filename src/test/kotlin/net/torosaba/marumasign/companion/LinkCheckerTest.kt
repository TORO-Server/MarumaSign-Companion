package net.torosaba.marumasign.companion

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.URI
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.logging.Handler
import java.util.logging.LogRecord
import java.util.logging.Logger

/** 検査の割り当て (負荷の抑制、優先、上限)、保存と読み込み */
class LinkCheckerTest {

    private val clock = AtomicLong(T0)
    private val probed: MutableList<String> = CopyOnWriteArrayList()
    private val logged: MutableList<String> = CopyOnWriteArrayList()
    private val log: Logger = Logger.getLogger("linkchecker-test-" + System.nanoTime())
    private val changes = AtomicInteger()

    init {
        log.useParentHandlers = false
        log.addHandler(object : Handler() {
            override fun publish(r: LogRecord) {
                logged.add(r.level.toString() + " " + r.message)
            }

            override fun flush() {
            }

            override fun close() {
            }
        })
    }

    private fun cfg(): LinkSettings {
        val c = LinkSettings()
        c.startupDelayMinutes = 5
        c.hostMinIntervalSeconds = 0
        c.concurrency = 2
        c.hostRules = ArrayList()
        return c
    }

    private fun checker(file: Path, c: LinkSettings, prober: LinkProbe.Prober, throttle: HostThrottle): LinkChecker {
        return LinkChecker(log, file, c, { u -> u.startsWith("https://") && u.length < 2000 }, prober, throttle, { clock.get() }, { changes.incrementAndGet() })
    }

    private fun checker(file: Path, c: LinkSettings): LinkChecker {
        return checker(file, c, { u ->
            probed.add(u)
            LinkProbe.Result.ok(200)
        }, HostThrottle(c))
    }

    @Test
    fun waitsForTheStartupDelayBeforeTheFirstCheck(@TempDir dir: Path) {
        val c = checker(dir.resolve("l.json"), cfg())
        c.track(listOf(url(1), url(2)))
        assertEquals(0, c.step(T0))
        assertEquals(0, c.step(T0 + 4 * MIN))
        assertEquals(2, c.step(T0 + 5 * MIN))
        assertEquals(2, probed.size)
    }

    @Test
    fun sameUrlOnManySignsIsCheckedOnce(@TempDir dir: Path) {
        val c = checker(dir.resolve("l.json"), cfg())
        c.track(listOf(url(1), url(1), url(1)))
        c.track(listOf(url(1)))
        assertEquals(1, c.tracked())
        c.step(T0 + 6 * MIN)
        assertEquals(listOf(url(1)), probed)
        // 次の期限 (7 日) までは、もう検査しない
        clock.set(T0 + 7 * MIN)
        assertEquals(0, c.step(T0 + 7 * MIN))
        assertEquals(LinkStatus.OK, c.state(url(1))!!.status)
    }

    @Test
    fun newOrEditedUrlsAreCheckedFirst(@TempDir dir: Path) {
        val s = cfg()
        s.concurrency = 1
        val c = checker(dir.resolve("l.json"), s)
        c.track(listOf(url(1), url(2), url(3)))
        c.prioritize(url(3))
        c.step(T0 + 6 * MIN)
        assertEquals(listOf(url(3)), probed)
    }

    @Test
    fun unacceptableUrlsAreNeverTracked(@TempDir dir: Path) {
        val c = checker(dir.resolve("l.json"), cfg())
        c.track(listOf("http://insecure.example/a", "javascript:alert(1)", null, "https://" + "a".repeat(3000), url(1)))
        assertEquals(1, c.tracked())
        c.prioritize("ftp://x.example/")
        assertEquals(1, c.tracked())
        assertEquals(0, c.recheck("https://not-tracked.example/"))
    }

    @Test
    fun sameHostIsNotStartedTwiceInOneRoundAndRespectsTheInterval(@TempDir dir: Path) {
        val s = cfg()
        s.hostMinIntervalSeconds = 2
        val throttle = HostThrottle(s)
        val c = checker(dir.resolve("l.json"), s, { u ->
            probed.add(u)
            throttle.reserve(URI.create(u).host, clock.get())
            LinkProbe.Result.ok(200)
        }, throttle)
        c.track(listOf("https://same.example/1.png", "https://same.example/2.png", "https://other.example/3.png"))
        clock.set(T0 + 6 * MIN)
        assertEquals(2, c.step(clock.get()), "同じホストは 1 件ずつ (別のホストは同時に)")
        clock.set(T0 + 6 * MIN + 100)
        assertEquals(0, c.step(clock.get()))
        clock.set(T0 + 6 * MIN + 4000)
        assertEquals(1, c.step(clock.get()))
        assertEquals(3, probed.size)
    }

    @Test
    fun perHostHourlyCapDefersTheRest(@TempDir dir: Path) {
        val s = cfg()
        s.hostPerHourLimit = 2
        s.concurrency = 4
        val throttle = HostThrottle(s)
        val c = checker(dir.resolve("l.json"), s, { u ->
            probed.add(u)
            throttle.reserve(URI.create(u).host, clock.get())
            LinkProbe.Result.ok(200)
        }, throttle)
        val urls = ArrayList<String>()
        for (i in 0 until 6) urls.add("https://busy.example/$i.png")
        c.track(urls)
        var t = T0 + 6 * MIN
        for (i in 0 until 6) {
            c.step(t)
            t += 10_000
        }
        assertEquals(2, probed.size, "1 時間に 2 件まで")
        t += H
        c.step(t)
        assertEquals(3, probed.size, "1 時間たてば、また進む")
    }

    @Test
    fun globalHourlyLimitStopsNewChecks(@TempDir dir: Path) {
        val s = cfg()
        s.perHourLimit = 3
        s.concurrency = 4
        val c = checker(dir.resolve("l.json"), s)
        val urls = ArrayList<String>()
        for (i in 0 until 10) urls.add(url(i))
        c.track(urls)
        var t = T0 + 6 * MIN
        var started = 0
        for (i in 0 until 5) {
            started += c.step(t)
            t += 1000
        }
        assertEquals(3, started)
        assertEquals(3, c.counts().hourUsed)
        assertTrue(c.step(t + H + 1000) > 0)
    }

    @Test
    fun concurrencyIsLimited(@TempDir dir: Path) {
        val s = cfg()
        s.concurrency = 2
        s.startupDelayMinutes = 0
        val running = AtomicInteger()
        val max = AtomicInteger()
        val release = CountDownLatch(1)
        val twoRunning = CountDownLatch(2)
        val c = LinkChecker(log, dir.resolve("l.json"), s, { true }, { _ ->
            max.accumulateAndGet(running.incrementAndGet()) { a, b -> Math.max(a, b) }
            twoRunning.countDown()
            try {
                release.await(10, TimeUnit.SECONDS)
            } finally {
                running.decrementAndGet()
            }
            LinkProbe.Result.ok(200)
        }, HostThrottle(s), { System.currentTimeMillis() }, {
        })
        val urls = ArrayList<String>()
        for (i in 0 until 10) urls.add(url(i))
        c.track(urls)
        c.start()
        try {
            assertTrue(twoRunning.await(10, TimeUnit.SECONDS))
            Thread.sleep(1500)
            assertEquals(2, max.get(), "同時に検査するのは 2 件まで")
            assertEquals(2, c.counts().inFlight)
        } finally {
            release.countDown()
            c.stop()
        }
    }

    @Test
    fun stopEndsTheThreadsAndSavesTheResults(@TempDir dir: Path) {
        val s = cfg()
        s.startupDelayMinutes = 0
        val file = dir.resolve("l.json")
        val c = LinkChecker(log, file, s, { true }, { LinkProbe.Result.ok(200) }, HostThrottle(s), { System.currentTimeMillis() }, {
        })
        c.track(listOf(url(1)))
        c.start()
        c.stop()
        assertTrue(Files.isRegularFile(file))
        Thread.sleep(300)
        val alive = Thread.getAllStackTraces().keys.stream()
            .filter { t -> t.name.startsWith("MarumaSignCompanion-linkcheck") && t.isAlive }.count()
        assertEquals(0L, alive, "止めたあとに、検査のスレッドが残らない")
    }

    @Test
    fun queueLimitDropsTheOldestPendingAndLogsOnce(@TempDir dir: Path) {
        val s = cfg()
        s.maxTrackedUrls = 100
        val c = checker(dir.resolve("l.json"), s)
        for (i in 0 until 150) {
            clock.addAndGet(10)
            c.track(listOf(url(i)))
        }
        assertTrue(c.tracked() <= 100, "上限を超えない: " + c.tracked())
        assertNull(c.state(url(0)), "古い未検査から落ちる")
        assertNotNull(c.state(url(149)))
        assertEquals(1L, logged.stream().filter { l -> l.contains("queue is full") }.count(), "ログは 1 行だけ")
    }

    @Test
    fun failingProberBecomesUnknownAndTheLogCarriesNoSecrets(@TempDir dir: Path) {
        val c = checker(dir.resolve("l.json"), cfg(), { _ ->
            throw IllegalStateException("SECRET-MESSAGE token=SECRET")
        }, HostThrottle(cfg()))
        c.track(listOf("https://secret.example/a.png?token=SECRET#frag"))
        c.step(T0 + 6 * MIN)
        val st = c.state("https://secret.example/a.png?token=SECRET#frag")!!
        assertEquals(LinkStatus.UNKNOWN, st.status)
        for (line in logged) {
            assertFalse(line.contains("SECRET"), line)
            assertFalse(line.contains("token"), line)
        }
        assertTrue(logged.stream().anyMatch { l -> l.contains("IllegalStateException") && l.contains("secret.example/a.png") })
    }

    @Test
    fun onChangeIsCalledOnlyWhenTheStatusOrReasonChanges(@TempDir dir: Path) {
        val c = checker(dir.resolve("l.json"), cfg())
        c.track(listOf(url(1)))
        c.step(T0 + 6 * MIN)
        assertEquals(1, changes.get(), "PENDING → OK")
        clock.set(T0 + 8 * 24 * H)
        c.step(clock.get())
        assertEquals(1, changes.get(), "OK のまま変わらなければ呼ばない")
    }

    @Test
    fun recheckMakesUrlsDueAtOnce(@TempDir dir: Path) {
        val c = checker(dir.resolve("l.json"), cfg(), { LinkProbe.Result.broken(LinkReason.HTTP_404, 404) }, HostThrottle(cfg()))
        c.track(listOf(url(1), url(2)))
        c.step(T0 + 6 * MIN)
        assertEquals(2, c.counts().broken)
        assertEquals(0, c.step(T0 + 7 * MIN))
        assertEquals(2, c.recheckAllBroken())
        assertEquals(2, c.step(T0 + 7 * MIN))
        assertEquals(1, c.recheck(url(1)))
    }

    // ---- 保存と読み込み ----

    @Test
    fun resultsSurviveARestart(@TempDir dir: Path) {
        val file = dir.resolve("data").resolve("linkcheck.json")
        val a = checker(file, cfg(), { u ->
            if (u.endsWith("/1.png")) LinkProbe.Result.broken(LinkReason.HTTP_404, 404)
            else LinkProbe.Result.ok(200)
        }, HostThrottle(cfg()))
        a.track(listOf("https://x1.example/1.png", "https://x2.example/2.png", "https://x3.example/3.png"))
        a.step(T0 + 6 * MIN)
        a.step(T0 + 6 * MIN + 5000)
        a.save()
        assertFalse(Files.exists(file.resolveSibling("linkcheck.json.tmp")), "一時ファイルは残さない")

        val b = checker(file, cfg())
        b.load()
        assertEquals(3, b.tracked())
        val s1 = b.state("https://x1.example/1.png")!!
        assertEquals(a.state("https://x1.example/1.png"), s1)
        assertEquals(LinkStatus.BROKEN, s1.status)
        assertEquals(LinkReason.HTTP_404, s1.reason)
        assertEquals(404, s1.httpCode)
        assertTrue(s1.firstFailedAt > 0 && s1.lastCheckedAt > 0 && s1.consecutiveFailures == 1)
        val json = Files.readString(file, StandardCharsets.UTF_8)
        for (key in listOf("status", "httpCode", "reason", "firstFailedAt", "lastFailedAt", "lastOkAt", "lastCheckedAt", "consecutiveFailures")) {
            assertTrue(json.contains("\"" + key + "\""), key)
        }
    }

    @Test
    fun oldUnseenEntriesArePrunedOnSave(@TempDir dir: Path) {
        val file = dir.resolve("l.json")
        val a = checker(file, cfg())
        a.track(listOf(url(1)))
        clock.addAndGet(40L * 24 * H)
        a.track(listOf(url(2)))
        a.save()
        assertNull(a.state(url(1)))
        assertNotNull(a.state(url(2)))
    }

    private fun loadFrom(file: Path, content: String): LinkChecker {
        Files.writeString(file, content, StandardCharsets.UTF_8)
        val c = checker(file, cfg())
        c.load()
        return c
    }

    @Test
    fun corruptFilesAreMovedAsideAndWeStartEmpty(@TempDir dir: Path) {
        val good = ("{\"status\":\"OK\",\"httpCode\":200,\"reason\":\"OK\",\"firstFailedAt\":0,\"lastFailedAt\":0,\"lastOkAt\":1,"
            + "\"lastCheckedAt\":1,\"consecutiveFailures\":0,\"nextCheckAt\":1,\"lastSeenAt\":1}")
        val corrupt = listOf(
            "not json at all", "[]", "{}", "{\"version\":2,\"links\":{}}", "{\"version\":1}", "{\"version\":1,\"links\":[]}",
            "{\"version\":1,\"links\":{\"https://a.example/x\":5}}",
            "{\"version\":1,\"links\":{\"https://a.example/x\":" + good.replace("\"OK\",\"httpCode\"", "\"WEIRD\",\"httpCode\"") + "}}",
            "{\"version\":1,\"links\":{\"https://a.example/x\":" + good.replace("\"httpCode\":200", "\"httpCode\":99999") + "}}",
            "{\"version\":1,\"links\":{\"https://a.example/x\":" + good.replace("\"consecutiveFailures\":0", "\"consecutiveFailures\":-5") + "}}",
            "{\"version\":1,\"links\":{\"https://a.example/x\":" + good.replace("\"lastOkAt\":1", "\"lastOkAt\":-1") + "}}",
            "{\"version\":1,\"links\":{\"https://a.example/x\":" + good.replace("\"lastOkAt\":1", "\"lastOkAt\":999999999999999999") + "}}",
            "{\"version\":1,\"links\":{\"https://a.example/x\":" + good.replace("\"httpCode\":200,", "") + "}}",
            "{\"version\":1,\"links\":{\"https://a.example/x\":" + good.replace("\"reason\":\"OK\"", "\"reason\":\"<script>alert(1)</script>\"") + "}}")
        var n = 0
        for (content in corrupt) {
            val file = dir.resolve("c" + (n++) + ".json")
            val c = loadFrom(file, content)
            assertEquals(0, c.tracked(), content)
            assertFalse(Files.exists(file), "壊れたファイルは退避する: $content")
            Files.list(dir).use { files ->
                assertTrue(files.anyMatch { p -> p.fileName.toString().startsWith(file.fileName.toString() + ".corrupt-") }, content)
            }
        }
        assertTrue(logged.stream().anyMatch { l -> l.contains("could not be read") })
    }

    @Test
    fun loadedUrlsMustPassTheCurrentRulesAndAreNotTrusted(@TempDir dir: Path) {
        val entry = ("{\"status\":\"BROKEN\",\"httpCode\":404,\"reason\":\"HTTP_404\",\"firstFailedAt\":1,\"lastFailedAt\":1,\"lastOkAt\":0,"
            + "\"lastCheckedAt\":1,\"consecutiveFailures\":1,\"nextCheckAt\":1,\"lastSeenAt\":" + T0 + "}")
        val c = loadFrom(dir.resolve("l.json"), ("{\"version\":1,\"links\":{"
            + "\"https://ok.example/a.png\":" + entry + ",\"javascript:alert(1)\":" + entry + ",\"http://plain.example/a\":" + entry
            + ",\"https://a.example/<script>\":" + entry.replace("\"BROKEN\"", "\"OK\"") + "}}"))
        assertNotNull(c.state("https://ok.example/a.png"))
        assertNull(c.state("javascript:alert(1)"))
        assertNull(c.state("http://plain.example/a"))
        assertEquals(1, c.tracked() - (if (c.state("https://a.example/<script>") == null) 0 else 1), "acceptable を通る URL だけ読む (ここでは <script> も通る形)")
    }

    @Test
    fun missingFileStartsEmptyWithoutWarnings(@TempDir dir: Path) {
        val c = checker(dir.resolve("none.json"), cfg())
        c.load()
        assertEquals(0, c.tracked())
        assertTrue(logged.isEmpty())
    }

    companion object {
        private const val MIN = 60_000L
        private const val H = 3_600_000L
        private const val T0 = 5_000_000_000L

        private fun url(i: Int): String {
            return "https://host$i.example/a.png"
        }
    }
}
