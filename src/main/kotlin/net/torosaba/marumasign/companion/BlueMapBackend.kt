package net.torosaba.marumasign.companion

import com.flowpowered.math.vector.Vector2i
import com.flowpowered.math.vector.Vector3d
import de.bluecolored.bluemap.api.BlueMapAPI
import de.bluecolored.bluemap.api.BlueMapMap
import de.bluecolored.bluemap.api.markers.HtmlMarker
import de.bluecolored.bluemap.api.markers.MarkerSet
import org.bukkit.Bukkit
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.HexFormat
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.function.Consumer

/**
 * BlueMap に出す。3D モード (既定) では、看板の画像を 3D の空間に置く Web 側のスクリプト (marumasign3d.js) と、
 * その看板データ (webroot/marumasign/&lt;地図の id&gt;.json) を用意する。markers モードでは HtmlMarker を使う。
 */
internal class BlueMapBackend(private val plugin: MarumaSignCompanion) : MapBackend {

    private val maps: MutableMap<String, List<BlueMapMap>> = ConcurrentHashMap()
    private val lock = Any()
    private val onEnable: Consumer<BlueMapAPI> = Consumer { enable(it) }
    private val onDisable: Consumer<BlueMapAPI> = Consumer { disable(it) }

    @Volatile
    private var webRoot: Path? = null

    @Volatile
    private var exporter: ScheduledExecutorService? = null
    private val exportPending = AtomicBoolean()

    override fun name(): String = "BlueMap"

    override fun start() {
        exporter = Executors.newSingleThreadScheduledExecutor { r ->
            val t = Thread(r, "MarumaSignCompanion-export")
            t.isDaemon = true
            t.priority = Thread.MIN_PRIORITY
            t
        }
        BlueMapAPI.onEnable(onEnable)
        BlueMapAPI.onDisable(onDisable)
    }

    override fun stop() {
        BlueMapAPI.unregisterListener(onEnable)
        BlueMapAPI.unregisterListener(onDisable)
        val e = exporter
        exporter = null
        e?.shutdownNow()
        maps.clear()
    }

    private fun enable(api: BlueMapAPI) {
        plugin.logger.info("BlueMap " + api.blueMapVersion + " enabled; preparing MarumaSign markers (mode: "
                + plugin.settings().bluemapMode + ")")
        maps.clear()
        webRoot = null
        if (plugin.settings().bluemap3d()) installScript(api)
        for (world in Bukkit.getWorlds()) {
            val bw = api.getWorld(world).orElse(null)
            if (bw == null || bw.maps.isEmpty()) continue
            var save: Path? = null
            try {
                save = bw.saveFolder
            } catch (e: RuntimeException) {
                // BlueMap の版によっては取れない
            } catch (e: LinkageError) {
                // BlueMap の版によっては取れない
            }
            plugin.registerWorld(world, save)
            maps[world.name] = ArrayList(bw.maps)
        }
        refresh()
        plugin.requestScan()
    }

    private fun disable(api: BlueMapAPI) {
        maps.clear()
        webRoot = null
    }

    /** Web アプリに 3D 表示のスクリプトを置いて登録する */
    private fun installScript(api: BlueMapAPI) {
        try {
            val root = api.webApp.webRoot
            val script: ByteArray = (BlueMapBackend::class.java.getResourceAsStream(SCRIPT_RESOURCE)
                ?: throw IOException("Missing resource $SCRIPT_RESOURCE")).use { it.readAllBytes() }
            val target = root.resolve(SCRIPT_PATH)
            Files.createDirectories(target.parent)
            val tmp = target.resolveSibling(target.fileName.toString() + ".tmp")
            Files.write(tmp, script)
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING)
            val version = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(script)).substring(0, 12)
            api.webApp.registerScript("$SCRIPT_PATH?v=$version")
            webRoot = root
            plugin.logger.info("3D sign script installed in the BlueMap web root")
        } catch (e: Exception) {
            plugin.logger.warning("Could not install the 3D sign script: $e")
        } catch (e: LinkageError) {
            plugin.logger.warning("Could not install the 3D sign script: $e")
        }
    }

    override fun refresh() {
        synchronized(lock) {
            val st = plugin.settings()
            for ((key, value) in maps) {
                val w = plugin.worldState(key) ?: continue
                if (st.bluemapMarkers()) {
                    val set = MarkerSet.builder()
                        .label(st.markerSetLabel)
                        .toggleable(true)
                        .defaultHidden(st.defaultHidden)
                        .build()
                    for (view in plugin.views(w)) {
                        set.put(markerId(view.sign.x, view.sign.y, view.sign.z), buildMarker(view))
                    }
                    for (map in value) map.markerSets.put(MarumaSignCompanion.MARKER_SET_ID, set)
                } else {
                    for (map in value) map.markerSets.remove(MarumaSignCompanion.MARKER_SET_ID)
                }
            }
            if (st.bluemap3d()) exportNow()
        }
    }

    private fun exportNow() {
        val root = webRoot ?: return
        for ((key, value) in maps) {
            val w = plugin.worldState(key) ?: continue
            val views = plugin.views3d(w)
            for (map in value) {
                try {
                    val hosting = plugin.hosting()
                    WebExporter.write(root.resolve("marumasign").resolve(safeId(map.id) + ".json"), views, plugin.settings()) { v ->
                        // 短縮 URL は、看板に書かれた URL (v.sign.url) でホストしている。行き先の URL (v.url) でホストしていることもある
                        hosting?.hostedImageUrl(v.sign.url, v.url)
                    }
                } catch (ex: IOException) {
                    plugin.logger.warning("[$key] Could not write 3D sign data: $ex")
                }
            }
        }
    }

    override fun hostedChanged() {
        scheduleExport()
    }

    // 看板が変わったら、少し待ってからまとめて書き出す
    private fun scheduleExport() {
        val ex = exporter
        if (ex == null || webRoot == null || !plugin.settings().bluemap3d()) return
        if (!exportPending.compareAndSet(false, true)) return
        try {
            ex.schedule(Runnable {
                exportPending.set(false)
                synchronized(lock) {
                    exportNow()
                }
            }, 1500, TimeUnit.MILLISECONDS)
        } catch (e: RuntimeException) {
            exportPending.set(false)
        }
    }

    override fun signUpdated(worldName: String, sign: SignRecord, view: MarkerHtml.View?) {
        val list = maps[worldName] ?: return
        if (plugin.settings().bluemapMarkers()) {
            val id = markerId(sign.x, sign.y, sign.z)
            for (map in list) {
                val set = map.markerSets[MarumaSignCompanion.MARKER_SET_ID] ?: continue
                if (view == null) set.remove(id)
                else if (set.markers.size < plugin.settings().maxMarkers || set.get(id) != null) set.put(id, buildMarker(view))
            }
        }
        scheduleExport()
    }

    override fun signRemoved(worldName: String, x: Int, y: Int, z: Int) {
        val list = maps[worldName] ?: return
        if (plugin.settings().bluemapMarkers()) {
            val id = markerId(x, y, z)
            for (map in list) {
                val set = map.markerSets[MarumaSignCompanion.MARKER_SET_ID]
                set?.remove(id)
            }
        }
        scheduleExport()
    }

    private fun buildMarker(view: MarkerHtml.View): HtmlMarker {
        val s = view.sign
        val b = HtmlMarker.builder()
            .label(plugin.markerHtml().label(view))
            .position(Vector3d(s.x + 0.5, s.y + 0.5, s.z + 0.5))
            .anchor(Vector2i(0, 0))
            .html(plugin.markerHtml().html(view, plugin.settings().thumbnailSize))
        if (plugin.settings().maxDistance > 0) b.maxDistance(plugin.settings().maxDistance)
        return b.build()
    }

    companion object {
        const val SCRIPT_RESOURCE = "/web/marumasign3d.js"
        const val SCRIPT_PATH = "js/marumasign3d.js"

        private fun safeId(id: String): String = id.replace(Regex("[^A-Za-z0-9_.-]"), "_")

        private fun markerId(x: Int, y: Int, z: Int): String = "sign_" + x + "_" + y + "_" + z
    }
}
