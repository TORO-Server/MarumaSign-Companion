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
import java.nio.file.StandardCopyOption
import java.util.ArrayDeque
import java.util.Locale
import java.util.concurrent.Executors
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
 * 状態は hosting.json ([HostedStore])。処理はこのクラスの 1 本のスレッドで行い、看板の書き換えだけメインスレッドで行う。
 */
internal class HostingManager(
    private val plugin: JavaPlugin,
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
    private val clock: () -> Long = System::currentTimeMillis,
) : Listener {

    val store = HostedStore()
    val directory: Path = if (cfg.directory.isEmpty()) plugin.dataFolder.toPath().resolve("hosted") else Path.of(cfg.directory)
    private val tmpDir: Path = plugin.dataFolder.toPath().resolve("hosting-tmp")
    private val base = cfg.publicBaseUrl + "/"
    private var server: HostingServer? = null
    private var executor: ScheduledExecutorService? = null
    private val kickPending = AtomicBoolean()
    private val downloads = ArrayDeque<Long>()
    private var storageWarned = false

    // 書き換え待ちの看板があるチャンク (world:cx:cz)。チャンクが読み込まれたときに見る
    @Volatile
    private var pendingChunks: Set<String> = setOf()

    fun start() {
        try {
            store.load(stateFile)
        } catch (e: IOException) {
            val aside = stateFile.resolveSibling("hosting.json.corrupt-" + clock())
            try {
                Files.move(stateFile, aside, StandardCopyOption.REPLACE_EXISTING)
            } catch (ignored: IOException) {
                // 退避できなくても、空から始める
            }
            logger.warning("hosting.json was broken; moved it aside and starting empty")
        }
        Files.createDirectories(directory)
        cleanTmp()
        if (cfg.port > 0) {
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
        Bukkit.getPluginManager().registerEvents(this, plugin)
        e.scheduleWithFixedDelay({ safely { tick() } }, 1, 10, TimeUnit.MINUTES)
        e.scheduleWithFixedDelay({ safely { saveIfDirty() } }, 5, 5, TimeUnit.MINUTES)
    }

    fun stop() {
        server?.stop()
        server = null
        executor?.shutdownNow()
        executor = null
        try {
            store.save(stateFile)
        } catch (e: IOException) {
            logger.warning("Hosting: could not save hosting.json: $e")
        }
    }

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

    /** このプラグインがホストしている URL か (リンクの検査に出さない。自分には検査でつながないため) */
    fun isHostedUrl(url: String): Boolean = hostedId(url) != null

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

    // ---- 定期の処理 ----

    internal fun tick() {
        val now = clock()
        val byUrl = HashMap<String, MutableList<Pair<String, SignRecord>>>()
        for ((world, list) in signs()) {
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
        downloadDue(byUrl, now)
        refreshPendingChunks()
        applyRewrites(store.rewrites.values.toList())
        saveIfDirty()
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
            if (!hostAllowed(url)) continue
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
            URI(url).host?.lowercase(Locale.ROOT)
        } catch (e: Exception) {
            null
        } ?: return false
        return cfg.excludeHosts.none { host == it || host.endsWith(".$it") }
    }

    private fun downloadDue(byUrl: Map<String, List<Pair<String, SignRecord>>>, now: Long) {
        val due = store.candidates.entries.filter { now - it.value >= cfg.verifyAfterHours * HOUR }.sortedBy { it.value }.map { it.key }
        for (url in due) {
            if (Thread.currentThread().isInterrupted) return
            while (downloads.isNotEmpty() && downloads.peekFirst() < now - HOUR) downloads.pollFirst()
            if (downloads.size >= cfg.perHourLimit) return
            if (store.totalBytes() >= cfg.maxTotalBytes) {
                if (!storageWarned) logger.warning("Hosting: reached hosting.max-total-mb (${cfg.maxTotalMb} MB); not hosting more files")
                storageWarned = true
                return
            }
            storageWarned = false
            downloads.addLast(now)
            val maxBytes = minOf(cfg.maxFileBytes, cfg.maxTotalBytes - store.totalBytes())
            when (val r = fetcher.fetch(url, tmpDir, maxBytes, cfg.downloadTimeoutSeconds * 1000)) {
                is HostFetcher.Fetched -> {
                    val f = host(url, r, now)
                    for ((world, s) in byUrl[url].orEmpty()) queueRewrite(world, s, url, urlOf(f))
                }
                is HostFetcher.Rejected -> {
                    store.candidates.remove(url)
                    store.failures[url] = HostedStore.Failure(r.reason, now + retryAfter(r.reason))
                    store.dirty = true
                }
                HostFetcher.Deferred -> {
                    downloads.pollLast()
                }
            }
        }
    }

    private fun host(url: String, r: HostFetcher.Fetched, now: Long): HostedStore.HostedFile {
        val existing = store.files[r.id]
        val f = existing ?: HostedStore.HostedFile(r.id, r.ext, r.size, now, now)
        val target = directory.resolve(f.fileName)
        if (existing == null || !Files.isRegularFile(target)) {
            Files.move(r.file, target, StandardCopyOption.REPLACE_EXISTING)
        } else {
            Files.deleteIfExists(r.file)
        }
        f.sources.add(url)
        store.candidates.remove(url)
        store.add(f)
        if (existing == null) logger.info("Hosting: now hosting ${f.fileName} (${r.size / 1024} KB) for ${SafeText.logUrl(url)}")
        else logger.info("Hosting: ${SafeText.logUrl(url)} has the same content as ${f.fileName}; using that file")
        return f
    }

    private fun refreshPendingChunks() {
        pendingChunks = store.rewrites.values.mapTo(HashSet()) { chunkKey(it.world, it.x shr 4, it.z shr 4) }
    }

    /** 書き換えをメインスレッドで試す。結果はこのクラスのスレッドで受け取る */
    private fun applyRewrites(list: List<HostedStore.Rewrite>) {
        if (list.isEmpty() || !plugin.isEnabled) return
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

    private fun rewritten(results: List<Pair<HostedStore.Rewrite, SignRewriter.Outcome>>) {
        for ((r, outcome) in results) {
            when (outcome) {
                SignRewriter.Outcome.DONE -> {
                    store.rewrites.remove(r.key, r)
                    val id = hostedId(r.to)
                    if (id != null) {
                        store.files[id]?.origins?.put(r.key, r.from)
                        logger.info("Hosting: rewrote the sign at ${r.key} to ${r.to}")
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
                val f = store.remove(id)
                if (f != null) {
                    store.denied.addAll(f.sources)
                    deleteFile(f)
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

    // ---- その他 ----

    private fun saveIfDirty() {
        if (!store.dirty) return
        try {
            store.save(stateFile)
        } catch (e: IOException) {
            logger.warning("Hosting: could not save hosting.json: $e")
        }
    }

    private fun deleteFile(f: HostedStore.HostedFile) {
        try {
            Files.deleteIfExists(directory.resolve(f.fileName))
        } catch (e: IOException) {
            logger.warning("Hosting: could not delete ${f.fileName}: ${e.javaClass.simpleName}")
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

        private fun chunkKey(world: String, cx: Int, cz: Int) = "$world:$cx:$cz"

        /** ホストできなかった URL を、次に試すまでの時間 */
        internal fun retryAfter(reason: HostingPolicy.Reject): Long = when (reason) {
            HostingPolicy.Reject.HTTP, HostingPolicy.Reject.TIMEOUT, HostingPolicy.Reject.FAILED -> DAY
            else -> 7 * DAY
        }
    }
}
