package net.torosaba.signmap;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;
import org.dynmap.DynmapCommonAPI;
import org.dynmap.DynmapCommonAPIListener;
import org.dynmap.markers.Marker;
import org.dynmap.markers.MarkerAPI;
import org.dynmap.markers.MarkerIcon;
import org.dynmap.markers.MarkerSet;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Dynmap の MarkerAPI で出す (ラベルは HTML、アイコンは組み込みの看板) */
final class DynmapBackend implements MapBackend {

    private static final int POPUP_SIZE = 320;

    private final SignMapPlugin plugin;
    private final Object lock = new Object();
    private volatile MarkerSet set;
    private volatile MarkerIcon icon;
    private final Set<String> worldNames = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final DynmapCommonAPIListener listener = new DynmapCommonAPIListener() {
        @Override
        public void apiEnabled(DynmapCommonAPI api) {
            enable(api);
        }

        @Override
        public void apiDisabled(DynmapCommonAPI api) {
            set = null;
            worldNames.clear();
        }
    };

    DynmapBackend(SignMapPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public String name() {
        return "Dynmap";
    }

    @Override
    public void start() {
        DynmapCommonAPIListener.register(listener);
    }

    @Override
    public void stop() {
        DynmapCommonAPIListener.unregister(listener);
        final MarkerSet s = set;
        set = null;
        worldNames.clear();
        if (s != null) {
            try {
                s.deleteMarkerSet();
            } catch (RuntimeException e) {
                // Dynmap が止まっている途中
            }
        }
    }

    private void enable(DynmapCommonAPI api) {
        final MarkerAPI markers = api.getMarkerAPI();
        if (markers == null) {
            plugin.getLogger().warning("Dynmap marker API is not available; Dynmap markers disabled");
            return;
        }
        synchronized (lock) {
            MarkerSet s = markers.getMarkerSet(SignMapPlugin.MARKER_SET_ID);
            if (s != null) s.deleteMarkerSet();
            // persistent=false: Dynmap の markers.yml には書かず、起動のたびに作り直す
            s = markers.createMarkerSet(SignMapPlugin.MARKER_SET_ID, plugin.settings().markerSetLabel, null, false);
            if (s == null) {
                plugin.getLogger().warning("Could not create the Dynmap marker set");
                return;
            }
            s.setHideByDefault(plugin.settings().dynmapDefaultHidden);
            // ラベルは常時表示せず (数千件の枠で地図が埋まる)、アイコンにマウスを乗せたときだけ出す。中身はクリックのポップアップ
            s.setLabelShow(false);
            s.setLayerPriority(10);
            icon = markers.getMarkerIcon(MarkerIcon.SIGN);
            if (icon == null) icon = markers.getMarkerIcon(MarkerIcon.DEFAULT);
            s.setDefaultMarkerIcon(icon);
            set = s;
        }
        worldNames.clear();
        final Set<String> targets = targetWorlds();
        plugin.getLogger().info("Dynmap " + api.getDynmapCoreVersion() + " enabled; worlds: " + targets);
        for (String name : targets) {
            final World world = Bukkit.getWorld(name);
            if (world == null) continue;
            plugin.registerWorld(world, null);
            worldNames.add(name);
        }
        refresh();
        plugin.requestScan();
    }

    /** Dynmap が地図を作るワールド (worlds.txt で有効で、地図があり、いま読み込まれているもの)。config の dynmap.worlds があればそれ */
    private Set<String> targetWorlds() {
        final List<String> configured = plugin.getConfig().getStringList("dynmap.worlds");
        final Set<String> result = new LinkedHashSet<>();
        if (!configured.isEmpty()) {
            for (String n : configured) if (Bukkit.getWorld(n) != null) result.add(n);
            return result;
        }
        final Set<String> loaded = new HashSet<>();
        for (World w : Bukkit.getWorlds()) loaded.add(w.getName());
        final Plugin dynmap = Bukkit.getPluginManager().getPlugin("dynmap");
        boolean read = false;
        if (dynmap != null) {
            final File file = new File(dynmap.getDataFolder(), "worlds.txt");
            if (file.isFile()) {
                try {
                    final YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
                    for (Map<?, ?> entry : yaml.getMapList("worlds")) {
                        final Object name = entry.get("name");
                        final Object enabled = entry.get("enabled");
                        final Object maps = entry.get("maps");
                        if (name == null || !loaded.contains(name.toString())) continue;
                        if (enabled != null && !Boolean.parseBoolean(enabled.toString())) continue;
                        if (maps instanceof List<?> l && l.isEmpty()) continue;
                        result.add(name.toString());
                        read = true;
                    }
                } catch (RuntimeException e) {
                    plugin.getLogger().warning("Could not read Dynmap worlds.txt: " + e);
                }
            }
        }
        if (!read) result.addAll(loaded);
        return result;
    }

    @Override
    public void refresh() {
        final MarkerSet s = set;
        if (s == null) return;
        synchronized (lock) {
            for (String name : worldNames) {
                final SignMapPlugin.WorldState w = plugin.worldState(name);
                if (w == null) continue;
                final String prefix = idPrefix(name);
                final Map<String, MarkerHtml.View> desired = new HashMap<>();
                for (MarkerHtml.View view : plugin.views(w)) {
                    desired.put(markerId(name, view.sign().x(), view.sign().y(), view.sign().z()), view);
                }
                for (Marker m : new ArrayList<>(s.getMarkers())) {
                    final String id = m.getMarkerID();
                    if (id.startsWith(prefix) && !desired.containsKey(id)) m.deleteMarker();
                }
                for (Map.Entry<String, MarkerHtml.View> e : desired.entrySet()) put(s, name, e.getKey(), e.getValue());
            }
        }
    }

    private void put(MarkerSet s, String worldName, String id, MarkerHtml.View view) {
        final SignRecord sign = view.sign();
        final String label = plugin.markerHtml().label(view);
        final String popup = plugin.markerHtml().html(view, POPUP_SIZE);
        final double x = sign.x() + 0.5, y = sign.y() + 0.5, z = sign.z() + 0.5;
        Marker m = s.findMarker(id);
        if (m == null) {
            m = s.createMarker(id, label, false, worldName, x, y, z, icon, false);
            if (m == null) return;
        } else {
            m.setLocation(worldName, x, y, z);
            m.setLabel(label, false);
        }
        m.setDescription(popup);
    }

    @Override
    public void signUpdated(String worldName, SignRecord sign, MarkerHtml.View view) {
        final MarkerSet s = set;
        if (s == null || !worldNames.contains(worldName)) return;
        synchronized (lock) {
            final String id = markerId(worldName, sign.x(), sign.y(), sign.z());
            if (view == null) {
                final Marker m = s.findMarker(id);
                if (m != null) m.deleteMarker();
            } else if (s.findMarker(id) != null || s.getMarkers().size() < plugin.settings().maxMarkers * Math.max(1, worldNames.size())) {
                put(s, worldName, id, view);
            }
        }
    }

    @Override
    public void signRemoved(String worldName, int x, int y, int z) {
        final MarkerSet s = set;
        if (s == null || !worldNames.contains(worldName)) return;
        synchronized (lock) {
            final Marker m = s.findMarker(markerId(worldName, x, y, z));
            if (m != null) m.deleteMarker();
        }
    }

    private static String idPrefix(String worldName) {
        return "ms." + worldName.replaceAll("[^A-Za-z0-9_-]", "_") + ".";
    }

    private static String markerId(String worldName, int x, int y, int z) {
        return idPrefix(worldName) + x + "_" + y + "_" + z;
    }
}
