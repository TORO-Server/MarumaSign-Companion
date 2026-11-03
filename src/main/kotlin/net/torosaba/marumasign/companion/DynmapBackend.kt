package net.torosaba.marumasign.companion

import org.bukkit.Bukkit
import org.bukkit.configuration.file.YamlConfiguration
import org.dynmap.DynmapCommonAPI
import org.dynmap.DynmapCommonAPIListener
import org.dynmap.markers.Marker
import org.dynmap.markers.MarkerIcon
import org.dynmap.markers.MarkerSet
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.EnumMap
import java.util.concurrent.ConcurrentHashMap

/**
 * Dynmap の MarkerAPI で出す。Dynmap の地図の目的は、リンク切れの看板を見つけて直すこと。
 *
 * リンクの検査が有効なら、3 つのレイヤー (どれも初期は非表示): 「リンク切れ」「要確認」「正常」(show-ok のときだけ作る)。
 * 地図に常時出すのはアイコンだけで、ホバーのラベルは文字だけ (markup なし)。クリックのポップアップは、状態と理由、
 * URL (文字として表示。リンクにはしない)、リンク切れの期間、看板の座標、同じ URL の他の看板を出す。
 * 検査が無効なら、従来どおり 1 つのレイヤーに全部出す。
 */
internal class DynmapBackend(private val plugin: MarumaSignCompanion) : MapBackend {

    private enum class Layer(val id: String, val label: String?, val icons: Array<String>, val priority: Int) {
        BROKEN("marumasign_broken", "MarumaSign リンク切れ", arrayOf("warning", "redflag", "sign"), 12),
        UNKNOWN("marumasign_unknown", "MarumaSign 要確認", arrayOf("yellowflag", "caution", "sign"), 11),
        OK("marumasign_ok", "MarumaSign 正常", arrayOf("sign"), 10),
        LEGACY(MarumaSignCompanion.MARKER_SET_ID, null, arrayOf("sign"), 10),
    }

    /** 1 つのマーカーの、あるべき姿 */
    private data class Desired(val layer: Layer, val world: String, val x: Double, val y: Double, val z: Double, val label: String, val popup: String) {
        fun signature(): String = layer.name + "|" + label + "|" + popup
    }

    data class SignRef(val world: String, val x: Int, val y: Int, val z: Int)

    private val lock = Any()
    private val sets: MutableMap<Layer, MarkerSet> = EnumMap(Layer::class.java)
    private val icons: MutableMap<Layer, MarkerIcon?> = EnumMap(Layer::class.java)
    private val signatures: MutableMap<String, String> = HashMap()
    private val worldNames: MutableSet<String> = ConcurrentHashMap.newKeySet()

    @Volatile
    private var active = false
    private val listener: DynmapCommonAPIListener = object : DynmapCommonAPIListener() {
        override fun apiEnabled(api: DynmapCommonAPI) {
            enable(api)
        }

        override fun apiDisabled(api: DynmapCommonAPI) {
            synchronized(lock) {
                active = false
                sets.clear()
                signatures.clear()
            }
            worldNames.clear()
        }
    }

    override fun name(): String = "Dynmap"

    override fun start() {
        DynmapCommonAPIListener.register(listener)
    }

    override fun stop() {
        DynmapCommonAPIListener.unregister(listener)
        synchronized(lock) {
            active = false
            for (s in sets.values) {
                try {
                    s.deleteMarkerSet()
                } catch (e: RuntimeException) {
                    // Dynmap が止まっている途中
                }
            }
            sets.clear()
            signatures.clear()
        }
        worldNames.clear()
    }

    private fun checking(): Boolean = plugin.linkChecker() != null

    private fun enable(api: DynmapCommonAPI) {
        val markers = api.markerAPI
        if (markers == null) {
            plugin.logger.warning("Dynmap marker API is not available; Dynmap markers disabled")
            return
        }
        synchronized(lock) {
            sets.clear()
            signatures.clear()
            val layers = ArrayList<Layer>()
            if (checking()) {
                layers.add(Layer.BROKEN)
                layers.add(Layer.UNKNOWN)
                if (plugin.settings().dynmapShowOk) layers.add(Layer.OK)
            } else {
                layers.add(Layer.LEGACY)
            }
            for (layer in layers) {
                var s: MarkerSet? = markers.getMarkerSet(layer.id)
                s?.deleteMarkerSet()
                val label = if (layer == Layer.LEGACY) plugin.settings().markerSetLabel else layer.label
                // persistent=false: Dynmap の markers.yml には書かず、起動のたびに作り直す
                s = markers.createMarkerSet(layer.id, label, null, false)
                if (s == null) {
                    plugin.logger.warning("Could not create the Dynmap marker set " + layer.id)
                    continue
                }
                s.setHideByDefault(plugin.settings().dynmapDefaultHidden)
                // ラベルは常時表示せず (数千件の枠で地図が埋まる)、アイコンにマウスを乗せたときだけ出す。中身はクリックのポップアップ
                s.setLabelShow(false)
                s.setLayerPriority(layer.priority)
                var icon: MarkerIcon? = null
                for (name in layer.icons) {
                    icon = markers.getMarkerIcon(name)
                    if (icon != null) break
                }
                if (icon == null) icon = markers.getMarkerIcon(MarkerIcon.DEFAULT)
                icons[layer] = icon
                s.setDefaultMarkerIcon(icon)
                sets[layer] = s
            }
            active = sets.isNotEmpty()
        }
        worldNames.clear()
        val targets = targetWorlds()
        plugin.logger.info("Dynmap " + api.dynmapCoreVersion + " enabled; worlds: " + targets
                + (if (checking()) "; layers: " + sets.keys else "; link check is off, single layer"))
        for (name in targets) {
            val world = Bukkit.getWorld(name) ?: continue
            plugin.registerWorld(world, null)
            worldNames.add(name)
        }
        refresh()
        plugin.requestScan()
    }

    /** Dynmap が地図を作るワールド (worlds.txt で有効で、地図があり、いま読み込まれているもの)。config の dynmap.worlds があればそれ */
    private fun targetWorlds(): Set<String> {
        val configured = plugin.config.getStringList("dynmap.worlds")
        val result = LinkedHashSet<String>()
        if (configured.isNotEmpty()) {
            for (n in configured) if (Bukkit.getWorld(n) != null) result.add(n)
            return result
        }
        val loaded = HashSet<String>()
        for (w in Bukkit.getWorlds()) loaded.add(w.name)
        val dynmap = Bukkit.getPluginManager().getPlugin("dynmap")
        var read = false
        if (dynmap != null) {
            val file = File(dynmap.dataFolder, "worlds.txt")
            if (file.isFile) {
                try {
                    val yaml = YamlConfiguration.loadConfiguration(file)
                    for (entry in yaml.getMapList("worlds")) {
                        val name = entry["name"]
                        val enabled = entry["enabled"]
                        val maps = entry["maps"]
                        if (name == null || !loaded.contains(name.toString())) continue
                        if (enabled != null && !java.lang.Boolean.parseBoolean(enabled.toString())) continue
                        if (maps is List<*> && maps.isEmpty()) continue
                        result.add(name.toString())
                        read = true
                    }
                } catch (e: RuntimeException) {
                    plugin.logger.warning("Could not read Dynmap worlds.txt: $e")
                }
            }
        }
        if (!read) result.addAll(loaded)
        return result
    }

    // ---- マーカーの更新 (あるべき姿との差だけ反映する) ----

    override fun refresh() {
        if (!active) return
        synchronized(lock) {
            if (!active) return
            val checker = plugin.linkChecker()
            val max = plugin.settings().maxMarkers
            val now = System.currentTimeMillis()

            // 同じ URL の看板 (ワールドをまたいで)
            val byUrl = HashMap<String, MutableList<SignRef>>()
            val perWorld = HashMap<String, List<SignRecord>>()
            for (name in worldNames) {
                val w = plugin.worldState(name) ?: continue
                val signs = w.store.all()
                perWorld[name] = signs
                if (checker != null) {
                    for (s in signs) byUrl.computeIfAbsent(s.url) { ArrayList() }.add(SignRef(name, s.x, s.y, s.z))
                }
            }

            val desired = HashMap<String, Desired>()
            val counts: MutableMap<Layer, Int> = EnumMap(Layer::class.java)
            for ((world, signs) in perWorld) {
                for (sign in signs) {
                    val d = (if (checker == null) legacy(world, sign) else checked(checker, world, sign, byUrl, now)) ?: continue
                    if (!sets.containsKey(d.layer)) continue
                    if (counts.merge(d.layer, 1) { a, b -> a + b }!! > max) continue
                    desired[markerId(world, sign.x, sign.y, sign.z)] = d
                }
            }
            apply(desired)
        }
    }

    private fun legacy(world: String, sign: SignRecord): Desired? {
        val view = plugin.classify(sign) ?: return null
        return Desired(Layer.LEGACY, world, sign.x + 0.5, sign.y + 0.5, sign.z + 0.5,
            plugin.markerHtml().label(view), plugin.markerHtml().html(view, POPUP_SIZE))
    }

    private fun checked(checker: LinkChecker, world: String, sign: SignRecord, byUrl: Map<String, List<SignRef>>, now: Long): Desired? {
        if (plugin.isSelfHostedUrl(sign.url)) {
            // ホストされた看板は自サーバーが管理・保持している正常な画像。リンク切れや要確認には出さない
            if (plugin.settings().dynmapShowOk) {
                val view = plugin.classify(sign) ?: return null
                return Desired(Layer.OK, world, sign.x + 0.5, sign.y + 0.5, sign.z + 0.5,
                    plugin.markerHtml().label(view), plugin.markerHtml().html(view, POPUP_SIZE))
            }
            return null
        }
        val st = checker.state(sign.url) ?: return null
        val x = sign.x + 0.5
        val y = sign.y + 0.5
        val z = sign.z + 0.5
        val which = layerOf(st, plugin.settings().dynmapShowOk)
        if (which == Layer.BROKEN || which == Layer.UNKNOWN) {
            // 公開の地図なので、DNS の確認 (HostGuard) は通さない: 名前が引けない URL こそ、リンク切れとして見つけたい。
            // 地図は URL の先へつながないので、安全に出せる (URL は文字だけで、リンクにしない)
            val view = plugin.markerHtml().classify(sign) ?: return null
            val broken = st.status == LinkStatus.BROKEN
            val layer = if (broken) Layer.BROKEN else Layer.UNKNOWN
            return Desired(layer, world, x, y, z, problemLabel(st, view, broken), problemPopup(st, view, world, sign, byUrl[sign.url], broken, now))
        }
        if (which == Layer.OK) {
            val view = plugin.classify(sign) ?: return null
            return Desired(Layer.OK, world, x, y, z, plugin.markerHtml().label(view), plugin.markerHtml().html(view, POPUP_SIZE))
        }
        return null
    }

    private fun apply(desired: Map<String, Desired>) {
        // あるべきでないマーカーを消す (別のレイヤーにあるべきものも、いったん消す)
        for ((layer, set) in sets) {
            for (m in ArrayList<Marker>(set.markers)) {
                val id = m.markerID
                if (!id.startsWith("ms.")) continue
                val d = desired[id]
                if (d == null || d.layer != layer) {
                    m.deleteMarker()
                    signatures.remove(id)
                }
            }
        }
        for ((key, d) in desired) {
            val s = sets[d.layer] ?: continue
            val sig = d.signature()
            var m: Marker? = s.findMarker(key)
            if (m != null && sig == signatures[key]) continue
            if (m == null) {
                m = s.createMarker(key, d.label, false, d.world, d.x, d.y, d.z, icons[d.layer], false)
                if (m == null) continue
            } else {
                m.setLocation(d.world, d.x, d.y, d.z)
                m.setLabel(d.label, false)
            }
            m.setDescription(d.popup)
            signatures[key] = sig
        }
    }

    override fun signUpdated(worldName: String, sign: SignRecord, view: MarkerHtml.View?) {
        if (worldNames.contains(worldName)) refresh()
    }

    override fun signRemoved(worldName: String, x: Int, y: Int, z: Int) {
        if (worldNames.contains(worldName)) refresh()
    }

    override fun linkStateChanged() {
        refresh()
    }

    companion object {
        private const val POPUP_SIZE = 320
        private val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault())

        /** 状態からレイヤーを決める。出さないものは null (未検査、一時的な 1 回の失敗、showOk でない正常) */
        private fun layerOf(st: LinkState, showOk: Boolean): Layer? {
            if (st.status == LinkStatus.BROKEN) return Layer.BROKEN
            if (st.needsAttention()) return Layer.UNKNOWN
            if (st.status == LinkStatus.OK && showOk) return Layer.OK
            return null
        }

        /** テスト用: レイヤーの名前 (BROKEN / UNKNOWN / OK)。出さないなら null */
        fun layerName(st: LinkState, showOk: Boolean): String? = layerOf(st, showOk)?.name

        /** ホバーで出す短い文字 (HTML ではない)。例: リンク切れ(404 見つからない) 画像 i.imgur.com/xxxx… */
        fun problemLabel(st: LinkState, view: MarkerHtml.View, broken: Boolean): String =
            (if (broken) "リンク切れ" else "要確認") + "(" + st.reason.publicLabel + ") " + view.kind.label + " " +
                SafeText.displayUrl(view.url, 40)

        /** クリックのポップアップ (HTML)。外から来た文字は、すべて無害にしてからエスケープする。URL はリンクにしない */
        fun problemPopup(st: LinkState, view: MarkerHtml.View, world: String, sign: SignRecord, same: List<SignRef>?,
                         broken: Boolean, now: Long): String {
            val h = StringBuilder(600)
            h.append("<div style=\"font:13px/1.5 sans-serif;max-width:360px\">")
            h.append("<div><span style=\"background:").append(if (broken) "#c0392b" else "#b7950b")
                .append(";color:#fff;border-radius:3px;padding:1px 6px;font-weight:bold\">")
                .append(if (broken) "リンク切れ" else "要確認").append("</span> ")
                .append(esc(st.reason.publicLabel)).append("</div>")
            h.append("<div style=\"word-break:break-all;margin:6px 0\">").append(esc(view.kind.icon)).append(' ')
                .append(esc(view.kind.label)).append(": <code>").append(esc(SafeText.displayUrl(view.url, 90))).append("</code></div>")
            if (st.firstFailedAt > 0) {
                val days = Math.max(0L, (now - st.firstFailedAt) / 86_400_000L)
                h.append("<div>").append(if (broken) "リンク切れになってから " else "失敗が始まってから ").append(days).append(" 日</div>")
            }
            h.append("<div style=\"opacity:.8\">最終確認: ").append(time(st.lastCheckedAt)).append(" / 最後に正常: ")
                .append(if (st.lastOkAt > 0) time(st.lastOkAt) else "なし").append("</div>")
            h.append("<div style=\"margin-top:6px\">看板: ").append(esc(AdminCommands.coords(world, sign.x, sign.y, sign.z)))
                .append("<br><code>").append(esc(AdminCommands.tpCommand(sign.x, sign.y, sign.z))).append("</code></div>")
            if (same != null && same.size > 1) {
                h.append("<div style=\"margin-top:6px\">同じ URL の看板が他に ").append(same.size - 1).append(" 件")
                var shown = 0
                for (r in same) {
                    if (r.world == world && r.x == sign.x && r.y == sign.y && r.z == sign.z) continue
                    if (shown++ >= 5) break
                    h.append("<br>").append(esc(AdminCommands.coords(r.world, r.x, r.y, r.z)))
                }
                h.append("</div>")
            }
            h.append("</div>")
            return h.toString()
        }

        private fun time(millis: Long): String = if (millis <= 0) "未検査" else TIME.format(Instant.ofEpochMilli(millis))

        private fun esc(s: String): String = MarkerHtml.escape(s)

        private fun idPrefix(worldName: String): String = "ms." + worldName.replace(Regex("[^A-Za-z0-9_-]"), "_") + "."

        private fun markerId(worldName: String, x: Int, y: Int, z: Int): String = idPrefix(worldName) + x + "_" + y + "_" + z
    }
}
