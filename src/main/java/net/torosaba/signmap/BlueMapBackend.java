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

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * BlueMap に出す。3D モード (既定) では、看板の画像を 3D の空間に置く Web 側のスクリプト (marumasign3d.js) と、
 * その看板データ (webroot/marumasign/&lt;地図の id&gt;.json) を用意する。markers モードでは HtmlMarker を使う。
 */
final class BlueMapBackend implements MapBackend {

    static final String SCRIPT_RESOURCE = "/web/marumasign3d.js";
    static final String SCRIPT_PATH = "js/marumasign3d.js";

    private final SignMapPlugin plugin;
    private final Map<String, List<BlueMapMap>> maps = new ConcurrentHashMap<>();
    private final Object lock = new Object();
    private final Consumer<BlueMapAPI> onEnable = this::enable;
    private final Consumer<BlueMapAPI> onDisable = this::disable;
    private volatile Path webRoot;
    private volatile ScheduledExecutorService exporter;
    private final AtomicBoolean exportPending = new AtomicBoolean();

    BlueMapBackend(SignMapPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public String name() {
        return "BlueMap";
    }

    @Override
    public void start() {
        exporter = Executors.newSingleThreadScheduledExecutor(r -> {
            final Thread t = new Thread(r, "BlueMapMarumaSign-export");
            t.setDaemon(true);
            t.setPriority(Thread.MIN_PRIORITY);
            return t;
        });
        BlueMapAPI.onEnable(onEnable);
        BlueMapAPI.onDisable(onDisable);
    }

    @Override
    public void stop() {
        BlueMapAPI.unregisterListener(onEnable);
        BlueMapAPI.unregisterListener(onDisable);
        final ScheduledExecutorService e = exporter;
        exporter = null;
        if (e != null) e.shutdownNow();
        maps.clear();
    }

    private void enable(BlueMapAPI api) {
        plugin.getLogger().info("BlueMap " + api.getBlueMapVersion() + " enabled; preparing MarumaSign markers (mode: "
                + plugin.settings().bluemapMode + ")");
        maps.clear();
        webRoot = null;
        if (plugin.settings().bluemap3d()) installScript(api);
        for (World world : Bukkit.getWorlds()) {
            final BlueMapWorld bw = api.getWorld(world).orElse(null);
            if (bw == null || bw.getMaps().isEmpty()) continue;
            Path save = null;
            try {
                save = bw.getSaveFolder();
            } catch (RuntimeException | LinkageError e) {
                // BlueMap の版によっては取れない
            }
            plugin.registerWorld(world, save);
            maps.put(world.getName(), new ArrayList<>(bw.getMaps()));
        }
        refresh();
        plugin.requestScan();
    }

    private void disable(BlueMapAPI api) {
        maps.clear();
        webRoot = null;
    }

    /** Web アプリに 3D 表示のスクリプトを置いて登録する */
    private void installScript(BlueMapAPI api) {
        try {
            final Path root = api.getWebApp().getWebRoot();
            final byte[] script;
            try (InputStream in = BlueMapBackend.class.getResourceAsStream(SCRIPT_RESOURCE)) {
                if (in == null) throw new IOException("Missing resource " + SCRIPT_RESOURCE);
                script = in.readAllBytes();
            }
            final Path target = root.resolve(SCRIPT_PATH);
            Files.createDirectories(target.getParent());
            final Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
            Files.write(tmp, script);
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            final String version = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(script)).substring(0, 12);
            api.getWebApp().registerScript(SCRIPT_PATH + "?v=" + version);
            webRoot = root;
            plugin.getLogger().info("3D sign script installed in the BlueMap web root");
        } catch (IOException | java.security.NoSuchAlgorithmException | RuntimeException | LinkageError e) {
            plugin.getLogger().warning("Could not install the 3D sign script: " + e);
        }
    }

    @Override
    public void refresh() {
        synchronized (lock) {
            final Settings st = plugin.settings();
            for (Map.Entry<String, List<BlueMapMap>> e : maps.entrySet()) {
                final SignMapPlugin.WorldState w = plugin.worldState(e.getKey());
                if (w == null) continue;
                if (st.bluemapMarkers()) {
                    final MarkerSet set = MarkerSet.builder()
                            .label(st.markerSetLabel)
                            .toggleable(true)
                            .defaultHidden(st.defaultHidden)
                            .build();
                    for (MarkerHtml.View view : plugin.views(w)) {
                        set.put(markerId(view.sign().x(), view.sign().y(), view.sign().z()), buildMarker(view));
                    }
                    for (BlueMapMap map : e.getValue()) map.getMarkerSets().put(SignMapPlugin.MARKER_SET_ID, set);
                } else {
                    for (BlueMapMap map : e.getValue()) map.getMarkerSets().remove(SignMapPlugin.MARKER_SET_ID);
                }
            }
            if (st.bluemap3d()) exportNow();
        }
    }

    private void exportNow() {
        final Path root = webRoot;
        if (root == null) return;
        for (Map.Entry<String, List<BlueMapMap>> e : maps.entrySet()) {
            final SignMapPlugin.WorldState w = plugin.worldState(e.getKey());
            if (w == null) continue;
            final List<MarkerHtml.View> views = plugin.views3d(w);
            for (BlueMapMap map : e.getValue()) {
                try {
                    WebExporter.write(root.resolve("marumasign").resolve(safeId(map.getId()) + ".json"), views, plugin.settings());
                } catch (IOException ex) {
                    plugin.getLogger().warning("[" + e.getKey() + "] Could not write 3D sign data: " + ex);
                }
            }
        }
    }

    // 看板が変わったら、少し待ってからまとめて書き出す
    private void scheduleExport() {
        final ScheduledExecutorService ex = exporter;
        if (ex == null || webRoot == null || !plugin.settings().bluemap3d()) return;
        if (!exportPending.compareAndSet(false, true)) return;
        try {
            ex.schedule(() -> {
                exportPending.set(false);
                synchronized (lock) {
                    exportNow();
                }
            }, 1500, TimeUnit.MILLISECONDS);
        } catch (RuntimeException e) {
            exportPending.set(false);
        }
    }

    private static String safeId(String id) {
        return id.replaceAll("[^A-Za-z0-9_.-]", "_");
    }

    @Override
    public void signUpdated(String worldName, SignRecord sign, MarkerHtml.View view) {
        final List<BlueMapMap> list = maps.get(worldName);
        if (list == null) return;
        if (plugin.settings().bluemapMarkers()) {
            final String id = markerId(sign.x(), sign.y(), sign.z());
            for (BlueMapMap map : list) {
                final MarkerSet set = map.getMarkerSets().get(SignMapPlugin.MARKER_SET_ID);
                if (set == null) continue;
                if (view == null) set.remove(id);
                else if (set.getMarkers().size() < plugin.settings().maxMarkers || set.get(id) != null) set.put(id, buildMarker(view));
            }
        }
        scheduleExport();
    }

    @Override
    public void signRemoved(String worldName, int x, int y, int z) {
        final List<BlueMapMap> list = maps.get(worldName);
        if (list == null) return;
        if (plugin.settings().bluemapMarkers()) {
            final String id = markerId(x, y, z);
            for (BlueMapMap map : list) {
                final MarkerSet set = map.getMarkerSets().get(SignMapPlugin.MARKER_SET_ID);
                if (set != null) set.remove(id);
            }
        }
        scheduleExport();
    }

    private static String markerId(int x, int y, int z) {
        return "sign_" + x + "_" + y + "_" + z;
    }

    private HtmlMarker buildMarker(MarkerHtml.View view) {
        final SignRecord s = view.sign();
        final HtmlMarker.Builder b = HtmlMarker.builder()
                .label(plugin.markerHtml().label(view))
                .position(new Vector3d(s.x() + 0.5, s.y() + 0.5, s.z() + 0.5))
                .anchor(new Vector2i(0, 0))
                .html(plugin.markerHtml().html(view, plugin.settings().thumbnailSize));
        if (plugin.settings().maxDistance > 0) b.maxDistance(plugin.settings().maxDistance);
        return b.build();
    }
}
