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
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Dynmap の MarkerAPI で出す。Dynmap の地図の目的は、リンク切れの看板を見つけて直すこと。
 * <p>
 * リンクの検査が有効なら、3 つのレイヤー (どれも初期は非表示): 「リンク切れ」「要確認」「正常」(show-ok のときだけ作る)。
 * 地図に常時出すのはアイコンだけで、ホバーのラベルは文字だけ (markup なし)。クリックのポップアップは、状態と理由、
 * URL (文字として表示。リンクにはしない)、リンク切れの期間、看板の座標、同じ URL の他の看板を出す。
 * 検査が無効なら、従来どおり 1 つのレイヤーに全部出す。
 */
final class DynmapBackend implements MapBackend {

    private static final int POPUP_SIZE = 320;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

    private enum Layer {
        BROKEN("marumasign_broken", "MarumaSign リンク切れ", new String[]{"warning", "redflag", "sign"}, 12),
        UNKNOWN("marumasign_unknown", "MarumaSign 要確認", new String[]{"yellowflag", "caution", "sign"}, 11),
        OK("marumasign_ok", "MarumaSign 正常", new String[]{"sign"}, 10),
        LEGACY(SignMapPlugin.MARKER_SET_ID, null, new String[]{"sign"}, 10);

        final String id;
        final String label;
        final String[] icons;
        final int priority;

        Layer(String id, String label, String[] icons, int priority) {
            this.id = id;
            this.label = label;
            this.icons = icons;
            this.priority = priority;
        }
    }

    /** 1 つのマーカーの、あるべき姿 */
    private record Desired(Layer layer, String world, double x, double y, double z, String label, String popup) {
        String signature() {
            return layer.name() + "|" + label + "|" + popup;
        }
    }

    record SignRef(String world, int x, int y, int z) {
    }

    private final SignMapPlugin plugin;
    private final Object lock = new Object();
    private final Map<Layer, MarkerSet> sets = new EnumMap<>(Layer.class);
    private final Map<Layer, MarkerIcon> icons = new EnumMap<>(Layer.class);
    private final Map<String, String> signatures = new HashMap<>();
    private final Set<String> worldNames = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private volatile boolean active;
    private final DynmapCommonAPIListener listener = new DynmapCommonAPIListener() {
        @Override
        public void apiEnabled(DynmapCommonAPI api) {
            enable(api);
        }

        @Override
        public void apiDisabled(DynmapCommonAPI api) {
            synchronized (lock) {
                active = false;
                sets.clear();
                signatures.clear();
            }
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
        synchronized (lock) {
            active = false;
            for (MarkerSet s : sets.values()) {
                try {
                    s.deleteMarkerSet();
                } catch (RuntimeException e) {
                    // Dynmap が止まっている途中
                }
            }
            sets.clear();
            signatures.clear();
        }
        worldNames.clear();
    }

    private boolean checking() {
        return plugin.linkChecker() != null;
    }

    private void enable(DynmapCommonAPI api) {
        final MarkerAPI markers = api.getMarkerAPI();
        if (markers == null) {
            plugin.getLogger().warning("Dynmap marker API is not available; Dynmap markers disabled");
            return;
        }
        synchronized (lock) {
            sets.clear();
            signatures.clear();
            final List<Layer> layers = new ArrayList<>();
            if (checking()) {
                layers.add(Layer.BROKEN);
                layers.add(Layer.UNKNOWN);
                if (plugin.settings().dynmapShowOk) layers.add(Layer.OK);
            } else {
                layers.add(Layer.LEGACY);
            }
            for (Layer layer : layers) {
                MarkerSet s = markers.getMarkerSet(layer.id);
                if (s != null) s.deleteMarkerSet();
                final String label = layer == Layer.LEGACY ? plugin.settings().markerSetLabel : layer.label;
                // persistent=false: Dynmap の markers.yml には書かず、起動のたびに作り直す
                s = markers.createMarkerSet(layer.id, label, null, false);
                if (s == null) {
                    plugin.getLogger().warning("Could not create the Dynmap marker set " + layer.id);
                    continue;
                }
                s.setHideByDefault(plugin.settings().dynmapDefaultHidden);
                // ラベルは常時表示せず (数千件の枠で地図が埋まる)、アイコンにマウスを乗せたときだけ出す。中身はクリックのポップアップ
                s.setLabelShow(false);
                s.setLayerPriority(layer.priority);
                MarkerIcon icon = null;
                for (String name : layer.icons) {
                    icon = markers.getMarkerIcon(name);
                    if (icon != null) break;
                }
                if (icon == null) icon = markers.getMarkerIcon(MarkerIcon.DEFAULT);
                icons.put(layer, icon);
                s.setDefaultMarkerIcon(icon);
                sets.put(layer, s);
            }
            active = !sets.isEmpty();
        }
        worldNames.clear();
        final Set<String> targets = targetWorlds();
        plugin.getLogger().info("Dynmap " + api.getDynmapCoreVersion() + " enabled; worlds: " + targets
                + (checking() ? "; layers: " + sets.keySet() : "; link check is off, single layer"));
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

    // ---- マーカーの更新 (あるべき姿との差だけ反映する) ----

    @Override
    public void refresh() {
        if (!active) return;
        synchronized (lock) {
            if (!active) return;
            final LinkChecker checker = plugin.linkChecker();
            final int max = plugin.settings().maxMarkers;
            final long now = System.currentTimeMillis();

            // 同じ URL の看板 (ワールドをまたいで)
            final Map<String, List<SignRef>> byUrl = new HashMap<>();
            final Map<String, List<SignRecord>> perWorld = new HashMap<>();
            for (String name : worldNames) {
                final SignMapPlugin.WorldState w = plugin.worldState(name);
                if (w == null) continue;
                final List<SignRecord> signs = w.store.all();
                perWorld.put(name, signs);
                if (checker != null) {
                    for (SignRecord s : signs) byUrl.computeIfAbsent(s.url(), k -> new ArrayList<>()).add(new SignRef(name, s.x(), s.y(), s.z()));
                }
            }

            final Map<String, Desired> desired = new HashMap<>();
            final Map<Layer, Integer> counts = new EnumMap<>(Layer.class);
            for (Map.Entry<String, List<SignRecord>> e : perWorld.entrySet()) {
                final String world = e.getKey();
                for (SignRecord sign : e.getValue()) {
                    final Desired d = checker == null ? legacy(world, sign) : checked(checker, world, sign, byUrl, now);
                    if (d == null || !sets.containsKey(d.layer())) continue;
                    if (counts.merge(d.layer(), 1, Integer::sum) > max) continue;
                    desired.put(markerId(world, sign.x(), sign.y(), sign.z()), d);
                }
            }
            apply(desired);
        }
    }

    private Desired legacy(String world, SignRecord sign) {
        final MarkerHtml.View view = plugin.classify(sign);
        if (view == null) return null;
        return new Desired(Layer.LEGACY, world, sign.x() + 0.5, sign.y() + 0.5, sign.z() + 0.5,
                plugin.markerHtml().label(view), plugin.markerHtml().html(view, POPUP_SIZE));
    }

    private Desired checked(LinkChecker checker, String world, SignRecord sign, Map<String, List<SignRef>> byUrl, long now) {
        final LinkState st = checker.state(sign.url());
        if (st == null) return null;
        final double x = sign.x() + 0.5, y = sign.y() + 0.5, z = sign.z() + 0.5;
        final Layer which = layerOf(st, plugin.settings().dynmapShowOk);
        if (which == Layer.BROKEN || which == Layer.UNKNOWN) {
            // 公開の地図なので、DNS の確認 (HostGuard) は通さない: 名前が引けない URL こそ、リンク切れとして見つけたい。
            // 地図は URL の先へつながないので、安全に出せる (URL は文字だけで、リンクにしない)
            final MarkerHtml.View view = plugin.markerHtml().classify(sign);
            if (view == null) return null;
            final boolean broken = st.status() == LinkStatus.BROKEN;
            final Layer layer = broken ? Layer.BROKEN : Layer.UNKNOWN;
            return new Desired(layer, world, x, y, z, problemLabel(st, view, broken), problemPopup(st, view, world, sign, byUrl.get(sign.url()), broken, now));
        }
        if (which == Layer.OK) {
            final MarkerHtml.View view = plugin.classify(sign);
            if (view == null) return null;
            return new Desired(Layer.OK, world, x, y, z, plugin.markerHtml().label(view), plugin.markerHtml().html(view, POPUP_SIZE));
        }
        return null;
    }

    /** 状態からレイヤーを決める。出さないものは null (未検査、一時的な 1 回の失敗、showOk でない正常) */
    private static Layer layerOf(LinkState st, boolean showOk) {
        if (st.status() == LinkStatus.BROKEN) return Layer.BROKEN;
        if (st.needsAttention()) return Layer.UNKNOWN;
        if (st.status() == LinkStatus.OK && showOk) return Layer.OK;
        return null;
    }

    /** テスト用: レイヤーの名前 (BROKEN / UNKNOWN / OK)。出さないなら null */
    static String layerName(LinkState st, boolean showOk) {
        final Layer l = layerOf(st, showOk);
        return l == null ? null : l.name();
    }

    /** ホバーで出す短い文字 (HTML ではない)。例: リンク切れ(404 見つからない) 画像 i.imgur.com/xxxx… */
    static String problemLabel(LinkState st, MarkerHtml.View view, boolean broken) {
        return (broken ? "リンク切れ" : "要確認") + "(" + st.reason().publicLabel + ") " + view.kind().label + " "
                + SafeText.displayUrl(view.url(), 40);
    }

    /** クリックのポップアップ (HTML)。外から来た文字は、すべて無害にしてからエスケープする。URL はリンクにしない */
    static String problemPopup(LinkState st, MarkerHtml.View view, String world, SignRecord sign, List<SignRef> same,
                               boolean broken, long now) {
        final StringBuilder h = new StringBuilder(600);
        h.append("<div style=\"font:13px/1.5 sans-serif;max-width:360px\">");
        h.append("<div><span style=\"background:").append(broken ? "#c0392b" : "#b7950b")
                .append(";color:#fff;border-radius:3px;padding:1px 6px;font-weight:bold\">")
                .append(broken ? "リンク切れ" : "要確認").append("</span> ")
                .append(esc(st.reason().publicLabel)).append("</div>");
        h.append("<div style=\"word-break:break-all;margin:6px 0\">").append(esc(view.kind().icon)).append(' ')
                .append(esc(view.kind().label)).append(": <code>").append(esc(SafeText.displayUrl(view.url(), 90))).append("</code></div>");
        if (st.firstFailedAt() > 0) {
            final long days = Math.max(0, (now - st.firstFailedAt()) / 86_400_000L);
            h.append("<div>").append(broken ? "リンク切れになってから " : "失敗が始まってから ").append(days).append(" 日</div>");
        }
        h.append("<div style=\"opacity:.8\">最終確認: ").append(time(st.lastCheckedAt())).append(" / 最後に正常: ")
                .append(st.lastOkAt() > 0 ? time(st.lastOkAt()) : "なし").append("</div>");
        h.append("<div style=\"margin-top:6px\">看板: ").append(esc(coords(world, sign.x(), sign.y(), sign.z())))
                .append("<br><code>").append(esc(tpCommand(sign.x(), sign.y(), sign.z()))).append("</code></div>");
        if (same != null && same.size() > 1) {
            h.append("<div style=\"margin-top:6px\">同じ URL の看板が他に ").append(same.size() - 1).append(" 件");
            int shown = 0;
            for (SignRef r : same) {
                if (r.world().equals(world) && r.x() == sign.x() && r.y() == sign.y() && r.z() == sign.z()) continue;
                if (shown++ >= 5) break;
                h.append("<br>").append(esc(coords(r.world(), r.x(), r.y(), r.z())));
            }
            h.append("</div>");
        }
        h.append("</div>");
        return h.toString();
    }

    static String coords(String world, int x, int y, int z) {
        return SafeText.clean(world, 40) + " " + x + " " + y + " " + z;
    }

    static String tpCommand(int x, int y, int z) {
        return "/tp " + x + " " + y + " " + z;
    }

    private static String time(long millis) {
        return millis <= 0 ? "未検査" : TIME.format(Instant.ofEpochMilli(millis));
    }

    private static String esc(String s) {
        return MarkerHtml.escape(s);
    }

    private void apply(Map<String, Desired> desired) {
        // あるべきでないマーカーを消す (別のレイヤーにあるべきものも、いったん消す)
        for (Map.Entry<Layer, MarkerSet> e : sets.entrySet()) {
            for (Marker m : new ArrayList<>(e.getValue().getMarkers())) {
                final String id = m.getMarkerID();
                if (!id.startsWith("ms.")) continue;
                final Desired d = desired.get(id);
                if (d == null || d.layer() != e.getKey()) {
                    m.deleteMarker();
                    signatures.remove(id);
                }
            }
        }
        for (Map.Entry<String, Desired> e : desired.entrySet()) {
            final Desired d = e.getValue();
            final MarkerSet s = sets.get(d.layer());
            if (s == null) continue;
            final String sig = d.signature();
            Marker m = s.findMarker(e.getKey());
            if (m != null && sig.equals(signatures.get(e.getKey()))) continue;
            if (m == null) {
                m = s.createMarker(e.getKey(), d.label(), false, d.world(), d.x(), d.y(), d.z(), icons.get(d.layer()), false);
                if (m == null) continue;
            } else {
                m.setLocation(d.world(), d.x(), d.y(), d.z());
                m.setLabel(d.label(), false);
            }
            m.setDescription(d.popup());
            signatures.put(e.getKey(), sig);
        }
    }

    @Override
    public void signUpdated(String worldName, SignRecord sign, MarkerHtml.View view) {
        if (worldNames.contains(worldName)) refresh();
    }

    @Override
    public void signRemoved(String worldName, int x, int y, int z) {
        if (worldNames.contains(worldName)) refresh();
    }

    @Override
    public void linkStateChanged() {
        refresh();
    }

    private static String idPrefix(String worldName) {
        return "ms." + worldName.replaceAll("[^A-Za-z0-9_-]", "_") + ".";
    }

    private static String markerId(String worldName, int x, int y, int z) {
        return idPrefix(worldName) + x + "_" + y + "_" + z;
    }
}
