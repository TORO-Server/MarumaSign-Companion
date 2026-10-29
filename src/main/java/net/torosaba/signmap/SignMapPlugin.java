package net.torosaba.signmap;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * MarumaSign の形式の看板を BlueMap / Dynmap のマーカーとして出すプラグイン。
 * 看板の走査と保存は共通で、地図ごとの出し方は {@link MapBackend} が受け持つ (どちらか一方だけ入っていても動く)。
 */
public final class SignMapPlugin extends JavaPlugin {

    static final String MARKER_SET_ID = "marumasign";

    private volatile Settings settings = new Settings();
    private volatile MarkerHtml markerHtml = new MarkerHtml(new UrlFilter(List.of()), settings);

    private final Map<String, WorldState> worlds = new ConcurrentHashMap<>();
    // region ディレクトリがまだなかったワールド (できたばかりのネザーなど)。走査のたびにもう一度探す
    private final Map<String, Path> pendingWorlds = new ConcurrentHashMap<>();
    private final List<MapBackend> backends = new CopyOnWriteArrayList<>();
    // ゲーム中の変更を地図に反映する専用のスレッド (長い走査や、地図側の重い更新でメインスレッドを待たせない)
    private volatile ExecutorService liveExecutor;
    private volatile ScheduledExecutorService scheduler;
    private final AtomicBoolean scanning = new AtomicBoolean();
    private final AtomicBoolean scanRequested = new AtomicBoolean();
    private volatile boolean shuttingDown;

    static final class WorldState {
        final String name;
        final Path regionDir;
        final SignStore store = new SignStore();
        final Path cacheFile;

        WorldState(String name, Path regionDir, Path cacheFile) {
            this.name = name;
            this.regionDir = regionDir;
            this.cacheFile = cacheFile;
        }
    }

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadSettings();
        shuttingDown = false;
        if (settings.dnsCheck) {
            final HostGuard g = new HostGuard(getLogger(), true, this::refreshMarkers);
            g.start();
            hostGuard = g;
        }
        if (settings.link.enabled) {
            final UrlFilter filter = new UrlFilter(settings.blockedHosts);
            for (String bad : new AddressDenyList(settings.link.extraBlockedAddresses).rejected()) {
                getLogger().warning("link-check.extra-blocked-addresses: ignoring an entry that is not an address or range: " + SafeText.logUrl(bad));
            }
            final HostThrottle throttle = new HostThrottle(settings.link);
            final LinkProbe probe = LinkProbe.create(settings.link, filter, throttle);
            final LinkChecker checker = new LinkChecker(getLogger(), getDataFolder().toPath().resolve("linkcheck.json"), settings.link,
                    url -> probe.strictParse(url) != null, probe.asProber(), throttle, System::currentTimeMillis, this::linkStateChanged);
            checker.start();
            linkChecker = checker;
            getLogger().info("Link check is on: the server will connect to URLs written on signs (see link-check in config.yml); first check starts in "
                    + settings.link.startupDelayMinutes + " min");
        }
        if (settings.resolveShortLinks) {
            final ShortLinkResolver r = new ShortLinkResolver(getLogger(), new UrlFilter(settings.blockedHosts), settings.shortLinkHosts,
                    getDataFolder().toPath().resolve("shortlinks.json"), this::refreshMarkers);
            r.load();
            r.start();
            resolver = r;
        }
        getServer().getPluginManager().registerEvents(new SignListener(this), this);
        liveExecutor = Executors.newSingleThreadExecutor(r -> {
            final Thread t = new Thread(r, "BlueMapMarumaSign-live");
            t.setDaemon(true);
            return t;
        });
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            final Thread t = new Thread(r, "BlueMapMarumaSign-main");
            t.setDaemon(true);
            t.setPriority(Thread.MIN_PRIORITY);
            return t;
        });
        if (getConfig().getBoolean("bluemap.enabled", true)) {
            if (Bukkit.getPluginManager().getPlugin("BlueMap") != null) {
                addBackend(Backends.blueMap(this));
            } else {
                getLogger().info("BlueMap is not installed; BlueMap markers are off");
            }
        }
        if (getConfig().getBoolean("dynmap.enabled", true)) {
            if (Bukkit.getPluginManager().getPlugin("dynmap") != null) {
                addBackend(Backends.dynmap(this));
            } else {
                getLogger().info("Dynmap is not installed; Dynmap markers are off");
            }
        }
        if (backends.isEmpty()) getLogger().warning("No map plugin (BlueMap or Dynmap) is enabled; nothing to do");
        final ScheduledExecutorService s = scheduler;
        if (settings.rescanIntervalMinutes > 0) {
            s.scheduleWithFixedDelay(this::requestScan, settings.rescanIntervalMinutes, settings.rescanIntervalMinutes, TimeUnit.MINUTES);
        }
        s.scheduleWithFixedDelay(this::saveDirty, 5, 5, TimeUnit.MINUTES);
    }

    private void addBackend(MapBackend backend) {
        backends.add(backend);
        try {
            backend.start();
        } catch (RuntimeException | LinkageError e) {
            getLogger().warning(backend.name() + " support failed to start: " + e);
            backends.remove(backend);
        }
    }

    @Override
    public void onDisable() {
        shuttingDown = true;
        for (MapBackend b : backends) {
            try {
                b.stop();
            } catch (RuntimeException | LinkageError e) {
                getLogger().warning(b.name() + " support failed to stop: " + e);
            }
        }
        backends.clear();
        final LinkChecker checker = linkChecker;
        linkChecker = null;
        if (checker != null) checker.stop();
        final ShortLinkResolver r = resolver;
        resolver = null;
        if (r != null) r.stop();
        final HostGuard g = hostGuard;
        hostGuard = null;
        if (g != null) g.stop();
        final ScheduledExecutorService s = scheduler;
        if (s != null) s.shutdownNow();
        final ExecutorService live = liveExecutor;
        if (live != null) live.shutdownNow();
        for (WorldState w : worlds.values()) saveCache(w);
        worlds.clear();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission(AdminCommands.PERMISSION)) {
            sender.sendMessage("You do not have permission.");
            return true;
        }
        final String sub = args.length > 0 ? args[0].toLowerCase() : "status";
        switch (sub) {
            case "broken", "linkstatus", "recheck" -> admin.handle(sender, sub, args);
            case "reload" -> {
                reloadConfig();
                loadSettings();
                refreshMarkers();
                sender.sendMessage("MarumaSign map: reloaded config and refreshed markers.");
            }
            case "rescan" -> {
                sender.sendMessage("MarumaSign map: scanning changed region files in the background.");
                requestScan();
            }
            default -> {
                for (WorldState w : worlds.values()) {
                    sender.sendMessage("MarumaSign map [" + w.name + "]: " + w.store.count() + " signs");
                }
                final StringBuilder sb = new StringBuilder("MarumaSign map backends:");
                for (MapBackend b : backends) sb.append(' ').append(b.name());
                sender.sendMessage(sb.toString());
                final ShortLinkResolver r = resolver;
                if (r != null) {
                    sender.sendMessage("MarumaSign map: short links resolved " + r.resolvedCount() + ", waiting " + r.pending());
                }
                final HostGuard g = hostGuard;
                if (g != null) sender.sendMessage("MarumaSign map: hosts waiting for the DNS check " + g.pending());
            }
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission(AdminCommands.PERMISSION)) return List.of();
        return admin.complete(args);
    }

    private final AdminCommands admin = new AdminCommands(this);

    private void loadSettings() {
        final FileConfiguration c = getConfig();
        final Settings s = new Settings();
        s.dynmapShowOk = c.getBoolean("dynmap.show-ok", s.dynmapShowOk);
        s.link = readLinkSettings(c);
        s.maxMarkers = Math.max(0, c.getInt("max-markers", s.maxMarkers));
        final List<String> ext = c.getStringList("allowed-extensions");
        if (!ext.isEmpty()) s.allowedExtensions = Settings.normalizeExtensions(ext);
        s.allowNoExtension = c.getBoolean("allow-no-extension", s.allowNoExtension);
        s.blockedHosts = new ArrayList<>(c.getStringList("blocked-hosts"));
        s.scanThreads = Math.max(1, Math.min(8, c.getInt("scan.threads", s.scanThreads)));
        s.pauseMillisPerRegion = Math.max(0, c.getLong("scan.pause-millis-per-region", s.pauseMillisPerRegion));
        s.rescanIntervalMinutes = Math.max(0, c.getInt("scan.rescan-interval-minutes", s.rescanIntervalMinutes));
        s.thumbnailSize = Math.max(16, Math.min(512, c.getInt("marker.thumbnail-size", s.thumbnailSize)));
        s.maxDistance = c.getDouble("marker.max-distance", s.maxDistance);
        s.defaultHidden = c.getBoolean("marker.default-hidden", s.defaultHidden);
        s.dynmapDefaultHidden = c.getBoolean("dynmap.default-hidden", s.dynmapDefaultHidden);
        s.markerSetLabel = c.getString("marker.set-label", s.markerSetLabel);
        final String mode = c.getString("bluemap.mode", s.bluemapMode).toLowerCase(java.util.Locale.ROOT);
        if (mode.equals("3d") || mode.equals("markers") || mode.equals("both")) s.bluemapMode = mode;
        else getLogger().warning("Unknown bluemap.mode '" + mode + "'; using " + s.bluemapMode);
        s.resolveShortLinks = c.getBoolean("short-links.resolve", s.resolveShortLinks);
        final List<String> hosts = c.getStringList("short-links.hosts");
        if (!hosts.isEmpty()) {
            s.shortLinkHosts = new java.util.TreeSet<>();
            for (String h : hosts) s.shortLinkHosts.add(h.trim().toLowerCase(java.util.Locale.ROOT));
        }
        s.dnsCheck = c.getBoolean("security.dns-check", s.dnsCheck);
        s.web3dMaxSize = Math.max(1, Math.min(1000, c.getDouble("web3d.max-size", s.web3dMaxSize)));
        s.web3dMaxOffset = Math.max(1, Math.min(1000, c.getDouble("web3d.max-offset", s.web3dMaxOffset)));
        s.web3dMaxSigns = Math.max(0, c.getInt("web3d.max-signs", s.web3dMaxSigns));
        s.web3dDrawDistance = Math.max(16, Math.min(512, c.getInt("web3d.draw-distance", s.web3dDrawDistance)));
        s.web3dMaxActive = Math.max(10, Math.min(1000, c.getInt("web3d.max-active", s.web3dMaxActive)));
        s.web3dTextureSize = Math.max(64, Math.min(4096, c.getInt("web3d.texture-size", s.web3dTextureSize)));
        this.settings = s;
        final UrlFilter filter = new UrlFilter(s.blockedHosts);
        this.markerHtml = new MarkerHtml(filter, s);
        final ShortLinkResolver r = resolver;
        if (r != null) r.setHosts(s.shortLinkHosts);
    }

    /** config.yml の link-check を読む。値の範囲は収める (不正な値で検査が暴走しないように) */
    static LinkSettings readLinkSettings(org.bukkit.configuration.ConfigurationSection c) {
        final LinkSettings l = new LinkSettings();
        l.enabled = c.getBoolean("link-check.enabled", l.enabled);
        l.concurrency = LinkSettings.clamp(c.getInt("link-check.concurrency", l.concurrency), 1, 4);
        l.startupDelayMinutes = LinkSettings.clamp(c.getInt("link-check.startup-delay-minutes", l.startupDelayMinutes), 0, 1440);
        l.perHourLimit = LinkSettings.clamp(c.getInt("link-check.per-hour-limit", l.perHourLimit), 1, 100_000);
        l.hostPerHourLimit = LinkSettings.clamp(c.getInt("link-check.host-per-hour-limit", l.hostPerHourLimit), 1, 10_000);
        l.hostMinIntervalSeconds = LinkSettings.clamp(c.getInt("link-check.host-min-interval-seconds", l.hostMinIntervalSeconds), 0, 3600);
        l.connectTimeoutSeconds = LinkSettings.clamp(c.getInt("link-check.timeouts.connect-seconds", l.connectTimeoutSeconds), 1, 30);
        l.totalTimeoutSeconds = LinkSettings.clamp(c.getInt("link-check.timeouts.total-seconds", l.totalTimeoutSeconds), 2, 120);
        l.maxBodyKb = LinkSettings.clamp(c.getInt("link-check.max-body-kb", l.maxBodyKb), 1, 256);
        l.maxRedirects = LinkSettings.clamp(c.getInt("link-check.max-redirects", l.maxRedirects), 0, 10);
        l.recheckOkDays = LinkSettings.clamp(c.getInt("link-check.recheck-ok-days", l.recheckOkDays), 1, 365);
        l.recheckBrokenHours = LinkSettings.clamp(c.getInt("link-check.recheck-broken-hours", l.recheckBrokenHours), 1, 720);
        final List<Integer> backoff = new ArrayList<>();
        for (Integer h : c.getIntegerList("link-check.unknown-backoff-hours")) backoff.add(LinkSettings.clamp(h, 1, 720));
        if (!backoff.isEmpty()) l.unknownBackoffHours = backoff;
        l.brokenAfterFailures = LinkSettings.clamp(c.getInt("link-check.broken-after-failures", l.brokenAfterFailures), 1, 20);
        l.brokenAfterHours = LinkSettings.clamp(c.getInt("link-check.broken-after-hours", l.brokenAfterHours), 0, 720);
        l.treat403AsBroken = c.getBoolean("link-check.treat-403-as-broken", l.treat403AsBroken);
        l.maxTrackedUrls = LinkSettings.clamp(c.getInt("link-check.max-tracked-urls", l.maxTrackedUrls), 100, 200_000);
        l.userAgent = LinkSettings.cleanUserAgent(c.getString("link-check.user-agent", l.userAgent));
        for (String a : c.getStringList("link-check.extra-blocked-addresses")) {
            if (a != null && a.length() <= 64) l.extraBlockedAddresses.add(a.trim());
        }
        if (c.contains("link-check.host-rules")) {
            final List<LinkSettings.HostRule> rules = new ArrayList<>();
            for (java.util.Map<?, ?> m : c.getMapList("link-check.host-rules")) {
                final Object host = m.get("host");
                if (host == null) continue;
                final String h = host.toString().trim().toLowerCase(java.util.Locale.ROOT);
                if (!h.matches("[a-z0-9.-]{1,253}")) continue;
                int interval = 0;
                if (m.get("min-interval-seconds") instanceof Number n) interval = LinkSettings.clamp(n.intValue(), 0, 3600);
                final List<String> paths = new ArrayList<>();
                if (m.get("removed-paths") instanceof List<?> list) {
                    for (Object o : list) {
                        final String p = String.valueOf(o);
                        if (p.matches("/[A-Za-z0-9._~/-]{0,199}")) paths.add(p);
                    }
                }
                rules.add(new LinkSettings.HostRule(h, interval, paths));
            }
            l.hostRules = rules;
        }
        return l;
    }

    private volatile LinkChecker linkChecker;

    LinkChecker linkChecker() {
        return linkChecker;
    }

    // 検査の結果が変わったら、まとめて (10 秒に 1 回まで) 地図に反映する
    private final AtomicBoolean linkRefreshPending = new AtomicBoolean();

    private void linkStateChanged() {
        final ScheduledExecutorService s = scheduler;
        if (s == null || shuttingDown || !linkRefreshPending.compareAndSet(false, true)) return;
        try {
            s.schedule(() -> {
                linkRefreshPending.set(false);
                for (MapBackend b : backends) {
                    try {
                        b.linkStateChanged();
                    } catch (RuntimeException e) {
                        getLogger().warning(b.name() + " link state update failed: " + e.getClass().getSimpleName());
                    }
                }
            }, 10, TimeUnit.SECONDS);
        } catch (RuntimeException e) {
            linkRefreshPending.set(false);
        }
    }

    /** いま看板にあるすべての URL を、検査の対象として知らせる */
    private void trackLinks() {
        final LinkChecker checker = linkChecker;
        if (checker == null) return;
        final java.util.Set<String> urls = new java.util.HashSet<>();
        for (WorldState w : worlds.values()) {
            for (SignRecord s : w.store.all()) urls.add(s.url());
        }
        checker.track(urls);
    }

    private volatile ShortLinkResolver resolver;

    ShortLinkResolver resolver() {
        return resolver;
    }

    /** 短縮 URL は行き先が分かっていれば行き先に置き換える (まだなら調べるよう頼む) */
    SignRecord effective(SignRecord sign) {
        final ShortLinkResolver r = resolver;
        if (r == null || !r.isShort(sign.url())) return sign;
        final String target = r.resolved(sign.url());
        if (target == null) {
            r.request(sign.url());
            return sign;
        }
        return sign.withUrl(target);
    }

    Settings settings() {
        return settings;
    }

    MarkerHtml markerHtml() {
        return markerHtml;
    }

    // ---- ワールドと走査 ----

    /**
     * 看板を調べるワールドとして登録する (保存してあるキャッシュも読む)。同じワールドを複数の地図から登録してもよい。
     * region ディレクトリが見つからなければ null。
     */
    WorldState registerWorld(World world, Path extraBase) {
        final WorldState existing = worlds.get(world.getName());
        if (existing != null) return existing;
        final ConfigurationSection overrides = getConfig().getConfigurationSection("regions");
        final Path regionDir = RegionDirs.resolve(world, extraBase, overrides == null ? null : overrides.getString(world.getName()), getLogger());
        if (regionDir == null) {
            if (pendingWorlds.put(world.getName(), extraBase == null ? Path.of("") : extraBase) == null) {
                getLogger().info("[" + world.getName() + "] No region directory yet; will look again at the next scan");
            }
            return null;
        }
        pendingWorlds.remove(world.getName());
        final Path cache = getDataFolder().toPath().resolve("cache").resolve(safeName(world.getName()) + ".json");
        final WorldState state = new WorldState(world.getName(), regionDir, cache);
        try {
            state.store.load(cache);
            getLogger().info("[" + world.getName() + "] Loaded " + state.store.count() + " signs from cache");
        } catch (IOException e) {
            getLogger().warning("[" + world.getName() + "] Ignoring cache: " + e.getMessage());
        }
        final WorldState prev = worlds.putIfAbsent(world.getName(), state);
        if (prev != null) return prev;
        getLogger().info("[" + world.getName() + "] Region directory: " + regionDir);
        return state;
    }

    WorldState worldState(String name) {
        return worlds.get(name);
    }

    /** 走査しているワールドの一覧 (名前 → 状態) */
    Map<String, WorldState> worldStates() {
        return new java.util.TreeMap<>(worlds);
    }

    private static String safeName(String name) {
        return name.replaceAll("[^A-Za-z0-9_.-]", "_");
    }

    /** 更新された region ファイルの走査を頼む (実行中なら、終わってからもう一度) */
    void requestScan() {
        final ScheduledExecutorService s = scheduler;
        if (s == null || shuttingDown) return;
        scanRequested.set(true);
        try {
            s.execute(this::runScan);
        } catch (RuntimeException e) {
            // 止まっている途中
        }
    }

    private void runScan() {
        if (shuttingDown || !scanning.compareAndSet(false, true)) return;
        try {
            while (scanRequested.getAndSet(false)) {
                retryPendingWorlds();
                for (WorldState w : new ArrayList<>(worlds.values())) {
                    if (shuttingDown) return;
                    try {
                        final WorldScanner scanner = new WorldScanner(getLogger(), w.name, w.regionDir, w.store,
                                settings.scanThreads, settings.pauseMillisPerRegion);
                        final WorldScanner.Summary sum = scanner.scan(500, this::refreshMarkers);
                        if (sum.regionsScanned > 0) {
                            getLogger().info("[" + w.name + "] Scan finished: " + sum.regionsScanned + "/" + sum.regionsTotal
                                    + " regions, " + sum.chunks + " chunks, " + sum.rawSigns + " signs, " + sum.marumaSigns
                                    + " MarumaSign signs, " + sum.chunkErrors + " chunk errors, " + sum.fileErrors
                                    + " file errors, " + sum.millis / 1000.0 + " s");
                            saveCache(w);
                            refreshMarkers();
                        }
                    } catch (IOException e) {
                        getLogger().warning("[" + w.name + "] Scan failed: " + e);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }
        } finally {
            scanning.set(false);
        }
    }

    private void retryPendingWorlds() {
        for (Map.Entry<String, Path> e : new ArrayList<>(pendingWorlds.entrySet())) {
            final World world = Bukkit.getWorld(e.getKey());
            if (world == null) {
                pendingWorlds.remove(e.getKey());
                continue;
            }
            final Path base = e.getValue().toString().isEmpty() ? null : e.getValue();
            if (registerWorld(world, base) != null) refreshMarkers();
        }
    }

    private void saveDirty() {
        for (WorldState w : worlds.values()) {
            if (w.store.isDirty()) saveCache(w);
        }
    }

    private void saveCache(WorldState w) {
        try {
            w.store.save(w.cacheFile);
        } catch (IOException e) {
            getLogger().warning("[" + w.name + "] Could not save cache: " + e);
        }
    }

    // ---- マーカー ----

    /** そのワールドで地図に出す看板 (表示してよいもの。最大数まで) */
    List<MarkerHtml.View> views(WorldState w) {
        final List<MarkerHtml.View> out = new ArrayList<>();
        final MarkerHtml html = markerHtml;
        final int max = settings.maxMarkers;
        for (SignRecord sign : w.store.all()) {
            if (out.size() >= max) break;
            final MarkerHtml.View view = classify(sign);
            if (view != null) out.add(view);
        }
        return out;
    }

    /** 3D 表示に出す看板 (最大数まで)。短縮 URL は行き先に置き換えてある */
    List<MarkerHtml.View> views3d(WorldState w) {
        final List<MarkerHtml.View> out = new ArrayList<>();
        final int max = settings.web3dMaxSigns;
        for (SignRecord sign : w.store.all()) {
            if (out.size() >= max) break;
            final MarkerHtml.View view = classify(sign);
            if (view != null) out.add(view);
        }
        return out;
    }

    /**
     * 表示してよい看板ならその内容、だめなら null。短縮 URL の行き先への置き換えと、
     * URL の形・拡張子の確認 ({@link MarkerHtml}) のあと、ホスト名を DNS で引いて公開のアドレスか確かめる ({@link HostGuard})。
     */
    MarkerHtml.View classify(SignRecord sign) {
        final MarkerHtml.View view = markerHtml.classify(effective(sign));
        if (view == null) return null;
        final HostGuard guard = hostGuard;
        if (guard == null) return view;
        try {
            final String host = java.net.URI.create(view.url()).getHost();
            return guard.isPublic(host) ? view : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private volatile HostGuard hostGuard;

    HostGuard hostGuard() {
        return hostGuard;
    }

    void refreshMarkers() {
        trackLinks();
        for (MapBackend b : backends) {
            try {
                b.refresh();
            } catch (RuntimeException e) {
                getLogger().warning(b.name() + " marker refresh failed: " + e);
            }
        }
    }

    /** ゲーム中の看板の追加・変更を反映する */
    void signChanged(World world, int x, int y, int z, String text, int orient) {
        final WorldState w = worlds.get(world.getName());
        if (w == null) return;
        final MarumaSpec spec = MarumaSpec.parse(text);
        if (spec == null || spec.url().length() > UrlFilter.MAX_URL_LENGTH) {
            signRemoved(world, x, y, z);
            return;
        }
        final SignRecord record = new SignRecord(x, y, z, spec.url(), spec.values(), orient);
        w.store.liveSet(record);
        final LinkChecker checker = linkChecker;
        if (checker != null) checker.prioritize(record.url());
        final MarkerHtml.View view = classify(record);
        final String worldName = world.getName();
        runLive(() -> {
            for (MapBackend b : backends) b.signUpdated(worldName, record, view);
        });
    }

    void signRemoved(World world, int x, int y, int z) {
        final WorldState w = worlds.get(world.getName());
        if (w == null) return;
        if (!w.store.liveRemove(x, y, z)) return;
        final String worldName = world.getName();
        runLive(() -> {
            for (MapBackend b : backends) b.signRemoved(worldName, x, y, z);
        });
    }

    private void runLive(Runnable task) {
        final ExecutorService live = liveExecutor;
        if (live == null) return;
        try {
            live.execute(() -> {
                try {
                    task.run();
                } catch (RuntimeException e) {
                    getLogger().warning("Marker update failed: " + e);
                }
            });
        } catch (RuntimeException e) {
            // 止まっている途中
        }
    }

    boolean tracks(World world) {
        return worlds.containsKey(world.getName());
    }
}
