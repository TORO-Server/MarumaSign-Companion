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

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/** BlueMap の HtmlMarker で出す */
final class BlueMapBackend implements MapBackend {

    private final SignMapPlugin plugin;
    private final Map<String, List<BlueMapMap>> maps = new ConcurrentHashMap<>();
    private final Object lock = new Object();
    private final Consumer<BlueMapAPI> onEnable = this::enable;
    private final Consumer<BlueMapAPI> onDisable = this::disable;

    BlueMapBackend(SignMapPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public String name() {
        return "BlueMap";
    }

    @Override
    public void start() {
        BlueMapAPI.onEnable(onEnable);
        BlueMapAPI.onDisable(onDisable);
    }

    @Override
    public void stop() {
        BlueMapAPI.unregisterListener(onEnable);
        BlueMapAPI.unregisterListener(onDisable);
        maps.clear();
    }

    private void enable(BlueMapAPI api) {
        plugin.getLogger().info("BlueMap " + api.getBlueMapVersion() + " enabled; preparing MarumaSign markers");
        maps.clear();
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
    }

    @Override
    public void refresh() {
        synchronized (lock) {
            for (Map.Entry<String, List<BlueMapMap>> e : maps.entrySet()) {
                final SignMapPlugin.WorldState w = plugin.worldState(e.getKey());
                if (w == null) continue;
                final MarkerSet set = MarkerSet.builder()
                        .label(plugin.settings().markerSetLabel)
                        .toggleable(true)
                        .defaultHidden(plugin.settings().defaultHidden)
                        .build();
                for (MarkerHtml.View view : plugin.views(w)) {
                    set.put(markerId(view.sign().x(), view.sign().y(), view.sign().z()), buildMarker(view));
                }
                for (BlueMapMap map : e.getValue()) map.getMarkerSets().put(SignMapPlugin.MARKER_SET_ID, set);
            }
        }
    }

    @Override
    public void signUpdated(String worldName, SignRecord sign, MarkerHtml.View view) {
        final List<BlueMapMap> list = maps.get(worldName);
        if (list == null) return;
        final String id = markerId(sign.x(), sign.y(), sign.z());
        for (BlueMapMap map : list) {
            final MarkerSet set = map.getMarkerSets().get(SignMapPlugin.MARKER_SET_ID);
            if (set == null) continue;
            if (view == null) set.remove(id);
            else if (set.getMarkers().size() < plugin.settings().maxMarkers || set.get(id) != null) set.put(id, buildMarker(view));
        }
    }

    @Override
    public void signRemoved(String worldName, int x, int y, int z) {
        final List<BlueMapMap> list = maps.get(worldName);
        if (list == null) return;
        final String id = markerId(x, y, z);
        for (BlueMapMap map : list) {
            final MarkerSet set = map.getMarkerSets().get(SignMapPlugin.MARKER_SET_ID);
            if (set != null) set.remove(id);
        }
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
