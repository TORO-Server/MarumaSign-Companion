package net.torosaba.marumasign.companion

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.IOException
import java.net.URI
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.EnumMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ThreadLocalRandom
import java.util.function.LongSupplier
import java.util.function.Predicate
import java.util.logging.Logger

/**
 * 看板の URL のリンク切れを、少しずつ・非同期で検査して、結果を覚える。メインスレッドでは何もしない。
 * - URL 単位 (同じ URL の看板が複数あっても 1 回)。新しい URL・編集された URL は先に検査する。
 * - 負荷: 同時に検査するのは少数、同じホストには間隔を空ける、全体と 1 ホストの 1 時間あたりの上限、起動後しばらく待つ。
 * - 結果は JSON に保存して、再起動後も引き継ぐ (読むときは中身を検証する。壊れていればバックアップして空から)。
 *
 * @param acceptable 検査してよい URL か ([LinkProbe.strictParse] を使う)
 * @param onChange   結果が変わったときに呼ぶ (まとめて反映するのは呼び出し側)
 */
class LinkChecker internal constructor(
    private val log: Logger,
    private val file: Path,
    private val cfg: LinkSettings,
    private val acceptable: Predicate<String>,
    private val prober: LinkProbe.Prober,
    private val throttle: HostThrottle,
    private val clock: LongSupplier,
    private val onChange: Runnable,
) {

    private class Entry(@Volatile var state: LinkState) {
        @Volatile
        var priority = false
    }

    data class Counts(
        val ok: Int,
        val broken: Int,
        val unknown: Int,
        val pending: Int,
        val inFlight: Int,
        val hourUsed: Int,
    )

    private val entries = ConcurrentHashMap<String, Entry>()
    private val inFlight: MutableSet<String> = ConcurrentHashMap.newKeySet()
    @Volatile
    private var dirty = false
    @Volatile
    private var stopped = false
    @Volatile
    private var startAfter: Long = clock.asLong + cfg.startupDelayMinutes * 60_000L
    @Volatile
    private var lastOverflowLog: Long = 0
    private var workers: ExecutorService? = null
    private var scheduler: Thread? = null

    // ---- 起動と停止 ----

    /** 保存した結果を読んで、検査を始める (専用のデーモンスレッド) */
    @Synchronized
    fun start() {
        load()
        if (scheduler != null) return
        stopped = false
        workers = Executors.newFixedThreadPool(LinkSettings.clamp(cfg.concurrency, 1, 4)) { r ->
            val t = Thread(r, "MarumaSignCompanion-linkcheck")
            t.isDaemon = true
            t.priority = Thread.MIN_PRIORITY
            t
        }
        val s = Thread(this::run, "MarumaSignCompanion-linkcheck-scheduler")
        scheduler = s
        s.isDaemon = true
        s.priority = Thread.MIN_PRIORITY
        s.start()
    }

    /** 止める (実行中の検査は中断して、開いているソケットを閉じる。閉じないと、止まっている read が残る)。結果を保存する */
    @Synchronized
    fun stop() {
        stopped = true
        scheduler?.interrupt()
        scheduler = null
        workers?.shutdownNow()
        workers = null
        try {
            prober.shutdown()
        } catch (e: RuntimeException) {
            log.warning("Link check shutdown error: " + e.javaClass.simpleName)
        }
        save()
    }

    private fun run() {
        var lastSave = clock.asLong
        try {
            while (!stopped) {
                val now = clock.asLong
                try {
                    step(now)
                    if (dirty && now - lastSave > 60_000) {
                        save()
                        lastSave = now
                    }
                } catch (e: RuntimeException) {
                    log.warning("Link check scheduler error: " + e.javaClass.simpleName)
                }
                Thread.sleep(1000)
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    // ---- 追跡 ----

    /**
     * いま看板にある URL を知らせる。新しいものは PENDING で追加する。
     * 先に、すでにある URL を「いま看板にある」と記録してから、新しいものを足す (上限を超えたら、
     * いま看板にない URL (しばらく知らされていないもの) から落とし、なければ古い PENDING から落とす)。
     */
    fun track(urls: Collection<String?>) {
        val now = clock.asLong
        val fresh = ArrayList<String>()
        for (url in urls) {
            if (url == null) continue
            val e = entries[url]
            if (e != null) {
                if (now - e.state.lastSeenAt > SEEN_REFRESH_MILLIS) e.state = e.state.withSeen(now)
            } else {
                fresh.add(url)
            }
        }
        for (url in fresh) {
            if (entries.containsKey(url) || !acceptable.test(url)) continue
            if (entries.size >= cfg.maxTrackedUrls && !makeRoom(now)) continue
            entries.putIfAbsent(url, Entry(LinkState.pending(now)))
            dirty = true
        }
    }

    @Volatile
    private var roomFailedAt: Long = 0

    // 少し空ける。空けられなければ false (続けて失敗したら、しばらく探さない: 毎回全部を調べて CPU を使わないため)
    private fun makeRoom(now: Long): Boolean {
        if (now - roomFailedAt < 60_000L) return false
        val stale = ArrayList<Map.Entry<String, Entry>>()
        val pending = ArrayList<Map.Entry<String, Entry>>()
        for (e in entries.entries) {
            if (inFlight.contains(e.key)) continue
            val s = e.value.state
            if (now - s.lastSeenAt > STALE_MILLIS) stale.add(e)
            else if (s.status == LinkStatus.PENDING) pending.add(e)
        }
        val victims = if (stale.isEmpty()) pending else stale
        if (victims.isEmpty()) {
            roomFailedAt = now
            return false
        }
        victims.sortWith { a, b -> java.lang.Long.compare(a.value.state.lastSeenAt, b.value.state.lastSeenAt) }
        val drop = Math.max(1, Math.min(victims.size, cfg.maxTrackedUrls / 10))
        for (i in 0 until drop) entries.remove(victims[i].key)
        if (now - lastOverflowLog > 60_000) {
            lastOverflowLog = now
            log.warning("Link check queue is full (" + cfg.maxTrackedUrls + "); dropped " + drop + (if (stale.isEmpty()) " oldest pending" else " stale") + " URLs")
        }
        return true
    }

    /** 新しい・編集された URL を先に検査する */
    fun prioritize(url: String?) {
        if (url == null) return
        track(listOf(url))
        val e = entries[url]
        if (e != null && e.state.status == LinkStatus.PENDING) e.priority = true
    }

    fun state(url: String): LinkState? {
        val e = entries[url]
        return e?.state
    }

    /** すぐ再検査する (追跡している URL だけ)。当てはまった件数 */
    fun recheck(url: String): Int {
        val e = entries[url] ?: return 0
        e.state = e.state.withNext(0)
        e.priority = true
        dirty = true
        return 1
    }

    fun recheckAllBroken(): Int {
        var n = 0
        for (e in entries.values) {
            if (e.state.status == LinkStatus.BROKEN) {
                e.state = e.state.withNext(0)
                e.priority = true
                n++
            }
        }
        if (n > 0) dirty = true
        return n
    }

    fun counts(): Counts {
        val c = EnumMap<LinkStatus, Int>(LinkStatus::class.java)
        for (e in entries.values) c.merge(e.state.status, 1) { a, b -> a + b }
        return Counts(c.getOrDefault(LinkStatus.OK, 0), c.getOrDefault(LinkStatus.BROKEN, 0),
            c.getOrDefault(LinkStatus.UNKNOWN, 0), c.getOrDefault(LinkStatus.PENDING, 0), inFlight.size,
            throttle.globalUsed(clock.asLong))
    }

    fun tracked(): Int {
        return entries.size
    }

    // ---- 検査の割り当て ----

    /** 検査してよいものがあれば、空きの分だけ始める。始めた数 (テストからも呼ぶ) */
    internal fun step(now: Long): Int {
        if (now < startAfter || stopped) return 0
        var slots = LinkSettings.clamp(cfg.concurrency, 1, 4) - inFlight.size
        if (slots <= 0 || !throttle.globalAllowed(now)) return 0
        // 期限が来ていて、ホストに空きがあるもののうち、先に検査するもの (優先 → 期限の早い順)
        val due = ArrayList<Map.Entry<String, Entry>>()
        for (e in entries.entries) {
            val s = e.value.state
            if (s.nextCheckAt > now || inFlight.contains(e.key)) continue
            due.add(e)
        }
        due.sortWith { a, b ->
            val p = java.lang.Boolean.compare(b.value.priority, a.value.priority)
            if (p != 0) p else java.lang.Long.compare(a.value.state.nextCheckAt, b.value.state.nextCheckAt)
        }
        var started = 0
        val usedHosts = HashSet<String>()
        for (e in due) {
            if (slots <= 0 || !throttle.globalAllowed(now)) break
            val host = hostOf(e.key)
            if (host == null) {
                entries.remove(e.key)
                continue
            }
            // 同じ回に同じホストを 2 つ始めない (間隔は始めたあとに予約されるため)
            if (usedHosts.contains(host) || !throttle.isReady(host, now)) continue
            usedHosts.add(host)
            if (!inFlight.add(e.key)) continue
            throttle.globalRecord(now)
            slots--
            started++
            submit(e.key, e.value)
        }
        return started
    }

    private fun submit(url: String, entry: Entry) {
        val w = workers
        val task = Runnable { execute(url, entry) }
        if (w == null) {
            task.run()
            return
        }
        try {
            w.execute(task)
        } catch (e: RuntimeException) {
            inFlight.remove(url)
        }
    }

    /** 1 件の検査 (テストでは step から同じスレッドで呼ばれる) */
    private fun execute(url: String, entry: Entry) {
        try {
            val result: LinkProbe.Result = try {
                prober.probe(url)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                return
            } catch (e: RuntimeException) {
                log.warning("Link check failed for " + SafeText.logUrl(url) + ": " + e.javaClass.simpleName)
                LinkProbe.Result.unknown(LinkReason.UNEXPECTED_RESPONSE, 0, true)
            }
            val before = entry.state
            val after = before.apply(result, clock.asLong, cfg, ThreadLocalRandom.current().nextDouble(-1.0, 1.0))
            entry.state = after
            entry.priority = false
            dirty = true
            if (before.status != after.status || before.reason != after.reason) {
                try {
                    onChange.run()
                } catch (e: RuntimeException) {
                    log.warning("Link check callback failed: " + e.javaClass.simpleName)
                }
            }
        } finally {
            inFlight.remove(url)
        }
    }

    // ---- 保存と読み込み ----

    @Synchronized
    fun save() {
        dirty = false
        val now = clock.asLong
        val root = JsonObject()
        root.addProperty("version", FILE_VERSION)
        val links = JsonObject()
        for (e in entries.entries) {
            val s = e.value.state
            if (now - s.lastSeenAt > PRUNE_AFTER_MILLIS) {
                entries.remove(e.key)
                continue
            }
            val o = JsonObject()
            o.addProperty("status", s.status.name)
            o.addProperty("httpCode", s.httpCode)
            o.addProperty("reason", s.reason.name)
            o.addProperty("firstFailedAt", s.firstFailedAt)
            o.addProperty("lastFailedAt", s.lastFailedAt)
            o.addProperty("lastOkAt", s.lastOkAt)
            o.addProperty("lastCheckedAt", s.lastCheckedAt)
            o.addProperty("consecutiveFailures", s.consecutiveFailures)
            o.addProperty("nextCheckAt", s.nextCheckAt)
            o.addProperty("lastSeenAt", s.lastSeenAt)
            links.add(e.key, o)
        }
        root.add("links", links)
        try {
            Files.createDirectories(file.parent)
            val tmp = file.resolveSibling(file.fileName.toString() + ".tmp")
            Files.newBufferedWriter(tmp, StandardCharsets.UTF_8).use { w ->
                Gson().toJson(root, w)
            }
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (e: IOException) {
            dirty = true
            log.warning("Could not save link check results: " + e.javaClass.simpleName)
        }
    }

    /** 読めなければ (壊れている・大きすぎる) 退避して、空から始める。読んだ値は、型と範囲を確かめてから使う */
    @Synchronized
    internal fun load() {
        if (!Files.isRegularFile(file)) return
        val now = clock.asLong
        try {
            if (Files.size(file) > MAX_FILE_BYTES) throw IOException("too large")
            val loaded = HashMap<String, Entry>()
            Files.newBufferedReader(file, StandardCharsets.UTF_8).use { r ->
                val root = JsonParser.parseReader(r).asJsonObject
                if (root.get("version").asInt != FILE_VERSION) throw IOException("version")
                val links = root.getAsJsonObject("links")
                if (links.size() > MAX_FILE_ENTRIES) throw IOException("too many entries")
                for (e in links.entrySet()) {
                    val url = e.key
                    if (!acceptable.test(url)) continue // 今の規則を通らない URL は読まない
                    loaded[url] = Entry(readState(e.value.asJsonObject, now))
                }
            }
            entries.putAll(loaded)
        } catch (e: Exception) {
            if (e !is IOException && e !is RuntimeException) throw e
            val backup = file.resolveSibling(file.fileName.toString() + ".corrupt-" + now)
            try {
                Files.move(file, backup, StandardCopyOption.REPLACE_EXISTING)
            } catch (ignored: IOException) {
                // 退避できなくても、空から始める
            }
            entries.clear()
            log.warning("Link check results could not be read (" + e.javaClass.simpleName + "); moved aside and starting empty")
        }
    }

    /** コマンド用の写し (URL と状態) */
    fun snapshot(): Map<String, LinkState> {
        val copy = HashMap<String, LinkState>()
        for (e in entries.entries) copy[e.key] = e.value.state
        return copy
    }

    companion object {
        private const val FILE_VERSION = 1
        private const val MAX_FILE_BYTES = 64L * 1024 * 1024
        private const val MAX_FILE_ENTRIES = 200_000
        private const val PRUNE_AFTER_MILLIS = 30L * 24 * 3_600_000
        private const val YEAR = 366L * 24 * 3_600_000

        private const val SEEN_REFRESH_MILLIS = 30 * 60_000L
        // これより長く知らされていない URL は、いま看板にない (看板が壊された・書き換えられた)
        private const val STALE_MILLIS = 2 * 3_600_000L

        private fun hostOf(url: String): String? {
            return try {
                URI.create(url).host
            } catch (e: IllegalArgumentException) {
                null
            }
        }

        private fun readState(o: JsonObject, now: Long): LinkState {
            val status = LinkStatus.valueOf(o.get("status").asString)
            val reason = LinkReason.valueOf(o.get("reason").asString)
            val code = boundedInt(o, "httpCode", 0, 599)
            val failures = boundedInt(o, "consecutiveFailures", 0, 1_000_000)
            return LinkState(status, reason, code,
                boundedLong(o, "firstFailedAt", now), boundedLong(o, "lastFailedAt", now), boundedLong(o, "lastOkAt", now),
                boundedLong(o, "lastCheckedAt", now), failures, boundedLong(o, "nextCheckAt", now + YEAR), boundedLong(o, "lastSeenAt", now))
        }

        private fun boundedInt(o: JsonObject, key: String, lo: Int, hi: Int): Int {
            val v = o.get(key).asInt
            if (v < lo || v > hi) throw IllegalArgumentException("range")
            return v
        }

        private fun boundedLong(o: JsonObject, key: String, hi: Long): Long {
            val v = o.get(key).asLong
            if (v < 0 || v > hi + YEAR) throw IllegalArgumentException("range")
            return v
        }
    }
}
