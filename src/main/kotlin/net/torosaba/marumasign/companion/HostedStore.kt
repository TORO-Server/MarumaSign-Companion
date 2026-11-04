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
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * ホストしているファイルと、ホストの途中の状態 (候補・失敗・書き換え待ちの看板・取り下げた URL・看板の作者・承認した URL)。
 * 保存先は [HostingStorage] (hosting.json か、データベース)。
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
        /** このファイルの分を数えるプレイヤー (UUID)。分からないファイル (記録を始める前からあるものなど) は null で、だれの上限にも数えない */
        @Volatile
        var owner: String? = null

        /** 中身の SHA-256 (16 進)。分からなければ空 (以前の hosting.json のファイル) */
        @Volatile
        var sha256: String = ""

        val fileName: String get() = "$id.$ext"
    }

    /** 書き換え待ちの看板。from の URL のままなら to に書き換える */
    data class Rewrite(val world: String, val x: Int, val y: Int, val z: Int, val from: String, val to: String) {
        val key: String get() = signKey(world, x, y, z)
    }

    data class Failure(val reason: HostingPolicy.Reject, val retryAt: Long)

    /** 看板を書いたプレイヤー (UUID)。url は書いたときの URL で、看板がほかの URL に変わったら、この記録は当てにしない */
    data class SignAuthor(val uuid: String, val url: String, val at: Long)

    val files = ConcurrentHashMap<String, HostedFile>()
    /** 元の URL → ホストしているファイルの id */
    val bySource = ConcurrentHashMap<String, String>()
    /** ホストの候補: 元の URL → 正常と確かめた最初の時刻 */
    val candidates = ConcurrentHashMap<String, Long>()
    val failures = ConcurrentHashMap<String, Failure>()
    val rewrites = ConcurrentHashMap<String, Rewrite>()
    /** 管理者が取り下げた元の URL。もうホストしない */
    val denied: MutableSet<String> = ConcurrentHashMap.newKeySet()
    /** 管理者が承認した元の URL (hosting.require-approval のとき)。ホストしたら消す */
    val approved: MutableSet<String> = ConcurrentHashMap.newKeySet()
    /** 看板 (world:x:y:z) → 書いたプレイヤー。記録がない看板は、作者が分からない看板 */
    val authors = ConcurrentHashMap<String, SignAuthor>()

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
            f.owner?.let { o.addProperty("owner", it) }
            if (f.sha256.isNotEmpty()) o.addProperty("sha256", f.sha256)
            o.add("sources", JsonArray().apply { f.sources.forEach { add(it) } })
            o.add("origins", JsonObject().apply { f.origins.forEach { (k, v) -> addProperty(k, v) } })
            fs.add(o)
        }
        root.add("files", fs)
        for (name in STATE_NAMES) root.add(name, stateJson(name))
        root.add("authors", JsonObject().apply {
            authors.forEach { (k, v) ->
                add(k, JsonObject().apply { addProperty("uuid", v.uuid); addProperty("url", v.url); addProperty("at", v.at) })
            }
        })
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
                val f = checkedFile(o.get("id").asString, o.get("ext").asString, o.get("size").asLong, o.get("hostedAt").asLong, o.get("lastUsedAt").asLong)
                for (s in o.getAsJsonArray("sources")) f.sources.add(checkUrl(s.asString))
                for ((k, v) in o.getAsJsonObject("origins").entrySet()) f.origins[checkKey(k)] = checkUrl(v.asString)
                // 以前の版が書いた hosting.json には、次の行がない (作者が分からないファイルとして読む)
                f.owner = checkOwner(text(o.get("owner")))
                f.sha256 = checkSha256(text(o.get("sha256")))
                add(f)
            }
            for (name in STATE_NAMES) root.get(name)?.let { readState(name, it) }
            root.get("authors")?.let { readAuthors(it) }
        } catch (e: RuntimeException) {
            clear()
            throw IOException("Broken hosting.json: ${e.javaClass.simpleName}", e)
        }
        dirty = false
    }

    /** すべて空にする (読み込みに失敗したとき) */
    fun clear() {
        files.clear(); bySource.clear(); candidates.clear(); failures.clear(); rewrites.clear(); denied.clear()
        approved.clear(); authors.clear()
    }

    /** hosting.json の作者の記録を読む。壊れた行は飛ばす (作者が分からない看板になるだけ) */
    private fun readAuthors(e: JsonElement) {
        if (!e.isJsonObject) return
        for ((k, v) in e.asJsonObject.entrySet()) {
            try {
                val o = v.asJsonObject
                val author = checkedAuthor(o.get("uuid").asString, o.get("url").asString, o.get("at").asLong)
                if (author != null) authors[checkKey(k)] = author
            } catch (ex: RuntimeException) {
                // 飛ばす
            }
        }
    }

    // ---- 状態の JSON (hosting.json の中と、データベースの state の行で同じ形) ----

    /** 候補・失敗・書き換え待ち・取り下げのどれか ([STATE_NAMES]) を JSON にする */
    fun stateJson(name: String): JsonElement = when (name) {
        "candidates" -> JsonObject().apply { candidates.forEach { (k, v) -> addProperty(k, v) } }
        "failures" -> JsonObject().apply {
            failures.forEach { (k, v) -> add(k, JsonObject().apply { addProperty("reason", v.reason.name); addProperty("retryAt", v.retryAt) }) }
        }
        "rewrites" -> JsonArray().apply {
            for (r in rewrites.values) {
                add(JsonObject().apply {
                    addProperty("world", r.world); addProperty("x", r.x); addProperty("y", r.y); addProperty("z", r.z)
                    addProperty("from", r.from); addProperty("to", r.to)
                })
            }
        }
        "denied" -> JsonArray().apply { denied.forEach { add(it) } }
        "approved" -> JsonArray().apply { approved.forEach { add(it) } }
        else -> throw IllegalArgumentException(name)
    }

    /** [stateJson] の形を読む。型・範囲・URL を確かめ、おかしければ RuntimeException */
    fun readState(name: String, e: JsonElement) {
        when (name) {
            "candidates" -> for ((k, v) in e.asJsonObject.entrySet()) candidates[checkUrl(k)] = v.asLong
            "failures" -> for ((k, v) in e.asJsonObject.entrySet()) {
                val o = v.asJsonObject
                failures[checkUrl(k)] = Failure(HostingPolicy.Reject.valueOf(o.get("reason").asString), o.get("retryAt").asLong)
            }
            "rewrites" -> for (x in e.asJsonArray) {
                val o = x.asJsonObject
                val r = Rewrite(o.get("world").asString, o.get("x").asInt, o.get("y").asInt, o.get("z").asInt,
                    checkUrl(o.get("from").asString), checkUrl(o.get("to").asString))
                checkKey(r.key)
                rewrites[r.key] = r
            }
            "denied" -> for (x in e.asJsonArray) denied.add(checkUrl(x.asString))
            "approved" -> for (x in e.asJsonArray) approved.add(checkUrl(x.asString))
            else -> throw IllegalArgumentException(name)
        }
    }

    /** [readState] で読んだものを空にする (読み込みに失敗したとき) */
    fun clearState(name: String) {
        when (name) {
            "candidates" -> candidates.clear()
            "failures" -> failures.clear()
            "rewrites" -> rewrites.clear()
            "denied" -> denied.clear()
            "approved" -> approved.clear()
        }
    }

    companion object {
        private const val VERSION = 1
        private const val MAX_FILE_BYTES = 64L * 1024 * 1024
        val ID = Regex("[a-z2-7]{12}")
        val EXT = Regex("[a-z0-9]{1,10}")
        private val WORLD = Regex("[^:\\p{Cntrl}]{1,64}")
        private val UUID_TEXT = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
        private val SHA256_TEXT = Regex("[0-9a-f]{64}")
        /** ファイル以外の状態の名前 (hosting.json のキー、データベースの state の行の name) */
        val STATE_NAMES = listOf("candidates", "failures", "rewrites", "denied", "approved")

        fun signKey(world: String, x: Int, y: Int, z: Int) = "$world:$x:$y:$z"

        /** 承認のコマンドで URL を指す短いキー (URL の UTF-8 の SHA-256 から作る 12 文字) */
        fun urlKey(url: String): String =
            HostFetcher.idOf(MessageDigest.getInstance("SHA-256").digest(url.toByteArray(StandardCharsets.UTF_8)))

        private fun text(e: JsonElement?): String? = e?.takeIf { it.isJsonPrimitive }?.asString

        /** 保存してあったプレイヤーの UUID。おかしければ null (作者が分からないものとして扱う) */
        internal fun checkOwner(uuid: String?): String? = uuid?.takeIf { it.matches(UUID_TEXT) }

        /** 保存してあった中身の SHA-256。おかしければ空 */
        internal fun checkSha256(sha256: String?): String = sha256?.takeIf { it.matches(SHA256_TEXT) } ?: ""

        /** 保存してあった作者の記録。おかしければ null か RuntimeException */
        internal fun checkedAuthor(uuid: String?, url: String?, at: Long): SignAuthor? {
            val owner = checkOwner(uuid) ?: return null
            return SignAuthor(owner, checkUrl(url ?: return null), at)
        }

        /** 保存してあったファイルの情報を確かめて作る。おかしければ RuntimeException */
        internal fun checkedFile(id: String, ext: String, size: Long, hostedAt: Long, lastUsedAt: Long): HostedFile {
            require(id.matches(ID) && ext.matches(EXT)) { "bad id" }
            require(size >= 0) { "bad size" }
            return HostedFile(id, ext, size, hostedAt, lastUsedAt)
        }

        internal fun checkUrl(url: String): String {
            require(url.length <= UrlFilter.MAX_URL_LENGTH && url.none { it < ' ' }) { "bad url" }
            return url
        }

        internal fun checkKey(key: String): String {
            val p = key.split(':')
            require(p.size == 4 && p[0].matches(WORLD) && p.drop(1).all { it.toIntOrNull() != null }) { "bad key" }
            return key
        }
    }
}
