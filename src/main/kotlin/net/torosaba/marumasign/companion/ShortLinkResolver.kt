package net.torosaba.marumasign.companion

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.logging.Logger

/**
 * 短縮 URL (is.gd など) の行き先を調べる。3D 表示では、ブラウザーが画像を直接読む必要があり、
 * 短縮サービスの転送は CORS ヘッダーがなく画像として使えないため、サーバーで行き先を調べてキャッシュする。
 *
 * つなぐのは設定した短縮サービスのホスト (既定は is.gd など) だけで、HEAD で Location ヘッダーを読むだけ。
 * 転送は自動で追わず (最大 3 回まで、次も短縮サービスのときだけ)、行き先のサーバーにはつながない。
 * 行き先は [UrlFilter] で確認してから使う。
 *
 * @param hosts      つないでよい短縮サービスのホスト (小文字)
 * @param file       行き先のキャッシュを保存するファイル
 * @param onProgress 新しい行き先が分かったときに (まとめて) 呼ぶ
 */
class ShortLinkResolver internal constructor(
    private val log: Logger,
    private val filter: UrlFilter,
    hosts: Set<String>,
    private val file: Path,
    private val onProgress: Runnable,
    head: Head?,
    private val intervalMillis: Long,
) {

    /** target が null なら調べたが行き先が分からなかった */
    private data class Entry(val target: String?, val time: Long)

    /** 1 回の HEAD の結果 (転送先は Location ヘッダー。なければ null) */
    internal data class Reply(val status: Int, val location: String?)

    /** HEAD リクエストを送る処理 (テストで差し替える) */
    internal fun interface Head {
        @Throws(IOException::class, InterruptedException::class)
        fun head(uri: URI): Reply
    }

    @Volatile
    private var hosts: Set<String> = hosts
    private val head: Head = head ?: HttpHead()
    private val cache = ConcurrentHashMap<String, Entry>()
    private val queue = LinkedHashSet<String>()
    @Volatile
    private var dirty = false
    @Volatile
    private var stopped = false
    private var worker: Thread? = null

    constructor(log: Logger, filter: UrlFilter, hosts: Set<String>, file: Path, onProgress: Runnable) :
        this(log, filter, hosts, file, onProgress, null, REQUEST_INTERVAL_MILLIS)

    fun setHosts(hosts: Set<String>) {
        this.hosts = hosts
    }

    /** 短縮サービスの URL なら true (https の 443 番だけ。サービスのホストは設定で決めたものに限る) */
    fun isShort(url: String?): Boolean {
        val uri = filter.parse(url)
        if (uri == null || !"https".equals(uri.scheme, ignoreCase = true)) return false
        if (uri.port != -1 && uri.port != 443) return false
        val host: String? = uri.host
        return host != null && hosts.contains(host.lowercase())
    }

    // 本物の HEAD。転送は追わず、本文は読まず、Cookie も使わない
    private class HttpHead : Head {
        private val client: HttpClient = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofSeconds(8))
            .build()

        override fun head(uri: URI): Reply {
            val request = HttpRequest.newBuilder(uri)
                .method("HEAD", HttpRequest.BodyPublishers.noBody())
                .timeout(Duration.ofSeconds(10))
                .header("User-Agent", "MarumaSignCompanion/1.0 (short link resolver)")
                .build()
            val response = client.send(request, HttpResponse.BodyHandlers.discarding())
            return Reply(response.statusCode(), response.headers().firstValue("Location").orElse(null))
        }
    }

    /** 調べ済みの行き先。まだ、または調べられなかったら null */
    fun resolved(url: String): String? {
        val e = cache[url]
        return e?.target
    }

    /** 行き先を調べるよう頼む (調べ済みや、最近失敗したものは何もしない) */
    fun request(url: String) {
        val e = cache[url]
        if (e != null && (e.target != null || System.currentTimeMillis() - e.time < RETRY_AFTER_MILLIS)) return
        if (!isShort(url)) return
        synchronized(queue) {
            queue.add(url)
            (queue as java.lang.Object).notifyAll()
        }
    }

    fun pending(): Int {
        synchronized(queue) {
            return queue.size
        }
    }

    fun resolvedCount(): Int {
        var n = 0
        for (e in cache.values) if (e.target != null) n++
        return n
    }

    @Synchronized
    fun start() {
        if (worker != null) return
        stopped = false
        val w = Thread(this::run, "MarumaSignCompanion-shortlinks")
        worker = w
        w.isDaemon = true
        w.priority = Thread.MIN_PRIORITY
        w.start()
    }

    @Synchronized
    fun stop() {
        stopped = true
        synchronized(queue) {
            (queue as java.lang.Object).notifyAll()
        }
        worker?.interrupt()
        worker = null
        save()
    }

    private fun run() {
        var sinceNotify = 0
        var lastSave = System.currentTimeMillis()
        try {
            while (!stopped) {
                val url: String? = synchronized(queue) {
                    while (queue.isEmpty() && !stopped) {
                        if (sinceNotify > 0) break
                        (queue as java.lang.Object).wait(30_000)
                    }
                    if (stopped) return
                    if (queue.isEmpty()) {
                        null
                    } else {
                        val it = queue.iterator()
                        val next = it.next()
                        it.remove()
                        next
                    }
                }
                if (url == null) {
                    // 溜まった分をまとめて反映する
                    sinceNotify = 0
                    save()
                    onProgress.run()
                    continue
                }
                val before = cache[url]
                if (before != null && (before.target != null || System.currentTimeMillis() - before.time < RETRY_AFTER_MILLIS)) continue
                var target: String? = null
                var backoff = false
                try {
                    target = resolve(url)
                } catch (e: InterruptedException) {
                    throw e
                } catch (e: Exception) {
                    backoff = true
                    log.fine("Could not resolve short link: $e")
                }
                cache[url] = Entry(target, System.currentTimeMillis())
                dirty = true
                if (target != null) sinceNotify++
                if (sinceNotify >= 200) {
                    sinceNotify = 0
                    onProgress.run()
                }
                if (System.currentTimeMillis() - lastSave > 60_000) {
                    save()
                    lastSave = System.currentTimeMillis()
                }
                Thread.sleep(if (backoff) Math.max(5_000L, intervalMillis) else intervalMillis)
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    @Throws(IOException::class, InterruptedException::class)
    internal fun resolve(start: String): String? {
        var current = start
        for (hop in 0 until MAX_HOPS) {
            val uri = filter.parse(current)
            // つなぐのは設定した短縮サービスのホストだけ。行き先がそれ以外なら、つながずにそこで止める
            if (uri == null || !isShort(current)) return if (hop == 0) null else current
            val reply = head.head(uri)
            val code = reply.status
            if (code == 429 || code >= 500) throw IOException("HTTP $code")
            if (code < 300 || code >= 400) return null
            val location = reply.location
            if (location == null || location.length > UrlFilter.MAX_URL_LENGTH) return null
            val next: String = try {
                uri.resolve(location).toString()
            } catch (e: IllegalArgumentException) {
                return null
            }
            if (filter.parse(next) == null) return null
            current = next
            if (!isShort(current)) return current
        }
        return null
    }

    // ---- 保存と読み込み ----

    @Synchronized
    fun save() {
        if (!dirty && Files.exists(file)) return
        dirty = false
        val root = JsonObject()
        root.addProperty("version", 1)
        val map = JsonObject()
        for (e in cache.entries) {
            val a = JsonArray()
            a.add(e.value.target ?: "")
            a.add(e.value.time)
            map.add(e.key, a)
        }
        root.add("links", map)
        try {
            Files.createDirectories(file.parent)
            val tmp = file.resolveSibling(file.fileName.toString() + ".tmp")
            Files.newBufferedWriter(tmp, StandardCharsets.UTF_8).use { w ->
                Gson().toJson(root, w)
            }
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING)
        } catch (e: IOException) {
            log.warning("Could not save short link cache: $e")
        }
    }

    fun load() {
        if (!Files.isRegularFile(file)) return
        try {
            Files.newBufferedReader(file, StandardCharsets.UTF_8).use { r ->
                val root = JsonParser.parseReader(r).asJsonObject
                if (root.get("version").asInt != 1) return
                for (e in root.getAsJsonObject("links").entrySet()) {
                    val a = e.value.asJsonArray
                    val target = a.get(0).asString
                    // 読み込んだ行き先も、使う前にもう一度確認する
                    cache[e.key] = Entry(if (target.isEmpty() || filter.parse(target) == null) null else target, a.get(1).asLong)
                }
            }
        } catch (e: Exception) {
            if (e !is IOException && e !is RuntimeException) throw e
            log.warning("Ignoring short link cache: $e")
            cache.clear()
        }
    }

    companion object {
        private const val MAX_HOPS = 3
        private const val RETRY_AFTER_MILLIS = 6L * 60 * 60 * 1000
        private const val REQUEST_INTERVAL_MILLIS = 300L
    }
}
