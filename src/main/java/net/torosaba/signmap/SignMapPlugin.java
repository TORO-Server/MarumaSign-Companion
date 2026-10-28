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
        final ScheduledExecutorService s = scheduler;
        if (s != null) s.shutdownNow();
        final ExecutorService live = liveExecutor;
        if (live != null) live.shutdownNow();
        for (WorldState w : worlds.values()) saveCache(w);
        worlds.clear();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("bluemapmarumasign.admin")) {
            sender.sendMessage("You do not have permission.");
            return true;
        }
        final String sub = args.length > 0 ? args[0].toLowerCase() : "status";
        switch (sub) {
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
            }
        }
        return true;
    }

    private void loadSettings() {
        final FileConfiguration c = getConfig();
        final Settings s = new Settings();
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
        s.markerSetLabel = c.getString("marker.set-label", s.markerSetLabel);
        this.settings = s;
        this.markerHtml = new MarkerHtml(new UrlFilter(s.blockedHosts), s);
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
            final MarkerHtml.View view = html.classify(sign);
            if (view != null) out.add(view);
        }
        return out;
    }

    void refreshMarkers() {
        for (MapBackend b : backends) {
            try {
                b.refresh();
            } catch (RuntimeException e) {
                getLogger().warning(b.name() + " marker refresh failed: " + e);
            }
        }
    }

    /** ゲーム中の看板の追加・変更を反映する */
    void signChanged(World world, int x, int y, int z, String text) {
        final WorldState w = worlds.get(world.getName());
        if (w == null) return;
        final MarumaSpec spec = MarumaSpec.parse(text);
        if (spec == null || spec.url().length() > UrlFilter.MAX_URL_LENGTH) {
            signRemoved(world, x, y, z);
            return;
        }
        final SignRecord record = new SignRecord(x, y, z, spec.url());
        w.store.liveSet(record);
        final MarkerHtml.View view = markerHtml.classify(record);
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
