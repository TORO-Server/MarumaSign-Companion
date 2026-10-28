package net.torosaba.signmap;

import de.bluecolored.bluemap.api.BlueMapAPI;
import de.bluecolored.bluemap.api.BlueMapMap;
import de.bluecolored.bluemap.api.BlueMapWorld;
import de.bluecolored.bluemap.api.markers.HtmlMarker;
import de.bluecolored.bluemap.api.markers.MarkerSet;
import com.flowpowered.math.vector.Vector2i;
import com.flowpowered.math.vector.Vector3d;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * MarumaSign の形式の看板を BlueMap のマーカーとして出すプラグイン。
 */
public final class SignMapPlugin extends JavaPlugin {

    static final String MARKER_SET_ID = "marumasign";

    private Settings settings = new Settings();
    private UrlFilter filter = new UrlFilter(List.of());
    private MarkerHtml markerHtml = new MarkerHtml(filter, settings);

    /** BlueMap の地図があるワールド (Bukkit のワールドの名前 → 状態) */
    private final Map<String, WorldState> worlds = new ConcurrentHashMap<>();
    private volatile ScheduledExecutorService scheduler;
    private final AtomicBoolean scanning = new AtomicBoolean();
    private volatile boolean shuttingDown;

    private final Consumer<BlueMapAPI> onBlueMapEnable = this::enableBlueMap;
    private final Consumer<BlueMapAPI> onBlueMapDisable = this::disableBlueMap;

    static final class WorldState {
        final String name;
        final Path regionDir;
        final SignStore store = new SignStore();
        final Path cacheFile;
        volatile List<BlueMapMap> maps = List.of();

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
        getServer().getPluginManager().registerEvents(new SignListener(this), this);
        shuttingDown = false;
        BlueMapAPI.onEnable(onBlueMapEnable);
        BlueMapAPI.onDisable(onBlueMapDisable);
    }

    @Override
    public void onDisable() {
        shuttingDown = true;
        BlueMapAPI.unregisterListener(onBlueMapEnable);
        BlueMapAPI.unregisterListener(onBlueMapDisable);
        final ScheduledExecutorService s = scheduler;
        if (s != null) s.shutdownNow();
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
                runScan();
            }
            default -> {
                for (WorldState w : worlds.values()) {
                    sender.sendMessage("MarumaSign map [" + w.name + "]: " + w.store.count() + " signs");
                }
                if (worlds.isEmpty()) sender.sendMessage("MarumaSign map: BlueMap is not enabled or has no maps.");
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
        this.filter = new UrlFilter(s.blockedHosts);
        this.markerHtml = new MarkerHtml(filter, s);
    }

    // ---- BlueMap ----

    private void enableBlueMap(BlueMapAPI api) {
        getLogger().info("BlueMap " + api.getBlueMapVersion() + " enabled; preparing MarumaSign markers");
        worlds.clear();
        final ConfigurationSection overrides = getConfig().getConfigurationSection("regions");
        for (World world : Bukkit.getWorlds()) {
            final BlueMapWorld bw = api.getWorld(world).orElse(null);
            if (bw == null || bw.getMaps().isEmpty()) continue;
            final Path regionDir = RegionDirs.resolve(world, bw, overrides == null ? null : overrides.getString(world.getName()), getLogger());
            if (regionDir == null) {
                getLogger().warning("No region directory found for world '" + world.getName() + "'; skipped");
                continue;
            }
            final Path cache = getDataFolder().toPath().resolve("cache").resolve(safeName(world.getName()) + ".json");
            final WorldState state = new WorldState(world.getName(), regionDir, cache);
            try {
                state.store.load(cache);
                getLogger().info("[" + world.getName() + "] Loaded " + state.store.count() + " signs from cache");
            } catch (IOException e) {
                getLogger().warning("[" + world.getName() + "] Ignoring cache: " + e.getMessage());
            }
            state.maps = new ArrayList<>(bw.getMaps());
            worlds.put(world.getName(), state);
            getLogger().info("[" + world.getName() + "] Region directory: " + regionDir);
        }
        refreshMarkers();
        final ScheduledExecutorService s = Executors.newSingleThreadScheduledExecutor(r -> {
            final Thread t = new Thread(r, "BlueMapMarumaSign-main");
            t.setDaemon(true);
            t.setPriority(Thread.MIN_PRIORITY);
            return t;
        });
        scheduler = s;
        s.execute(this::runScan);
        if (settings.rescanIntervalMinutes > 0) {
            s.scheduleWithFixedDelay(this::runScan, settings.rescanIntervalMinutes, settings.rescanIntervalMinutes, TimeUnit.MINUTES);
        }
        s.scheduleWithFixedDelay(this::saveDirty, 5, 5, TimeUnit.MINUTES);
    }

    private void disableBlueMap(BlueMapAPI api) {
        final ScheduledExecutorService s = scheduler;
        if (s != null) s.shutdownNow();
        for (WorldState w : worlds.values()) saveCache(w);
        worlds.clear();
    }

    private static String safeName(String name) {
        return name.replaceAll("[^A-Za-z0-9_.-]", "_");
    }

    private void runScan() {
        if (shuttingDown || !scanning.compareAndSet(false, true)) return;
        try {
            for (WorldState w : new ArrayList<>(worlds.values())) {
                if (shuttingDown) return;
                try {
                    final WorldScanner scanner = new WorldScanner(getLogger(), w.name, w.regionDir, w.store,
                            settings.scanThreads, settings.pauseMillisPerRegion);
                    final WorldScanner.Summary sum = scanner.scan(500, this::refreshMarkers);
                    if (sum.regionsScanned > 0) {
                        getLogger().info("[" + w.name + "] Scan finished: " + sum.regionsScanned + "/" + sum.regionsTotal
                                + " regions, " + sum.chunks + " chunks, " + sum.rawSigns + " signs, " + sum.marumaSigns
                                + " MarumaSign signs, " + sum.chunkErrors + " chunk errors, " + sum.fileErrors + " file errors, "
                                + sum.millis / 1000.0 + " s");
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
        } finally {
            scanning.set(false);
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

    private final Object markerLock = new Object();

    /** 看板の一覧から、BlueMap の各地図のマーカーセットを作り直す */
    void refreshMarkers() {
        synchronized (markerLock) {
            for (WorldState w : worlds.values()) {
                final MarkerSet set = buildMarkerSet(w);
                for (BlueMapMap map : w.maps) {
                    map.getMarkerSets().put(MARKER_SET_ID, set);
                }
            }
        }
    }

    private MarkerSet buildMarkerSet(WorldState w) {
        final MarkerSet set = MarkerSet.builder()
                .label(settings.markerSetLabel)
                .toggleable(true)
                .defaultHidden(settings.defaultHidden)
                .build();
        int count = 0;
        for (SignRecord sign : w.store.all()) {
            if (count >= settings.maxMarkers) break;
            final MarkerHtml.View view = markerHtml.classify(sign);
            if (view == null) continue;
            set.put(markerId(sign), buildMarker(view));
            count++;
        }
        return set;
    }

    private static String markerId(SignRecord s) {
        return "sign_" + s.x() + "_" + s.y() + "_" + s.z();
    }

    private HtmlMarker buildMarker(MarkerHtml.View view) {
        final SignRecord s = view.sign();
        final HtmlMarker.Builder b = HtmlMarker.builder()
                .label(markerHtml.label(view))
                .position(new Vector3d(s.x() + 0.5, s.y() + 0.5, s.z() + 0.5))
                .anchor(new Vector2i(0, 0))
                .html(markerHtml.html(view));
        if (settings.maxDistance > 0) b.maxDistance(settings.maxDistance);
        return b.build();
    }

    /** ゲーム中の看板の追加・変更・削除を反映する */
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
        for (BlueMapMap map : w.maps) {
            final MarkerSet set = map.getMarkerSets().get(MARKER_SET_ID);
            if (set == null) continue;
            if (view == null) set.remove(markerId(record));
            else if (set.getMarkers().size() < settings.maxMarkers || set.get(markerId(record)) != null) {
                set.put(markerId(record), buildMarker(view));
            }
        }
    }

    void signRemoved(World world, int x, int y, int z) {
        final WorldState w = worlds.get(world.getName());
        if (w == null) return;
        if (!w.store.liveRemove(x, y, z)) return;
        final String id = "sign_" + x + "_" + y + "_" + z;
        for (BlueMapMap map : w.maps) {
            final MarkerSet set = map.getMarkerSets().get(MARKER_SET_ID);
            if (set != null) set.remove(id);
        }
    }

    boolean tracks(World world) {
        return worlds.containsKey(world.getName());
    }

    Collection<WorldState> worldStates() {
        return worlds.values();
    }
}
