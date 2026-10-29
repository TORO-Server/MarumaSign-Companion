package net.torosaba.marumasign.companion

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap

/**
 * 1 つのワールドの、MarumaSign の形式の看板の一覧。region ごとに持ち、region ファイルの更新日時といっしょに JSON に保存する。
 */
class SignStore {

    internal class Region {
        @Volatile
        var mtime: Long = 0

        @Volatile
        var signs: Map<Long, SignRecord> = mapOf()
    }

    // ゲーム中の変更。region ファイルにまだ書かれていないかもしれないので、走査の結果に重ねる
    private data class Live(val x: Int, val z: Int, val record: SignRecord?, val time: Long)

    private val regions = ConcurrentHashMap<Long, Region>()
    private val live = ConcurrentHashMap<Long, Live>()

    @Volatile
    private var dirty = false

    /** region ファイルを走査した結果を入れる (mtime はファイルを読む前に取った更新日時) */
    fun applyScan(rx: Int, rz: Int, mtime: Long, found: List<SignRecord>) {
        val map: MutableMap<Long, SignRecord> = ConcurrentHashMap()
        for (s in found) map[s.key()] = s
        val rk = regionKey(rx, rz)
        // ファイルの更新より新しいゲーム中の変更は、ファイルに入っていないかもしれないので重ねる
        for ((key, l) in live.entries) {
            if (regionKey(l.x shr 9, l.z shr 9) != rk) continue
            if (l.time > mtime) {
                if (l.record != null) map[key] = l.record
                else map.remove(key)
            } else {
                live.remove(key, l)
            }
        }
        val region = regions.computeIfAbsent(rk) { Region() }
        region.signs = map
        region.mtime = mtime
        dirty = true
    }

    /** 走査の結果、ファイルがなくなった region を消す */
    fun retainRegions(existing: Set<Long>) {
        if (regions.keys.retainAll(existing)) dirty = true
    }

    fun mtimeOf(rx: Int, rz: Int): Long {
        val r = regions[regionKey(rx, rz)]
        return r?.mtime ?: Long.MIN_VALUE
    }

    fun liveSet(record: SignRecord) {
        live[record.key()] = Live(record.x, record.z, record, System.currentTimeMillis())
        val region = regions.computeIfAbsent(regionKey(record.regionX(), record.regionZ())) {
            val r = Region()
            r.mtime = Long.MIN_VALUE
            r
        }
        val copy: MutableMap<Long, SignRecord> = ConcurrentHashMap(region.signs)
        copy[record.key()] = record
        region.signs = copy
        dirty = true
    }

    fun liveRemove(x: Int, y: Int, z: Int): Boolean {
        val key = SignRecord.pack(x, y, z)
        val region = regions[regionKey(x shr 9, z shr 9)]
        if (region == null || !region.signs.containsKey(key)) return false
        live[key] = Live(x, z, null, System.currentTimeMillis())
        val copy: MutableMap<Long, SignRecord> = ConcurrentHashMap(region.signs)
        copy.remove(key)
        region.signs = copy
        dirty = true
        return true
    }

    fun all(): List<SignRecord> {
        val out = ArrayList<SignRecord>()
        for (r in regions.values) out.addAll(r.signs.values)
        out.sortWith(Comparator.comparingInt<SignRecord> { it.x }.thenComparingInt { it.z }.thenComparingInt { it.y })
        return out
    }

    fun count(): Int {
        var n = 0
        for (r in regions.values) n += r.signs.size
        return n
    }

    fun isDirty(): Boolean = dirty

    // ---- 保存と読み込み ----

    @Synchronized
    fun save(file: Path) {
        dirty = false
        val root = JsonObject()
        root.addProperty("version", CACHE_VERSION)
        val regionsJson = JsonObject()
        for ((key, r) in regions.entries) {
            if (r.mtime == Long.MIN_VALUE) continue // まだ走査していない region
            val o = JsonObject()
            o.addProperty("mtime", r.mtime)
            val signs = JsonArray()
            for (s in r.signs.values) {
                val a = JsonArray()
                a.add(s.x)
                a.add(s.y)
                a.add(s.z)
                a.add(s.url)
                a.add(s.orient)
                for (v in s.values) a.add(v)
                signs.add(a)
            }
            o.add("signs", signs)
            regionsJson.add(regionKeyX(key).toString() + "," + regionKeyZ(key), o)
        }
        root.add("regions", regionsJson)
        Files.createDirectories(file.parent)
        val tmp = file.resolveSibling(file.fileName.toString() + ".tmp")
        Files.newBufferedWriter(tmp, StandardCharsets.UTF_8).use { w ->
            Gson().toJson(root as JsonElement, w)
        }
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING)
    }

    /** 保存したものを読む。ファイルがない、または読めなければ何も入れない */
    fun load(file: Path) {
        if (!Files.isRegularFile(file)) return
        try {
            Files.newBufferedReader(file, StandardCharsets.UTF_8).use { r ->
                val root = JsonParser.parseReader(r).asJsonObject
                if (root.get("version").asInt != CACHE_VERSION) return
                for ((name, value) in root.getAsJsonObject("regions").entrySet()) {
                    val p = COMMA.split(name)
                    val rx = Integer.parseInt(p[0])
                    val rz = Integer.parseInt(p[1])
                    val o = value.asJsonObject
                    val list = ArrayList<SignRecord>()
                    for (s in o.getAsJsonArray("signs")) {
                        val a = s.asJsonArray
                        val values = FloatArray(SignRecord.VALUE_COUNT)
                        for (i in values.indices) values[i] = a.get(5 + i).asFloat
                        list.add(SignRecord(a.get(0).asInt, a.get(1).asInt, a.get(2).asInt,
                                a.get(3).asString, values, a.get(4).asInt))
                    }
                    val map: MutableMap<Long, SignRecord> = ConcurrentHashMap()
                    for (s in list) map[s.key()] = s
                    val region = Region()
                    region.mtime = o.get("mtime").asLong
                    region.signs = map
                    regions[regionKey(rx, rz)] = region
                }
            }
        } catch (e: RuntimeException) {
            regions.clear()
            throw IOException("Broken cache: $e", e)
        }
        dirty = false
    }

    companion object {
        // 2: 看板の数値 (位置・大きさ・回転) と向きを持つようになった。古いキャッシュは読まずに、もう一度走査する
        private const val CACHE_VERSION = 2

        private val COMMA = ",".toPattern()

        fun regionKey(rx: Int, rz: Int): Long = (rx.toLong() shl 32) or (rz.toLong() and 0xFFFFFFFFL)

        fun regionKeyX(key: Long): Int = (key shr 32).toInt()

        fun regionKeyZ(key: Long): Int = key.toInt()
    }
}
