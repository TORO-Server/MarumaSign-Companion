package net.torosaba.marumasign.companion

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Bukkit
import org.bukkit.World
import org.bukkit.command.Command
import org.bukkit.command.CommandSender
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.plugin.java.JavaPlugin
import java.io.IOException
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import java.util.TreeMap
import java.util.TreeSet
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.function.LongSupplier
import java.util.function.Predicate

/**
 * MarumaSign を使うサーバーのためのプラグイン。ワールドの MarumaSign の看板を一覧にして、リンクを検査し ([LinkChecker])、
 * 正常な URL の中身をサーバーでホストして看板を書き換える ([HostingManager])。
 * BlueMap / Dynmap が入っていれば、地図にも出す (地図ごとの出し方は [MapBackend]。地図がなくても、ほかの機能は動く)。
 */
class MarumaSignCompanion : JavaPlugin() {

    @Volatile
    private var settings = Settings()

    @Volatile
    private var markerHtml = MarkerHtml(UrlFilter(listOf()), settings)

    private val worlds: MutableMap<String, WorldState> = ConcurrentHashMap()
    // region ディレクトリがまだなかったワールド (できたばかりのネザーなど)。走査のたびにもう一度探す
    private val pendingWorlds: MutableMap<String, Path> = ConcurrentHashMap()
    private val backends: MutableList<MapBackend> = CopyOnWriteArrayList()
    // ゲーム中の変更を地図に反映する専用のスレッド (長い走査や、地図側の重い更新でメインスレッドを待たせない)
    @Volatile
    private var liveExecutor: ExecutorService? = null

    @Volatile
    private var scheduler: ScheduledExecutorService? = null
    private val scanning = AtomicBoolean()
    private val scanRequested = AtomicBoolean()

    @Volatile
    private var shuttingDown = false

    internal class WorldState(val name: String, val regionDir: Path, val cacheFile: Path) {
        val store = SignStore()
    }

    override fun onEnable() {
        migrateOldDataFolder()
        saveDefaultConfig()
        loadSettings()
        shuttingDown = false
        if (settings.dnsCheck) {
            val g = HostGuard(logger, true, Runnable { refreshMarkers() })
            g.start()
            hostGuard = g
        }
        if (settings.link.enabled) {
            val filter = UrlFilter(settings.blockedHosts)
            for (bad in AddressDenyList(settings.link.extraBlockedAddresses).rejected()) {
                logger.warning("link-check.extra-blocked-addresses: ignoring an entry that is not an address or range: " + SafeText.logUrl(bad))
            }
            val throttle = HostThrottle(settings.link)
            val probe = LinkProbe.create(settings.link, filter, throttle)
            val checker = LinkChecker(logger, dataFolder.toPath().resolve("linkcheck.json"), settings.link,
                Predicate { url -> probe.strictParse(url) != null }, probe.asProber(), throttle,
                LongSupplier { System.currentTimeMillis() }, Runnable { linkStateChanged() })
            checker.start()
            linkChecker = checker
            logger.info("Link check is on: the server will connect to URLs written on signs (see link-check in config.yml); first check starts in "
                    + settings.link.startupDelayMinutes + " min")
            startHosting(filter, throttle, probe)
        } else if (settings.hosting.enabled) {
            logger.warning("hosting.enabled is true, but hosting needs link-check.enabled (it only hosts URLs the link check found working); hosting is off")
        }
        if (settings.resolveShortLinks) {
            val r = ShortLinkResolver(logger, UrlFilter(settings.blockedHosts), settings.shortLinkHosts,
                dataFolder.toPath().resolve("shortlinks.json"), Runnable { refreshMarkers() })
            r.load()
            r.start()
            resolver = r
        }
        server.pluginManager.registerEvents(SignListener(this), this)
        liveExecutor = Executors.newSingleThreadExecutor { r ->
            val t = Thread(r, "MarumaSignCompanion-live")
            t.isDaemon = true
            t
        }
        scheduler = Executors.newSingleThreadScheduledExecutor { r ->
            val t = Thread(r, "MarumaSignCompanion-main")
            t.isDaemon = true
            t.priority = Thread.MIN_PRIORITY
            t
        }
        if (config.getBoolean("bluemap.enabled", true)) {
            if (Bukkit.getPluginManager().getPlugin("BlueMap") != null) {
                addBackend(Backends.blueMap(this))
            } else {
                logger.info("BlueMap is not installed; BlueMap markers are off")
            }
        }
        if (config.getBoolean("dynmap.enabled", true)) {
            if (Bukkit.getPluginManager().getPlugin("dynmap") != null) {
                addBackend(Backends.dynmap(this))
            } else {
                logger.info("Dynmap is not installed; Dynmap markers are off")
            }
        }
        if (backends.isEmpty()) logger.info("No map plugin (BlueMap or Dynmap) is enabled; indexing, link checks and hosting still run")
        for (world in Bukkit.getWorlds()) worldLoaded(world)
        requestScan()
        val s = scheduler!!
        if (settings.rescanIntervalMinutes > 0) {
            s.scheduleWithFixedDelay(Runnable { requestScan() }, settings.rescanIntervalMinutes.toLong(), settings.rescanIntervalMinutes.toLong(), TimeUnit.MINUTES)
        }
        s.scheduleWithFixedDelay(Runnable { saveDirty() }, 5, 5, TimeUnit.MINUTES)
    }

    private fun addBackend(backend: MapBackend) {
        backends.add(backend)
        try {
            backend.start()
        } catch (e: RuntimeException) {
            backendFailedToStart(backend, e)
        } catch (e: LinkageError) {
            backendFailedToStart(backend, e)
        }
    }

    private fun backendFailedToStart(backend: MapBackend, e: Throwable) {
        logger.warning(backend.name() + " support failed to start: " + e)
        backends.remove(backend)
    }

    override fun onDisable() {
        shuttingDown = true
        for (b in backends) {
            try {
                b.stop()
            } catch (e: RuntimeException) {
                logger.warning(b.name() + " support failed to stop: " + e)
            } catch (e: LinkageError) {
                logger.warning(b.name() + " support failed to stop: " + e)
            }
        }
        backends.clear()
        val h = hosting
        hosting = null
        h?.stop()
        val checker = linkChecker
        linkChecker = null
        checker?.stop()
        val r = resolver
        resolver = null
        r?.stop()
        val g = hostGuard
        hostGuard = null
        g?.stop()
        val s = scheduler
        s?.shutdownNow()
        val live = liveExecutor
        live?.shutdownNow()
        for (w in worlds.values) saveCache(w)
        worlds.clear()
    }

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<String>): Boolean {
        if (!sender.hasPermission(AdminCommands.PERMISSION)) {
            sender.sendMessage("You do not have permission.")
            return true
        }
        val sub = if (args.isNotEmpty()) args[0].lowercase(Locale.getDefault()) else "status"
        when (sub) {
            "broken", "linkstatus", "recheck" -> admin.handle(sender, sub, args)
            "hosting" -> hostingCommand(sender, args)
            "reload" -> {
                reloadConfig()
                loadSettings()
                refreshMarkers()
                sender.sendMessage("MarumaSign: reloaded config and refreshed markers.")
            }
            "rescan" -> {
                sender.sendMessage("MarumaSign: scanning changed region files in the background.")
                requestScan()
            }
            else -> {
                for (w in worlds.values) {
                    sender.sendMessage("MarumaSign [" + w.name + "]: " + w.store.count() + " signs")
                }
                val sb = StringBuilder("MarumaSign map backends:")
                for (b in backends) sb.append(' ').append(b.name())
                sender.sendMessage(sb.toString())
                val r = resolver
                if (r != null) {
                    sender.sendMessage("MarumaSign: short links resolved " + r.resolvedCount() + ", waiting " + r.pending())
                }
                val g = hostGuard
                if (g != null) sender.sendMessage("MarumaSign: hosts waiting for the DNS check " + g.pending())
                val h = hosting
                if (h != null) sender.sendMessage("MarumaSign: hosting " + h.store.files.size + " files, " + h.store.rewrites.size + " signs waiting to be rewritten")
            }
        }
        return true
    }

    private fun hostingCommand(sender: CommandSender, args: Array<String>) {
        val h = hosting
        if (h == null) {
            sender.sendMessage(Component.text("ホストは無効です (config.yml の hosting.enabled と hosting.public-base-url、link-check.enabled)。", NamedTextColor.YELLOW))
            return
        }
        val sub = if (args.size >= 2) args[1].lowercase(Locale.ROOT) else "status"
        val st = h.store
        when (sub) {
            "list" -> {
                val page = if (args.size >= 3) AdminCommands.parsePage(args[2]) else 1
                if (page == null) {
                    sender.sendMessage(Component.text("ページ番号は 1 から 9999 の数字です。", NamedTextColor.RED))
                    return
                }
                val files = st.files.values.sortedByDescending { it.hostedAt }
                val pages = Math.max(1, (files.size + AdminCommands.PAGE_SIZE - 1) / AdminCommands.PAGE_SIZE)
                val p = Math.min(page, pages)
                sender.sendMessage(Component.text("ホストしているファイル: " + files.size + " 件 (ページ $p/$pages)", NamedTextColor.GOLD))
                for (f in files.drop((p - 1) * AdminCommands.PAGE_SIZE).take(AdminCommands.PAGE_SIZE)) {
                    val source = f.sources.firstOrNull()?.let { SafeText.displayUrl(it, 50) } ?: ""
                    sender.sendMessage(Component.text(f.fileName, NamedTextColor.AQUA)
                        .append(Component.text(" " + f.size / 1024 + " KB " + source, NamedTextColor.GRAY)))
                }
            }
            "remove" -> {
                val id = if (args.size == 3) args[2].substringBefore('.').lowercase(Locale.ROOT) else ""
                if (!id.matches(HostedStore.ID)) {
                    sender.sendMessage(Component.text("使い方: /marumasign hosting remove <id> (id は list のファイル名の . より前)", NamedTextColor.RED))
                    return
                }
                sender.sendMessage(Component.text("取り下げています: $id", NamedTextColor.GRAY))
                h.remove(id) { f ->
                    Bukkit.getScheduler().runTask(this, Runnable {
                        sender.sendMessage(if (f == null) Component.text("その id のファイルはありません。", NamedTextColor.YELLOW)
                        else Component.text("取り下げました: " + f.fileName + " (元の URL はもうホストしません。書き換えた看板は元の URL に戻します)", NamedTextColor.GREEN))
                    })
                }
            }
            else -> {
                val total = st.files.values.sumOf { it.size }
                sender.sendMessage(Component.text("ホスト: " + st.files.size + " 件 / " + total / (1024 * 1024) + " MB (上限 " + settings.hosting.maxTotalMb + " MB)", NamedTextColor.GOLD))
                sender.sendMessage(Component.text("候補 " + st.candidates.size + " / 書き換え待ちの看板 " + st.rewrites.size + " / ホストしない URL " + st.failures.size
                        + " / 取り下げ " + st.denied.size, NamedTextColor.WHITE))
                sender.sendMessage(Component.text("配信先: " + settings.hosting.publicBaseUrl + " (フォルダ " + h.directory + ")", NamedTextColor.GRAY))
            }
        }
    }

    @Volatile
    private var hosting: HostingManager? = null

    internal fun hosting(): HostingManager? = hosting

    private fun startHosting(filter: UrlFilter, throttle: HostThrottle, probe: LinkProbe) {
        val hs = settings.hosting
        if (!hs.enabled) return
        if (hs.publicBaseUrl.isEmpty()) {
            logger.warning("hosting.enabled is true, but hosting.public-base-url is empty; hosting is off")
            return
        }
        val o = SafeHttp.Options()
        o.connectTimeoutMillis = LinkSettings.clamp(settings.link.connectTimeoutSeconds, 1, 30) * 1000
        o.userAgent = LinkSettings.cleanUserAgent(settings.link.userAgent)
        o.denyList = AddressDenyList(settings.link.extraBlockedAddresses)
        val fetcher = HostFetcher(SafeHttp(o), { url -> probe.strictParse(url) }, throttle, settings.link.maxRedirects, settings.allowedExtensions)
        val m = HostingManager(this, logger, hs, dataFolder.toPath().resolve("hosting.json"), fetcher, settings.allowedExtensions,
            signs = { worlds.mapValues { it.value.store.all() } },
            linkState = { url -> linkChecker?.state(url) },
            onRewritten = { world, x, y, z, text, orient -> Bukkit.getWorld(world)?.let { signChanged(it, x, y, z, text, orient) } })
        try {
            m.start()
            hosting = m
            logger.info("Hosting is on: working URLs on signs are copied to ${hs.publicBaseUrl} and the signs are rewritten to point there")
        } catch (e: IOException) {
            logger.warning("Hosting could not start: $e")
        }
    }

    /** 旧名 (BlueMapMarumaSign) のフォルダがあれば、設定・キャッシュ・検査の結果を引き継ぐ (旧フォルダは残す) */
    private fun migrateOldDataFolder() {
        val target = dataFolder.toPath()
        val old = target.resolveSibling("BlueMapMarumaSign")
        if (Files.exists(target) || !Files.isDirectory(old)) return
        try {
            Files.walk(old).use { paths ->
                for (p in paths) {
                    val dest = target.resolve(old.relativize(p).toString())
                    if (Files.isDirectory(p)) Files.createDirectories(dest) else Files.copy(p, dest)
                }
            }
            logger.info("Copied settings and caches from plugins/BlueMapMarumaSign (the old name of this plugin); you can delete that folder")
        } catch (e: IOException) {
            logger.warning("Could not copy plugins/BlueMapMarumaSign: $e")
        }
    }

    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<String>): List<String> {
        if (!sender.hasPermission(AdminCommands.PERMISSION)) return listOf()
        return admin.complete(args)
    }

    private val admin = AdminCommands(this)

    private fun loadSettings() {
        val c = config
        val s = Settings()
        s.dynmapShowOk = c.getBoolean("dynmap.show-ok", s.dynmapShowOk)
        s.link = readLinkSettings(c)
        s.maxMarkers = Math.max(0, c.getInt("max-markers", s.maxMarkers))
        val ext = c.getStringList("allowed-extensions")
        if (ext.isNotEmpty()) s.allowedExtensions = Settings.normalizeExtensions(ext)
        s.allowNoExtension = c.getBoolean("allow-no-extension", s.allowNoExtension)
        s.blockedHosts = ArrayList(c.getStringList("blocked-hosts"))
        s.scanThreads = Math.max(1, Math.min(8, c.getInt("scan.threads", s.scanThreads)))
        s.pauseMillisPerRegion = Math.max(0L, c.getLong("scan.pause-millis-per-region", s.pauseMillisPerRegion))
        s.rescanIntervalMinutes = Math.max(0, c.getInt("scan.rescan-interval-minutes", s.rescanIntervalMinutes))
        s.scanWorlds = c.getStringList("scan.worlds").map { it.trim { ch -> ch <= ' ' } }.filter { it.isNotEmpty() }
        s.hosting = HostingSettings.read(c) { logger.warning(it) }
        s.thumbnailSize = Math.max(16, Math.min(512, c.getInt("marker.thumbnail-size", s.thumbnailSize)))
        s.maxDistance = c.getDouble("marker.max-distance", s.maxDistance)
        s.defaultHidden = c.getBoolean("marker.default-hidden", s.defaultHidden)
        s.dynmapDefaultHidden = c.getBoolean("dynmap.default-hidden", s.dynmapDefaultHidden)
        s.markerSetLabel = c.getString("marker.set-label", s.markerSetLabel)!!
        val mode = c.getString("bluemap.mode", s.bluemapMode)!!.lowercase(Locale.ROOT)
        if (mode == "3d" || mode == "markers" || mode == "both") s.bluemapMode = mode
        else logger.warning("Unknown bluemap.mode '" + mode + "'; using " + s.bluemapMode)
        s.resolveShortLinks = c.getBoolean("short-links.resolve", s.resolveShortLinks)
        val hosts = c.getStringList("short-links.hosts")
        if (hosts.isNotEmpty()) {
            val set = TreeSet<String>()
            for (h in hosts) set.add(h.trim { it <= ' ' }.lowercase(Locale.ROOT))
            s.shortLinkHosts = set
        }
        s.dnsCheck = c.getBoolean("security.dns-check", s.dnsCheck)
        s.web3dMaxSize = Math.max(1.0, Math.min(1000.0, c.getDouble("web3d.max-size", s.web3dMaxSize)))
        s.web3dMaxOffset = Math.max(1.0, Math.min(1000.0, c.getDouble("web3d.max-offset", s.web3dMaxOffset)))
        s.web3dMaxSigns = Math.max(0, c.getInt("web3d.max-signs", s.web3dMaxSigns))
        s.web3dDrawDistance = Math.max(16, Math.min(512, c.getInt("web3d.draw-distance", s.web3dDrawDistance)))
        s.web3dMaxActive = Math.max(10, Math.min(1000, c.getInt("web3d.max-active", s.web3dMaxActive)))
        s.web3dTextureSize = Math.max(64, Math.min(4096, c.getInt("web3d.texture-size", s.web3dTextureSize)))
        s.web3dHideBoards = c.getBoolean("web3d.hide-sign-boards", s.web3dHideBoards)
        s.web3dLabels = c.getBoolean("web3d.show-labels", s.web3dLabels)
        this.settings = s
        val filter = UrlFilter(s.blockedHosts)
        this.markerHtml = MarkerHtml(filter, s)
        val r = resolver
        r?.setHosts(s.shortLinkHosts)
    }

    @Volatile
    private var linkChecker: LinkChecker? = null

    internal fun linkChecker(): LinkChecker? = linkChecker

    // 検査の結果が変わったら、まとめて (10 秒に 1 回まで) 地図に反映する
    private val linkRefreshPending = AtomicBoolean()

    private fun linkStateChanged() {
        hosting?.kick()
        val s = scheduler
        if (s == null || shuttingDown || !linkRefreshPending.compareAndSet(false, true)) return
        try {
            s.schedule(Runnable {
                linkRefreshPending.set(false)
                for (b in backends) {
                    try {
                        b.linkStateChanged()
                    } catch (e: RuntimeException) {
                        logger.warning(b.name() + " link state update failed: " + e.javaClass.simpleName)
                    }
                }
            }, 10, TimeUnit.SECONDS)
        } catch (e: RuntimeException) {
            linkRefreshPending.set(false)
        }
    }

    /** いま看板にあるすべての URL を、検査の対象として知らせる */
    private fun trackLinks() {
        val checker = linkChecker ?: return
        val h = hosting
        val urls = HashSet<String>()
        for (w in worlds.values) {
            // ホストしている URL は、このサーバー自身なので検査しない (自分にはつながない決まり)
            for (s in w.store.all()) if (h == null || !h.isHostedUrl(s.url)) urls.add(s.url)
        }
        checker.track(urls)
        h?.kick()
    }

    @Volatile
    private var resolver: ShortLinkResolver? = null

    internal fun resolver(): ShortLinkResolver? = resolver

    /** 短縮 URL は行き先が分かっていれば行き先に置き換える (まだなら調べるよう頼む) */
    internal fun effective(sign: SignRecord): SignRecord {
        val r = resolver
        if (r == null || !r.isShort(sign.url)) return sign
        val target = r.resolved(sign.url)
        if (target == null) {
            r.request(sign.url)
            return sign
        }
        return sign.withUrl(target)
    }

    internal fun settings(): Settings = settings

    internal fun markerHtml(): MarkerHtml = markerHtml

    // ---- ワールドと走査 ----

    /**
     * 看板を調べるワールドとして登録する (保存してあるキャッシュも読む)。同じワールドを複数の地図から登録してもよい。
     * region ディレクトリが見つからなければ null。
     */
    internal fun registerWorld(world: World, extraBase: Path?): WorldState? {
        val existing = worlds[world.name]
        if (existing != null) return existing
        val overrides = config.getConfigurationSection("regions")
        val regionDir = RegionDirs.resolve(world, extraBase, overrides?.getString(world.name), logger)
        if (regionDir == null) {
            if (pendingWorlds.put(world.name, extraBase ?: Path.of("")) == null) {
                logger.info("[" + world.name + "] No region directory yet; will look again at the next scan")
            }
            return null
        }
        pendingWorlds.remove(world.name)
        val cache = dataFolder.toPath().resolve("cache").resolve(safeName(world.name) + ".json")
        val state = WorldState(world.name, regionDir, cache)
        try {
            state.store.load(cache)
            logger.info("[" + world.name + "] Loaded " + state.store.count() + " signs from cache")
        } catch (e: IOException) {
            logger.warning("[" + world.name + "] Ignoring cache: " + e.message)
        }
        val prev = worlds.putIfAbsent(world.name, state)
        if (prev != null) return prev
        logger.info("[" + world.name + "] Region directory: " + regionDir)
        return state
    }

    /** 読み込まれたワールドを、地図がなくても調べる (scan.worlds が空ならすべて、あればそこに書いたものだけ) */
    internal fun worldLoaded(world: World) {
        val only = settings.scanWorlds
        if (only.isNotEmpty() && world.name !in only) return
        if (!worlds.containsKey(world.name) && registerWorld(world, null) != null) requestScan()
    }

    internal fun worldState(name: String): WorldState? = worlds[name]

    /** 走査しているワールドの一覧 (名前 → 状態) */
    internal fun worldStates(): Map<String, WorldState> = TreeMap(worlds)

    /** 更新された region ファイルの走査を頼む (実行中なら、終わってからもう一度) */
    internal fun requestScan() {
        val s = scheduler
        if (s == null || shuttingDown) return
        scanRequested.set(true)
        try {
            s.execute { runScan() }
        } catch (e: RuntimeException) {
            // 止まっている途中
        }
    }

    private fun runScan() {
        if (shuttingDown || !scanning.compareAndSet(false, true)) return
        try {
            while (scanRequested.getAndSet(false)) {
                retryPendingWorlds()
                for (w in ArrayList(worlds.values)) {
                    if (shuttingDown) return
                    try {
                        val scanner = WorldScanner(logger, w.name, w.regionDir, w.store,
                            settings.scanThreads, settings.pauseMillisPerRegion)
                        val sum = scanner.scan(500, Runnable { refreshMarkers() })
                        if (sum.regionsScanned > 0) {
                            logger.info("[" + w.name + "] Scan finished: " + sum.regionsScanned + "/" + sum.regionsTotal
                                    + " regions, " + sum.chunks + " chunks, " + sum.rawSigns + " signs, " + sum.marumaSigns
                                    + " MarumaSign signs, " + sum.chunkErrors + " chunk errors, " + sum.fileErrors
                                    + " file errors, " + sum.millis / 1000.0 + " s")
                            saveCache(w)
                            refreshMarkers()
                        }
                    } catch (e: IOException) {
                        logger.warning("[" + w.name + "] Scan failed: " + e)
                    } catch (e: InterruptedException) {
                        Thread.currentThread().interrupt()
                        return
                    }
                }
            }
        } finally {
            scanning.set(false)
        }
    }

    private fun retryPendingWorlds() {
        for ((key, value) in ArrayList(pendingWorlds.entries)) {
            val world = Bukkit.getWorld(key)
            if (world == null) {
                pendingWorlds.remove(key)
                continue
            }
            val base = if (value.toString().isEmpty()) null else value
            if (registerWorld(world, base) != null) refreshMarkers()
        }
    }

    private fun saveDirty() {
        for (w in worlds.values) {
            if (w.store.isDirty()) saveCache(w)
        }
    }

    private fun saveCache(w: WorldState) {
        try {
            w.store.save(w.cacheFile)
        } catch (e: IOException) {
            logger.warning("[" + w.name + "] Could not save cache: " + e)
        }
    }

    // ---- マーカー ----

    /** そのワールドで地図に出す看板 (表示してよいもの。最大数まで) */
    internal fun views(w: WorldState): List<MarkerHtml.View> {
        val out = ArrayList<MarkerHtml.View>()
        val max = settings.maxMarkers
        for (sign in w.store.all()) {
            if (out.size >= max) break
            val view = classify(sign)
            if (view != null) out.add(view)
        }
        return out
    }

    /** 3D 表示に出す看板 (最大数まで)。短縮 URL は行き先に置き換えてある */
    internal fun views3d(w: WorldState): List<MarkerHtml.View> {
        val out = ArrayList<MarkerHtml.View>()
        val max = settings.web3dMaxSigns
        for (sign in w.store.all()) {
            if (out.size >= max) break
            val view = classify(sign)
            if (view != null) out.add(view)
        }
        return out
    }

    /**
     * 表示してよい看板ならその内容、だめなら null。短縮 URL の行き先への置き換えと、
     * URL の形・拡張子の確認 ([MarkerHtml]) のあと、ホスト名を DNS で引いて公開のアドレスか確かめる ([HostGuard])。
     */
    internal fun classify(sign: SignRecord): MarkerHtml.View? {
        val view = markerHtml.classify(effective(sign)) ?: return null
        val guard = hostGuard ?: return view
        return try {
            val host = URI.create(view.url).host
            if (guard.isPublic(host)) view else null
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    @Volatile
    private var hostGuard: HostGuard? = null

    internal fun hostGuard(): HostGuard? = hostGuard

    internal fun refreshMarkers() {
        trackLinks()
        for (b in backends) {
            try {
                b.refresh()
            } catch (e: RuntimeException) {
                logger.warning(b.name() + " marker refresh failed: " + e)
            }
        }
    }

    /** ゲーム中の看板の追加・変更を反映する */
    internal fun signChanged(world: World, x: Int, y: Int, z: Int, text: String, orient: Int) {
        val w = worlds[world.name] ?: return
        val spec = MarumaSpec.parse(text)
        if (spec == null || spec.url.length > UrlFilter.MAX_URL_LENGTH) {
            signRemoved(world, x, y, z)
            return
        }
        val record = SignRecord(x, y, z, spec.url, spec.values, orient)
        w.store.liveSet(record)
        val checker = linkChecker
        checker?.prioritize(record.url)
        hosting?.kick()
        val view = classify(record)
        val worldName = world.name
        runLive {
            for (b in backends) b.signUpdated(worldName, record, view)
        }
    }

    internal fun signRemoved(world: World, x: Int, y: Int, z: Int) {
        val w = worlds[world.name] ?: return
        if (!w.store.liveRemove(x, y, z)) return
        val worldName = world.name
        runLive {
            for (b in backends) b.signRemoved(worldName, x, y, z)
        }
    }

    private fun runLive(task: Runnable) {
        val live = liveExecutor ?: return
        try {
            live.execute {
                try {
                    task.run()
                } catch (e: RuntimeException) {
                    logger.warning("Marker update failed: " + e)
                }
            }
        } catch (e: RuntimeException) {
            // 止まっている途中
        }
    }

    internal fun tracks(world: World): Boolean = worlds.containsKey(world.name)

    companion object {
        internal const val MARKER_SET_ID = "marumasign"

        /** config.yml の link-check を読む。値の範囲は収める (不正な値で検査が暴走しないように) */
        internal fun readLinkSettings(c: ConfigurationSection): LinkSettings {
            val l = LinkSettings()
            l.enabled = c.getBoolean("link-check.enabled", l.enabled)
            l.concurrency = LinkSettings.clamp(c.getInt("link-check.concurrency", l.concurrency), 1, 4)
            l.startupDelayMinutes = LinkSettings.clamp(c.getInt("link-check.startup-delay-minutes", l.startupDelayMinutes), 0, 1440)
            l.perHourLimit = LinkSettings.clamp(c.getInt("link-check.per-hour-limit", l.perHourLimit), 1, 100_000)
            l.hostPerHourLimit = LinkSettings.clamp(c.getInt("link-check.host-per-hour-limit", l.hostPerHourLimit), 1, 10_000)
            l.hostMinIntervalSeconds = LinkSettings.clamp(c.getInt("link-check.host-min-interval-seconds", l.hostMinIntervalSeconds), 0, 3600)
            l.connectTimeoutSeconds = LinkSettings.clamp(c.getInt("link-check.timeouts.connect-seconds", l.connectTimeoutSeconds), 1, 30)
            l.totalTimeoutSeconds = LinkSettings.clamp(c.getInt("link-check.timeouts.total-seconds", l.totalTimeoutSeconds), 2, 120)
            l.maxBodyKb = LinkSettings.clamp(c.getInt("link-check.max-body-kb", l.maxBodyKb), 1, 256)
            l.maxRedirects = LinkSettings.clamp(c.getInt("link-check.max-redirects", l.maxRedirects), 0, 10)
            l.recheckOkDays = LinkSettings.clamp(c.getInt("link-check.recheck-ok-days", l.recheckOkDays), 1, 365)
            l.recheckBrokenHours = LinkSettings.clamp(c.getInt("link-check.recheck-broken-hours", l.recheckBrokenHours), 1, 720)
            val backoff = ArrayList<Int>()
            for (h in c.getIntegerList("link-check.unknown-backoff-hours")) backoff.add(LinkSettings.clamp(h, 1, 720))
            if (backoff.isNotEmpty()) l.unknownBackoffHours = backoff
            l.brokenAfterFailures = LinkSettings.clamp(c.getInt("link-check.broken-after-failures", l.brokenAfterFailures), 1, 20)
            l.brokenAfterHours = LinkSettings.clamp(c.getInt("link-check.broken-after-hours", l.brokenAfterHours), 0, 720)
            l.treat403AsBroken = c.getBoolean("link-check.treat-403-as-broken", l.treat403AsBroken)
            l.maxTrackedUrls = LinkSettings.clamp(c.getInt("link-check.max-tracked-urls", l.maxTrackedUrls), 100, 200_000)
            l.userAgent = LinkSettings.cleanUserAgent(c.getString("link-check.user-agent", l.userAgent))
            for (a in c.getStringList("link-check.extra-blocked-addresses")) {
                if (a != null && a.length <= 64) l.extraBlockedAddresses.add(a.trim { it <= ' ' })
            }
            if (c.contains("link-check.host-rules")) {
                val rules = ArrayList<LinkSettings.HostRule>()
                for (m in c.getMapList("link-check.host-rules")) {
                    val host = m["host"] ?: continue
                    val h = host.toString().trim { it <= ' ' }.lowercase(Locale.ROOT)
                    if (!h.matches(HOST_PATTERN)) continue
                    var interval = 0
                    val n = m["min-interval-seconds"]
                    if (n is Number) interval = LinkSettings.clamp(n.toInt(), 0, 3600)
                    val paths = ArrayList<String>()
                    val list = m["removed-paths"]
                    if (list is List<*>) {
                        for (o in list) {
                            val p = o.toString()
                            if (p.matches(PATH_PATTERN)) paths.add(p)
                        }
                    }
                    rules.add(LinkSettings.HostRule(h, interval, paths))
                }
                l.hostRules = rules
            }
            return l
        }

        private val HOST_PATTERN = Regex("[a-z0-9.-]{1,253}")
        private val PATH_PATTERN = Regex("/[A-Za-z0-9._~/-]{0,199}")

        private fun safeName(name: String): String = name.replace(Regex("[^A-Za-z0-9_.-]"), "_")
    }
}
