package net.torosaba.marumasign.companion

import org.bukkit.Bukkit
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.world.ChunkLoadEvent
import org.bukkit.plugin.java.JavaPlugin
import java.io.IOException
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.util.ArrayDeque
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.logging.Logger

/**
 * 看板の URL の中身をサーバーでホストして、看板の URL をホスト先に書き換える。
 *
 * 1. リンクの検査で正常だった URL を候補にする。正常と確かめてから verify-after-hours たち、読み込み (それ自体がもう一度の確認) にも
 *    成功したらホストする。ライブ配信・分割配信、外部のファイルを読む形式、HTML、大きすぎるものはホストしない ([HostingPolicy])。
 * 2. ホストしたら、その URL の看板を、ホスト先の URL に書き換える ([SignRewriter])。チャンクが読み込まれていなければ、読み込まれたときに。
 * 3. どの看板にも使われなくなってから unhost-after-days たったファイルは、ホストをやめて消す。
 *
 * ホストする量の上限: プレイヤーごとの数と容量 ([HostingQuota])、全体の数と容量、1 時間あたりの数。
 * 看板を書いたプレイヤーを記録し ([signWritten])、ファイルはその作者の分として数える。作者が分からないものは、だれの分にも数えない。
 * ホストした内容は、監査ログ ([AuditEntry]) に残す。
 *
 * 状態は [HostedStore]。ファイルの中身と状態の置き場所は [HostingStorage] (files: フォルダと hosting.json、database: データベース)。
 * 処理はこのクラスの 1 本のスレッドで行い、看板の書き換えだけメインスレッドで行う (データベースの読み書きもこのスレッド)。
 * URL の読み込みだけは専用のスレッド (同時に download-concurrency まで) で行い、結果をこのクラスのスレッドで受け取る
 * (遅い応答を待つあいだも、看板の書き換えと状態の保存を止めない)。
 */
internal class HostingManager(
    private val plugin: JavaPlugin? = null,
    private val logger: Logger,
    private val cfg: HostingSettings,
    private val stateFile: Path,
    private val fetcher: HostFetcher,
    private val allowedExtensions: Set<String>,
    /** ワールド名 → そのワールドの MarumaSign の看板 */
    private val signs: () -> Map<String, List<SignRecord>>,
    private val linkState: (String) -> LinkState?,
    /** 看板を書き換えたあと (メインスレッドで呼ぶ) */
    private val onRewritten: (world: String, x: Int, y: Int, z: Int, text: String, orient: Int) -> Unit,
    /** 新しいファイルをホストしたあと (このクラスのスレッドで、1 回の処理に 1 回。地図のデータを書き出し直す) */
    private val onHosted: () -> Unit = {},
    private val clock: () -> Long = System::currentTimeMillis,
) : Listener {

    /** 読み込んでいる途中の 1 件。maxBytes は全体とプレイヤーの分の空きから予約した大きさ */
    private class FetchJob(val url: String, val owner: String?, val maxBytes: Long, val signKey: String?) {
        val reservation: HostingQuota.Reservation get() = HostingQuota.Reservation(owner, maxBytes)
    }

    val store = HostedStore()
    val directory: Path = if (cfg.directory.isNotEmpty()) Path.of(cfg.directory) else (plugin?.dataFolder?.toPath() ?: Path.of(".")).resolve("hosted")
    private val tmpDir: Path = directory.resolveSibling("hosting-tmp")
    private val base = cfg.publicBaseUrl + "/"
    private var server: HostingServer? = null

    @Volatile
    private var executor: ScheduledExecutorService? = null

    @Volatile
    private var fetchPool: ExecutorService? = null
    private val kickPending = AtomicBoolean()
    private val downloads = ArrayDeque<Long>()
    private val ownerDownloads = HashMap<String, ArrayDeque<Long>>()
    private val inFlight = ConcurrentHashMap<String, FetchJob>()
    // いまは読まない URL → 次に試す時刻 (空きが足りなかった、ホストの上限で読めなかったなど)。短いあいだのことなので保存しない
    private val holds = ConcurrentHashMap<String, Long>()
    // 書かれたばかりの看板の作者 (メインスレッドから入れて、このクラスのスレッドで store に移す)
    private val newAuthors = ConcurrentHashMap<String, HostedStore.SignAuthor>()
    private val auditQueue = ConcurrentLinkedDeque<AuditEntry>()
    private val quotaWarned = HashSet<String>()
    private var storageWarned = false
    private var auditWarned = false
    private var loadFailures = 0

    /** storage: database では、以前の files の hosting.json とフォルダを最初に取り込む */
    private val storage: HostingStorage = if (cfg.usesDatabase) {
        DbHostingStorage(logger, DbHostingStorage.connect(cfg, logger), cfg.tablePrefix, Db.displayUrl(cfg.dbUrl), stateFile, directory, clock)
    } else {
        FileHostingStorage(logger, directory, stateFile, clock)
    }
    private val stateName = if (cfg.usesDatabase) "the database" else "hosting.json"

    // 保存してある状態を読めたか。読めるまでは、ホストの処理も保存もしない (空の状態で上書きしないように)
    @Volatile
    private var loaded = false

    // 書き換え待ちの看板があるチャンク (world:cx:cz)。チャンクが読み込まれたときに見る
    @Volatile
    private var pendingChunks: Set<String> = setOf()

    fun start() {
        if (!cfg.usesDatabase) Files.createDirectories(directory)
        cleanTmp()
        if (cfg.usesDatabase) {
            logger.info("Hosting: files are kept in the database (${Db.displayUrl(cfg.dbUrl)}) for another server to serve at ${cfg.publicBaseUrl}; the built-in web server is off")
        } else if (cfg.port > 0) {
            try {
                val s = HostingServer(cfg.bind, cfg.port, directory) { id -> store.files[id] }
                s.start()
                server = s
                logger.info("Hosting: serving hosted files on ${cfg.bind}:${cfg.port} (public URL ${cfg.publicBaseUrl})")
            } catch (e: IOException) {
                logger.warning("Hosting: could not start the web server on ${cfg.bind}:${cfg.port}: ${e.javaClass.simpleName}; files are still written to $directory")
            } catch (e: LinkageError) {
                logger.warning("Hosting: this Java runtime has no built-in web server (jdk.httpserver); serve $directory with another web server and set hosting.port to 0")
            }
        } else {
            logger.info("Hosting: built-in web server is off; serve $directory at ${cfg.publicBaseUrl}")
        }
        val e = Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "MarumaSignCompanion-hosting").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }
        }
        executor = e
        fetchPool = Executors.newFixedThreadPool(cfg.downloadConcurrency) { r ->
            Thread(r, "MarumaSignCompanion-hosting-fetch").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }
        }
        plugin?.let { Bukkit.getPluginManager().registerEvents(this, it) }
        e.execute { safely { loadState() } }
        e.scheduleWithFixedDelay({ safely { tick() } }, 1, 10, TimeUnit.MINUTES)
        e.scheduleWithFixedDelay({ safely { saveIfDirty() } }, 5, 5, TimeUnit.MINUTES)
    }

    /** 保存してある状態を読む。データベースにつながらなければ、1 分ごとにやり直す */
    private fun loadState() {
        if (loaded) return
        try {
            storage.load(store)
            loaded = true
            if (cfg.usesDatabase) logger.info("Hosting: loaded ${store.files.size} hosted files from the database")
        } catch (e: Exception) {
            store.clear()
            if (loadFailures++ == 0) logger.warning("Hosting: could not load the state from $stateName (${SafeText.clean(e.message ?: e.javaClass.simpleName, 200)}); hosting waits and tries again every minute")
            try {
                executor?.schedule({ safely { loadState() } }, 1, TimeUnit.MINUTES)
            } catch (ex: RuntimeException) {
                // 止まっている途中
            }
        }
    }

    fun stop() {
        server?.stop()
        server = null
        val e = executor
        executor = null
        e?.shutdownNow()
        val pool = fetchPool
        fetchPool = null
        pool?.shutdownNow()
        fetcher.close()
        try {
            e?.awaitTermination(5, TimeUnit.SECONDS)
        } catch (ex: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        // 読めていない状態を書くと、保存してあった状態を空で上書きしてしまう
        if (!loaded) {
            storage.close()
            return
        }
        if (!cfg.usesDatabase) {
            saveNow()
            return
        }
        // データベースへの最後の保存は、メインスレッドでしない (待つのは 15 秒まで)
        val t = Thread({
            saveNow()
            storage.close()
        }, "MarumaSignCompanion-hosting-save")
        t.isDaemon = true
        t.start()
        try {
            t.join(15_000)
        } catch (ex: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    /** /marumasign hosting status に出す、保存先の行 */
    fun describeStorage(): List<String> = storage.describe() + if (loaded) listOf() else listOf("保存してある状態をまだ読めていません (ホストは待っています)")

    /** /marumasign hosting status に出す、上限と読み込みの行 */
    fun describeLimits(): List<String> {
        val lines = ArrayList<String>()
        lines.add("全体の上限 " + cfg.maxTotalFiles + " 件 / 読み込み中 " + inFlight.size + " (同時に " + cfg.downloadConcurrency + " まで) / 空き待ち " + holds.size)
        lines.add("プレイヤーごとの上限 " + limitText(cfg.perPlayerFiles, "件") + " / " + limitText(cfg.perPlayerMb, "MB")
            + " (作者が分かるファイル " + store.files.values.count { it.owner != null } + " 件。分からないファイルは数えない)")
        if (cfg.allowHostsSet) lines.add("ホストしてよいホスト: " + cfg.allowHosts.joinToString(" ").ifEmpty { "なし (hosting.allow-hosts に読める行がない)" })
        if (cfg.requireApproval) lines.add("承認待ち " + pendingApprovals().size + " 件 (/marumasign hosting pending)")
        return lines
    }

    val storageKind: String get() = storage.kind

    val requiresApproval: Boolean get() = cfg.requireApproval

    /** 読み込んでいる途中の数 */
    val inFlightCount: Int get() = inFlight.size

    /** 看板が変わったときなど。30 秒以内のものはまとめて 1 回 */
    fun kick() {
        val e = executor ?: return
        if (!kickPending.compareAndSet(false, true)) return
        try {
            e.schedule({
                kickPending.set(false)
                safely { tick() }
            }, 30, TimeUnit.SECONDS)
        } catch (ex: RuntimeException) {
            kickPending.set(false)
        }
    }

    /** テスト用: このクラスのスレッドで実行して、終わるまで待つ */
    internal fun onWorker(task: () -> Unit) {
        executor!!.submit(Runnable { task() }).get(60, TimeUnit.SECONDS)
    }

    /**
     * プレイヤーが看板を書いた (メインスレッドから呼ぶ)。同じ URL の書き直しなら、最初に書いた人のまま。
     * ホスト先の URL を書いた看板は記録しない (ホストするものがないので)。
     */
    fun signWritten(world: String, x: Int, y: Int, z: Int, url: String, player: UUID) {
        if (isHostedUrl(url)) return
        val key = HostedStore.signKey(world, x, y, z)
        try {
            HostedStore.checkKey(key)
            HostedStore.checkUrl(url)
        } catch (e: IllegalArgumentException) {
            return
        }
        newAuthors[key] = HostedStore.SignAuthor(player.toString(), url, clock())
    }

    private fun drainAuthors() {
        for ((key, a) in newAuthors) {
            newAuthors.remove(key, a)
            val old = store.authors[key]
            if (old != null && old.url == a.url) continue
            store.authors[key] = a
            store.dirty = true
        }
    }

    /**
     * 作者の記録を片づける。看板が別の URL に変わった記録は、すぐ消す (ホスト先への書き換えは別)。
     * 看板が見つからない記録は、書いてから unhost-after-days たってから消す (走査の途中で消さないように。読み込まれていないワールドの分は残す)。
     */
    private fun pruneAuthors(all: Map<String, List<SignRecord>>, now: Long) {
        val current = HashMap<String, String>()
        for ((world, list) in all) for (s in list) current[HostedStore.signKey(world, s.x, s.y, s.z)] = s.url
        val limit = now - cfg.unhostAfterDays * DAY
        for ((key, a) in store.authors) {
            val url = current[key]
            val gone = if (url != null) url != a.url && !isHostedUrl(url) else a.at < limit && key.substringBefore(':') in all
            if (gone && store.authors.remove(key, a)) store.dirty = true
        }
    }

    /** その看板の作者。記録がないか、記録と URL が違えば null */
    private fun authorOf(world: String, s: SignRecord): HostedStore.SignAuthor? =
        store.authors[HostedStore.signKey(world, s.x, s.y, s.z)]?.takeIf { it.url == s.url }

    /** このプラグインがホストしている (またはこのサーバーが配信する) URL か (リンクの検査に出さない。自分には検査でつながないため) */
    fun isHostedUrl(url: String): Boolean = url.startsWith(base)

    private fun hostedId(url: String): String? {
        if (!url.startsWith(base)) return null
        val name = url.substring(base.length)
        val dot = name.indexOf('.')
        if (dot < 0) return null
        val id = name.substring(0, dot)
        val f = store.files[id] ?: return null
        return if (f.fileName == name) id else null
    }

    fun urlOf(f: HostedStore.HostedFile): String = base + f.fileName

    /**
     * 地図が画像として読む URL。sources のどれかをホストしていれば、ホスト先の URL (画像として描ける形式だけ)。
     * 看板の文字の書き換えはチャンクが読み込まれるまで待つので、書き換え前でも地図はホスト先から読める。
     */
    fun hostedImageUrl(vararg sources: String): String? {
        for (s in sources) {
            val f = store.bySource[s]?.let { store.files[it] } ?: continue
            if (WebExporter.isTextureExtension(f.ext)) return urlOf(f)
        }
        return null
    }

    // 新しくホストしたファイルがあるか (このクラスのスレッドだけで使う)
    private var hostedSomething = false

    // ---- 定期の処理 ----

    internal fun tick() {
        if (!loaded) return
        val now = clock()
        drainAuthors()
        val all = signs()
        val byUrl = HashMap<String, MutableList<Pair<String, SignRecord>>>()
        for ((world, list) in all) {
            for (s in list) byUrl.getOrPut(s.url) { ArrayList() }.add(world to s)
        }
        // 使われているファイルと、書き換えが要る看板
        for ((url, refs) in byUrl) {
            val hosted = hostedId(url)
            if (hosted != null) {
                store.files[hosted]?.lastUsedAt = now
                continue
            }
            val id = store.bySource[url] ?: continue
            val f = store.files[id] ?: continue
            if (url in store.denied) continue
            f.lastUsedAt = now
            for ((world, s) in refs) queueRewrite(world, s, url, urlOf(f))
        }
        store.dirty = true
        unhostUnused(now)
        updateCandidates(byUrl.keys, now)
        pruneAuthors(all, now)
        downloadDue(byUrl, now)
        refreshPendingChunks()
        applyRewrites(store.rewrites.values.toList())
        saveIfDirty()
        notifyHosted(now, 0)
    }

    private var lastHostedNotify = 0L

    /** 新しくホストしたファイルがあれば、minGap ミリ秒以上あいていたら知らせる (読み込みが続くあいだも、地図を少しずつ新しくする) */
    private fun notifyHosted(now: Long, minGap: Long) {
        if (!hostedSomething || now - lastHostedNotify < minGap) return
        hostedSomething = false
        lastHostedNotify = now
        try {
            onHosted()
        } catch (e: RuntimeException) {
            logger.warning("Hosting: could not update the map data: ${e.javaClass.simpleName}")
        }
    }

    private fun queueRewrite(world: String, s: SignRecord, from: String, to: String) {
        val r = HostedStore.Rewrite(world, s.x, s.y, s.z, from, to)
        if (store.rewrites.put(r.key, r) != r) store.dirty = true
    }

    private fun unhostUnused(now: Long) {
        val limit = now - cfg.unhostAfterDays * DAY
        for (f in store.files.values.toList()) {
            if (f.lastUsedAt >= limit) continue
            store.remove(f.id)
            deleteFile(f)
            audit(AuditEntry(now, AuditEntry.UNHOSTED, f.id, f.sha256, f.size, f.sources.firstOrNull() ?: "", null, f.owner))
            logger.info("Hosting: stopped hosting ${f.fileName} (no sign has used it for ${cfg.unhostAfterDays} days)")
        }
    }

    private fun updateCandidates(urls: Set<String>, now: Long) {
        store.candidates.keys.retainAll(urls)
        store.failures.entries.removeIf { it.key !in urls && it.value.retryAt < now }
        for (url in urls) {
            if (store.bySource.containsKey(url) || url in store.denied || isHostedUrl(url) || url.startsWith(base)) continue
            val failure = store.failures[url]
            if (failure != null && failure.retryAt > now) continue
            if (!hostAllowed(url)) {
                // 設定でホストしなくなったホストの URL は、候補からも外す
                if (store.candidates.remove(url) != null) store.dirty = true
                continue
            }
            val st = linkState(url)
            if (st != null && st.status == LinkStatus.OK && st.consecutiveFailures == 0) {
                if (store.candidates.putIfAbsent(url, if (st.lastOkAt > 0) st.lastOkAt else now) == null) store.dirty = true
            } else if (store.candidates.remove(url) != null) {
                store.dirty = true
            }
        }
    }

    private fun hostAllowed(url: String): Boolean {
        val host = try {
            URI(url).host
        } catch (e: Exception) {
            null
        }
        return cfg.hostPermitted(host)
    }

    // ---- 読み込み ----

    /** 期限の来た候補の読み込みを、空いている枠の分だけ始める (待たない。結果は [fetched] で受け取る) */
    private fun downloadDue(byUrl: Map<String, List<Pair<String, SignRecord>>>, now: Long) {
        holds.entries.removeIf { it.value <= now || !store.candidates.containsKey(it.key) }
        if (inFlight.size >= cfg.downloadConcurrency) return
        val due = store.candidates.entries
            .filter { now - it.value >= cfg.verifyAfterHours * HOUR && readyToFetch(it.key) }
            .sortedBy { it.value }.map { it.key }
        if (due.isEmpty()) return
        val quota = HostingQuota(cfg.perPlayerFiles, cfg.perPlayerBytes, store.files.values, inFlight.values.map { it.reservation })
        val hostedBytes = store.totalBytes()
        var reservedBytes = inFlight.values.sumOf { it.maxBytes }
        for (url in due) {
            if (inFlight.size >= cfg.downloadConcurrency) return
            while (downloads.isNotEmpty() && downloads.peekFirst() < now - HOUR) downloads.pollFirst()
            if (downloads.size >= cfg.perHourLimit) return
            if (hostedBytes >= cfg.maxTotalBytes || store.files.size >= cfg.maxTotalFiles) {
                if (!storageWarned) logger.warning("Hosting: reached hosting.max-total-mb (${cfg.maxTotalMb} MB) or hosting.max-total-files (${cfg.maxTotalFiles}); not hosting more files")
                storageWarned = true
                return
            }
            storageWarned = false
            val free = cfg.maxTotalBytes - hostedBytes - reservedBytes
            // 空きは、読み込んでいる途中の分の予約で埋まっている。終わったら、また試す
            if (free <= 0 || store.files.size + inFlight.size >= cfg.maxTotalFiles) return
            val refs = byUrl[url].orEmpty()
            val authors = refs.map { (world, s) -> authorOf(world, s) }
            val choice = quota.choose(authors) { !ownerBusy(it, now) }
            if (choice == null) {
                noteQuotaFull(authors, quota)
                continue
            }
            // 監査ログに残す看板: 数える人の看板 (作者が分からないときは、最初の看板)
            val sign = refs.firstOrNull { (world, s) -> choice.owner == null || authorOf(world, s)?.uuid == choice.owner }
            val signKey = sign?.let { (world, s) -> HostedStore.signKey(world, s.x, s.y, s.z) }
            val job = FetchJob(url, choice.owner, minOf(cfg.maxFileBytes, free, choice.room), signKey)
            if (!startFetch(job)) return
            downloads.addLast(now)
            job.owner?.let { ownerDownloads.getOrPut(it) { ArrayDeque() }.addLast(now) }
            quota.reserve(job.reservation)
            reservedBytes += job.maxBytes
        }
    }

    private fun readyToFetch(url: String): Boolean =
        !inFlight.containsKey(url) && !holds.containsKey(url) && url !in store.denied && (!cfg.requireApproval || url in store.approved)

    /** そのプレイヤーの分は、いま読まない (同時に読むのは 1 人 1 件まで、1 時間に読むのは全体の上限の 3 分の 1 まで。ほかの人の分を止めないように) */
    private fun ownerBusy(owner: String, now: Long): Boolean {
        if (inFlight.values.any { it.owner == owner }) return true
        val recent = ownerDownloads[owner] ?: return false
        while (recent.isNotEmpty() && recent.peekFirst() < now - HOUR) recent.pollFirst()
        if (recent.isEmpty()) {
            ownerDownloads.remove(owner)
            return false
        }
        return recent.size >= maxOf(1, cfg.perHourLimit / 3)
    }

    private fun noteQuotaFull(authors: List<HostedStore.SignAuthor?>, quota: HostingQuota) {
        for (a in authors.filterNotNull()) {
            if (quota.room(a.uuid) > 0 || !quotaWarned.add(a.uuid)) continue
            logger.info("Hosting: player ${a.uuid} reached the per-player limit (hosting.quota); URLs from this player wait until their hosted files are removed")
        }
    }

    private fun startFetch(job: FetchJob): Boolean {
        val pool = fetchPool ?: return false
        inFlight[job.url] = job
        try {
            pool.execute {
                val result = try {
                    fetcher.fetch(job.url, tmpDir, job.maxBytes, cfg.downloadTimeoutSeconds * 1000)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    null
                } catch (e: Exception) {
                    logger.warning("Hosting: could not read ${SafeText.logUrl(job.url)}: ${e.javaClass.simpleName}")
                    null
                }
                deliver(job, result)
            }
            return true
        } catch (e: RejectedExecutionException) {
            inFlight.remove(job.url)
            return false
        }
    }

    /** 読み込みのスレッドから: 結果をこのクラスのスレッドへ渡す。止まっていれば、一時ファイルを消す */
    private fun deliver(job: FetchJob, result: HostFetcher.Result?) {
        try {
            val e = executor ?: throw RejectedExecutionException()
            e.execute { safely { fetched(job, result) } }
        } catch (ex: RejectedExecutionException) {
            discard(result)
        }
    }

    private fun discard(result: HostFetcher.Result?) {
        val file = (result as? HostFetcher.Fetched)?.file ?: return
        try {
            Files.deleteIfExists(file)
        } catch (e: IOException) {
            // 次に始めるときに片づける ([cleanTmp])
        }
    }

    /** 読み込みの結果 (null は、読み込みの処理そのものが失敗した) */
    private fun fetched(job: FetchJob, r: HostFetcher.Result?) {
        inFlight.remove(job.url)
        val now = clock()
        when (r) {
            is HostFetcher.Fetched -> hostFetched(job, r, now)
            is HostFetcher.Rejected -> {
                if (r.reason == HostingPolicy.Reject.TOO_LARGE && job.maxBytes < cfg.maxFileBytes) {
                    // 読める大きさを、全体かプレイヤーの分の空きで絞っていた。空きが足りなかっただけかもしれない
                    holds[job.url] = now + ROOM_RETRY_MILLIS
                } else {
                    store.candidates.remove(job.url)
                    store.failures[job.url] = HostedStore.Failure(r.reason, now + retryAfter(r.reason))
                    store.dirty = true
                }
            }
            HostFetcher.Deferred -> {
                downloads.pollLast()
                job.owner?.let { ownerDownloads[it]?.pollLast() }
                holds[job.url] = now + DEFER_RETRY_MILLIS
            }
            null -> holds[job.url] = now + ROOM_RETRY_MILLIS
        }
        saveIfDirty()
        kick()
    }

    private fun hostFetched(job: FetchJob, r: HostFetcher.Fetched, now: Long) {
        // 読んでいるあいだに看板から消えた URL や、取り下げられた URL は、ホストしない
        if (!store.candidates.containsKey(job.url) || job.url in store.denied) {
            discard(r)
            return
        }
        try {
            host(job, r, now)
        } catch (e: IOException) {
            discard(r)
            holds[job.url] = now + ROOM_RETRY_MILLIS
            logger.warning("Hosting: could not store ${SafeText.logUrl(job.url)}: ${if (cfg.usesDatabase) e.message?.let { SafeText.clean(it, 200) } else e.javaClass.simpleName}")
            return
        }
        notifyHosted(clock(), HOSTED_NOTIFY_MILLIS)
    }

    private fun host(job: FetchJob, r: HostFetcher.Fetched, now: Long) {
        val url = job.url
        val existing = store.files[r.id]
        val f = existing ?: HostedStore.HostedFile(r.id, r.ext, r.size, now, now).also {
            it.owner = job.owner
            it.sha256 = r.sha256
        }
        if (existing == null || !storage.has(f)) {
            storage.put(f, r.file)
        } else {
            Files.deleteIfExists(r.file)
        }
        if (f.sha256.isEmpty()) f.sha256 = r.sha256
        f.sources.add(url)
        store.candidates.remove(url)
        store.approved.remove(url)
        store.add(f)
        hostedSomething = true
        audit(AuditEntry(now, if (existing == null) AuditEntry.HOSTED else AuditEntry.SAME_CONTENT, f.id, f.sha256, f.size, url, job.signKey, job.owner))
        val who = job.signKey?.let { " (sign $it" + (job.owner?.let { o -> ", player $o" } ?: "") + ")" } ?: ""
        if (existing == null) logger.info("Hosting: now hosting ${f.fileName} (${r.size / 1024} KB) for ${SafeText.logUrl(url)}$who")
        else logger.info("Hosting: ${SafeText.logUrl(url)} has the same content as ${f.fileName}; using that file$who")
    }

    private fun refreshPendingChunks() {
        pendingChunks = store.rewrites.values.mapTo(HashSet()) { chunkKey(it.world, it.x shr 4, it.z shr 4) }
    }

    /** 書き換えをメインスレッドで試す。結果はこのクラスのスレッドで受け取る */
    private fun applyRewrites(list: List<HostedStore.Rewrite>) {
        if (list.isEmpty() || plugin?.isEnabled != true) return
        for (batch in list.chunked(REWRITES_PER_TICK)) {
            Bukkit.getScheduler().runTask(plugin, Runnable {
                val results = batch.map { r ->
                    val applied = try {
                        SignRewriter.apply(r)
                    } catch (e: RuntimeException) {
                        logger.warning("Hosting: could not rewrite the sign at ${r.key}: ${e.javaClass.simpleName}")
                        SignRewriter.Applied(SignRewriter.Outcome.NOT_LOADED)
                    }
                    if (applied.outcome == SignRewriter.Outcome.DONE) onRewritten(r.world, r.x, r.y, r.z, applied.text, applied.orient)
                    r to applied.outcome
                }
                executor?.execute { safely { rewritten(results) } }
            })
        }
    }

    internal fun rewritten(results: List<Pair<HostedStore.Rewrite, SignRewriter.Outcome>>) {
        for ((r, outcome) in results) {
            when (outcome) {
                SignRewriter.Outcome.DONE -> {
                    store.rewrites.remove(r.key, r)
                    val id = hostedId(r.to)
                    if (id != null) {
                        val f = store.files[id]
                        f?.origins?.put(r.key, r.from)
                        logger.info("Hosting: rewrote the sign at ${r.key} to ${r.to}")
                        if (f != null) {
                            val author = store.authors[r.key]?.takeIf { it.url == r.from }?.uuid
                            audit(AuditEntry(clock(), AuditEntry.SIGN_REWRITTEN, f.id, f.sha256, f.size, r.from, r.key, author))
                        }
                    } else {
                        logger.info("Hosting: restored the sign at ${r.key} to ${SafeText.logUrl(r.to)}")
                    }
                }
                SignRewriter.Outcome.CHANGED, SignRewriter.Outcome.TOO_LONG -> store.rewrites.remove(r.key, r)
                SignRewriter.Outcome.NOT_LOADED, SignRewriter.Outcome.BUSY -> {}
            }
        }
        store.dirty = true
        refreshPendingChunks()
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onChunkLoad(event: ChunkLoadEvent) {
        val key = chunkKey(event.world.name, event.chunk.x, event.chunk.z)
        if (key !in pendingChunks) return
        val e = executor ?: return
        try {
            e.execute {
                safely {
                    // 読み込みの直後は看板のブロックがまだ扱えないことがあるので、次の tick に回る (applyRewrites は runTask)
                    applyRewrites(store.rewrites.values.filter { chunkKey(it.world, it.x shr 4, it.z shr 4) == key })
                }
            }
        } catch (ex: RuntimeException) {
            // 止まっている途中
        }
    }

    // ---- 管理コマンド ----

    /**
     * ホストを取り下げる (管理者の判断)。ファイルを消し、元の URL は二度とホストしない。
     * 書き換えた看板は、元の URL に戻す。
     */
    fun remove(id: String, done: (HostedStore.HostedFile?) -> Unit) {
        val e = executor ?: return done(null)
        e.execute {
            safely {
                val f = if (loaded) store.remove(id) else null
                if (f != null) {
                    store.denied.addAll(f.sources)
                    deleteFile(f)
                    audit(AuditEntry(clock(), AuditEntry.REMOVED, f.id, f.sha256, f.size, f.sources.firstOrNull() ?: "", null, f.owner))
                    val hostedUrl = urlOf(f)
                    for ((world, list) in signs()) {
                        for (s in list) {
                            if (s.url != hostedUrl) continue
                            val key = HostedStore.signKey(world, s.x, s.y, s.z)
                            val original = f.origins[key] ?: f.sources.firstOrNull() ?: continue
                            queueRewrite(world, s, hostedUrl, original)
                        }
                    }
                    logger.info("Hosting: removed ${f.fileName} by an admin; its source URLs will not be hosted again")
                    refreshPendingChunks()
                    applyRewrites(store.rewrites.values.toList())
                    saveIfDirty()
                }
                done(f)
            }
        }
    }

    /** 承認を待っている URL (古い順)。hosting.require-approval のときだけ、これらは承認されるまで読まない */
    fun pendingApprovals(): List<String> =
        store.candidates.entries.filter { it.key !in store.approved }.sortedBy { it.value }.map { it.key }

    /** 承認を待っている URL をホストしてよいことにする。done には、その URL (キーが見つからなければ null) */
    fun approve(key: String, by: String, done: (String?) -> Unit) = withPending(key, done) { url ->
        store.approved.add(url)
        logger.info("Hosting: $by approved ${SafeText.logUrl(url)}")
        kick()
    }

    /** 承認を待っている URL を断る。元の URL はもうホストしない (看板はそのまま)。done には、その URL (キーが見つからなければ null) */
    fun reject(key: String, by: String, done: (String?) -> Unit) = withPending(key, done) { url ->
        store.candidates.remove(url)
        store.denied.add(url)
        logger.info("Hosting: $by rejected ${SafeText.logUrl(url)}; it will not be hosted")
    }

    private fun withPending(key: String, done: (String?) -> Unit, action: (String) -> Unit) {
        val e = executor ?: return done(null)
        e.execute {
            safely {
                val url = if (loaded) pendingApprovals().firstOrNull { HostedStore.urlKey(it) == key } else null
                if (url != null) {
                    action(url)
                    store.dirty = true
                    saveIfDirty()
                }
                done(url)
            }
        }
    }

    // ---- その他 ----

    private fun audit(entry: AuditEntry) {
        auditQueue.addLast(entry)
        while (auditQueue.size > MAX_AUDIT_QUEUE) auditQueue.pollFirst()
    }

    private fun flushAudit() {
        if (auditQueue.isEmpty()) return
        val batch = auditQueue.toList()
        try {
            storage.audit(batch)
            repeat(batch.size) { auditQueue.pollFirst() }
            auditWarned = false
        } catch (e: IOException) {
            // 次の保存でやり直す
            if (!auditWarned) logger.warning("Hosting: could not write the audit log: ${if (cfg.usesDatabase) e.message?.let { SafeText.clean(it, 200) } else e.javaClass.simpleName}; will retry")
            auditWarned = true
        }
    }

    private fun saveIfDirty() {
        if (!loaded) return
        drainAuthors()
        flushAudit()
        if (store.dirty) saveState()
    }

    private fun saveNow() {
        drainAuthors()
        flushAudit()
        saveState()
    }

    private fun saveState() {
        try {
            storage.save(store)
        } catch (e: IOException) {
            logger.warning("Hosting: could not save $stateName: $e")
        }
    }

    private fun deleteFile(f: HostedStore.HostedFile) {
        try {
            storage.delete(f)
        } catch (e: IOException) {
            logger.warning("Hosting: could not delete ${f.fileName}: ${if (cfg.usesDatabase) e.message?.let { SafeText.clean(it, 200) } else e.javaClass.simpleName}")
        }
    }

    private fun cleanTmp() {
        if (!Files.isDirectory(tmpDir)) return
        Files.list(tmpDir).use { s -> s.forEach { Files.deleteIfExists(it) } }
    }

    private fun safely(task: () -> Unit) {
        try {
            task()
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (e: Exception) {
            logger.warning("Hosting: ${e.javaClass.simpleName}: ${e.message?.let { SafeText.clean(it, 200) }}")
        }
    }

    companion object {
        private const val HOUR = 3_600_000L
        private const val DAY = 24 * HOUR
        private const val REWRITES_PER_TICK = 50
        private const val HOSTED_NOTIFY_MILLIS = 120_000L
        /** 空きが足りなかった URL を、次に試すまでの時間 */
        internal const val ROOM_RETRY_MILLIS = 30 * 60_000L
        /** ホストごとの上限で読めなかった URL を、次に試すまでの時間 */
        private const val DEFER_RETRY_MILLIS = 10 * 60_000L
        private const val MAX_AUDIT_QUEUE = 10_000

        private fun chunkKey(world: String, cx: Int, cz: Int) = "$world:$cx:$cz"

        private fun limitText(limit: Int, unit: String): String = if (limit > 0) "$limit $unit" else "なし"

        /** ホストできなかった URL を、次に試すまでの時間 */
        internal fun retryAfter(reason: HostingPolicy.Reject): Long = when (reason) {
            HostingPolicy.Reject.HTTP, HostingPolicy.Reject.TIMEOUT, HostingPolicy.Reject.FAILED -> DAY
            else -> 7 * DAY
        }
    }
}
