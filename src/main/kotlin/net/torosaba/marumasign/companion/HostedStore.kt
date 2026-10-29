package net.torosaba.marumasign.companion

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap

/**
 * ホストしているファイルと、ホストの途中の状態 (候補・失敗・書き換え待ちの看板・取り下げた URL)。hosting.json に保存する。
 * 書き換えるのは [HostingManager] のスレッドだけ。Web サーバーなどほかのスレッドは読むだけ。
 */
class HostedStore {

    class HostedFile(
        /** 内容と拡張子の SHA-256 から作った 12 文字 (小文字の英字と 2-7) */
        val id: String,
        val ext: String,
        val size: Long,
        val hostedAt: Long,
        @Volatile var lastUsedAt: Long,
        /** この内容の元の URL */
        val sources: MutableSet<String> = ConcurrentHashMap.newKeySet(),
        /** 書き換えた看板 (world:x:y:z) → 書き換える前の URL。取り下げたときに戻すため */
        val origins: MutableMap<String, String> = ConcurrentHashMap(),
    ) {
        val fileName: String get() = "$id.$ext"
    }

    /** 書き換え待ちの看板。from の URL のままなら to に書き換える */
    data class Rewrite(val world: String, val x: Int, val y: Int, val z: Int, val from: String, val to: String) {
        val key: String get() = signKey(world, x, y, z)
    }

    data class Failure(val reason: HostingPolicy.Reject, val retryAt: Long)

    val files = ConcurrentHashMap<String, HostedFile>()
    /** 元の URL → ホストしているファイルの id */
    val bySource = ConcurrentHashMap<String, String>()
    /** ホストの候補: 元の URL → 正常と確かめた最初の時刻 */
    val candidates = ConcurrentHashMap<String, Long>()
    val failures = ConcurrentHashMap<String, Failure>()
    val rewrites = ConcurrentHashMap<String, Rewrite>()
    /** 管理者が取り下げた元の URL。もうホストしない */
    val denied: MutableSet<String> = ConcurrentHashMap.newKeySet()

    @Volatile
    var dirty = false

    fun totalBytes(): Long = files.values.sumOf { it.size }

    fun add(file: HostedFile) {
        files[file.id] = file
        for (s in file.sources) bySource[s] = file.id
        dirty = true
    }

    fun remove(id: String): HostedFile? {
        val f = files.remove(id) ?: return null
        bySource.entries.removeIf { it.value == id }
        rewrites.entries.removeIf { it.value.to.endsWith("/" + f.fileName) }
        dirty = true
        return f
    }

    // ---- 保存と読み込み ----

    @Synchronized
    @Throws(IOException::class)
    fun save(file: Path) {
        dirty = false
        val root = JsonObject()
        root.addProperty("version", VERSION)
        val fs = JsonArray()
        for (f in files.values) {
            val o = JsonObject()
            o.addProperty("id", f.id)
            o.addProperty("ext", f.ext)
            o.addProperty("size", f.size)
            o.addProperty("hostedAt", f.hostedAt)
            o.addProperty("lastUsedAt", f.lastUsedAt)
            o.add("sources", JsonArray().apply { f.sources.forEach { add(it) } })
            o.add("origins", JsonObject().apply { f.origins.forEach { (k, v) -> addProperty(k, v) } })
            fs.add(o)
        }
        root.add("files", fs)
        root.add("candidates", JsonObject().apply { candidates.forEach { (k, v) -> addProperty(k, v) } })
        root.add("failures", JsonObject().apply {
            failures.forEach { (k, v) -> add(k, JsonObject().apply { addProperty("reason", v.reason.name); addProperty("retryAt", v.retryAt) }) }
        })
        val rw = JsonArray()
        for (r in rewrites.values) {
            rw.add(JsonObject().apply {
                addProperty("world", r.world); addProperty("x", r.x); addProperty("y", r.y); addProperty("z", r.z)
                addProperty("from", r.from); addProperty("to", r.to)
            })
        }
        root.add("rewrites", rw)
        root.add("denied", JsonArray().apply { denied.forEach { add(it) } })
        Files.createDirectories(file.parent)
        val tmp = file.resolveSibling("${file.fileName}.tmp")
        Files.newBufferedWriter(tmp, StandardCharsets.UTF_8).use { Gson().toJson(root, it) }
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING)
    }

    /** 保存したものを読む。型・範囲・URL を確かめ、おかしければ例外 (呼ぶ側が退避して空から始める) */
    @Throws(IOException::class)
    fun load(file: Path) {
        if (!Files.isRegularFile(file)) return
        if (Files.size(file) > MAX_FILE_BYTES) throw IOException("hosting.json is too large")
        try {
            val root = Files.newBufferedReader(file, StandardCharsets.UTF_8).use { JsonParser.parseReader(it).asJsonObject }
            if (root.get("version").asInt != VERSION) return
            for (e in root.getAsJsonArray("files")) {
                val o = e.asJsonObject
                val id = o.get("id").asString
                val ext = o.get("ext").asString
                require(id.matches(ID) && ext.matches(EXT)) { "bad id" }
                val f = HostedFile(id, ext, o.get("size").asLong.also { require(it >= 0) }, o.get("hostedAt").asLong, o.get("lastUsedAt").asLong)
                for (s in o.getAsJsonArray("sources")) f.sources.add(checkUrl(s.asString))
                for ((k, v) in o.getAsJsonObject("origins").entrySet()) f.origins[checkKey(k)] = checkUrl(v.asString)
                add(f)
            }
            for ((k, v) in root.getAsJsonObject("candidates").entrySet()) candidates[checkUrl(k)] = v.asLong
            for ((k, v) in root.getAsJsonObject("failures").entrySet()) {
                val o = v.asJsonObject
                failures[checkUrl(k)] = Failure(HostingPolicy.Reject.valueOf(o.get("reason").asString), o.get("retryAt").asLong)
            }
            for (e in root.getAsJsonArray("rewrites")) {
                val o = e.asJsonObject
                val r = Rewrite(o.get("world").asString, o.get("x").asInt, o.get("y").asInt, o.get("z").asInt,
                    checkUrl(o.get("from").asString), checkUrl(o.get("to").asString))
                checkKey(r.key)
                rewrites[r.key] = r
            }
            for (e in root.getAsJsonArray("denied")) denied.add(checkUrl(e.asString))
        } catch (e: RuntimeException) {
            files.clear(); bySource.clear(); candidates.clear(); failures.clear(); rewrites.clear(); denied.clear()
            throw IOException("Broken hosting.json: ${e.javaClass.simpleName}", e)
        }
        dirty = false
    }

    companion object {
        private const val VERSION = 1
        private const val MAX_FILE_BYTES = 64L * 1024 * 1024
        val ID = Regex("[a-z2-7]{12}")
        val EXT = Regex("[a-z0-9]{1,10}")
        private val WORLD = Regex("[^:\\p{Cntrl}]{1,64}")

        fun signKey(world: String, x: Int, y: Int, z: Int) = "$world:$x:$y:$z"

        private fun checkUrl(url: String): String {
            require(url.length <= UrlFilter.MAX_URL_LENGTH && url.none { it < ' ' }) { "bad url" }
            return url
        }

        private fun checkKey(key: String): String {
            val p = key.split(':')
            require(p.size == 4 && p[0].matches(WORLD) && p.drop(1).all { it.toIntOrNull() != null }) { "bad key" }
            return key
        }
    }
}
