package net.torosaba.marumasign.companion

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ExecutorService
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * ホストしたファイルを配る、内蔵の小さな Web サーバー。
 * 配るのは `/<id>.<拡張子>` の形で、ホストしているファイルだけ (パスはファイル名の形を確かめ、フォルダの外は読まない)。
 * 公開するときは、前に HTTPS の逆プロキシ (Cloudflare や nginx) を置いて、hosting.public-base-url をそのアドレスにする。
 */
class HostingServer(
    private val bind: String,
    private val port: Int,
    private val directory: Path,
    private val lookup: (String) -> HostedStore.HostedFile?,
) {
    private var server: HttpServer? = null
    private var pool: ExecutorService? = null

    @Throws(IOException::class)
    fun start() {
        val s = HttpServer.create(InetSocketAddress(bind, port), 64)
        // 同時に配る数に上限 (遅いクライアントに枠を取られすぎないよう、待ち行列も有限)
        val p = ThreadPoolExecutor(2, 8, 30, TimeUnit.SECONDS, LinkedBlockingQueue(256)) { r ->
            Thread(r, "MarumaSignCompanion-http").apply { isDaemon = true }
        }
        s.executor = p
        s.createContext("/") { ex -> handle(ex) }
        s.start()
        server = s
        pool = p
    }

    fun stop() {
        server?.stop(0)
        server = null
        pool?.shutdownNow()
        pool = null
    }

    private fun handle(ex: HttpExchange) {
        ex.use {
            try {
                serve(ex)
            } catch (e: IOException) {
                // 相手が途中で切った
            }
        }
    }

    private fun serve(ex: HttpExchange) {
        val h = ex.responseHeaders
        h["X-Content-Type-Options"] = "nosniff"
        h["Referrer-Policy"] = "no-referrer"
        // SVG などにスクリプトがあっても動かさない
        h["Content-Security-Policy"] = "default-src 'none'; style-src 'unsafe-inline'; sandbox"
        // BlueMap の 3D 表示 (WebGL のテクスチャ) が読めるように
        h["Access-Control-Allow-Origin"] = "*"
        val method = ex.requestMethod
        if (method != "GET" && method != "HEAD") {
            h["Allow"] = "GET, HEAD"
            ex.sendResponseHeaders(405, -1)
            return
        }
        val path = ex.requestURI.rawPath ?: ""
        val m = PATH.matchEntire(path)
        val file = m?.let { lookup(it.groupValues[1]) }?.takeIf { it.ext == m.groupValues[2] }
        val local = file?.let { directory.resolve(it.fileName) }
        if (file == null || local == null || !Files.isRegularFile(local)) {
            ex.sendResponseHeaders(404, -1)
            return
        }
        val size = Files.size(local)
        val etag = "\"${file.id}\""
        h["Content-Type"] = HostingPolicy.contentTypeFor(file.ext)
        h["Cache-Control"] = "public, max-age=31536000, immutable"
        h["ETag"] = etag
        h["Accept-Ranges"] = "bytes"
        if (ex.requestHeaders.getFirst("If-None-Match") == etag) {
            ex.sendResponseHeaders(304, -1)
            return
        }
        val range = parseRange(ex.requestHeaders.getFirst("Range"), size)
        if (range == INVALID) {
            h["Content-Range"] = "bytes */$size"
            ex.sendResponseHeaders(416, -1)
            return
        }
        val (start, end) = range ?: (0L to size - 1)
        val length = if (size == 0L) 0L else end - start + 1
        if (range != null) h["Content-Range"] = "bytes $start-$end/$size"
        val status = if (range != null) 206 else 200
        if (method == "HEAD") {
            h["Content-Length"] = length.toString()
            ex.sendResponseHeaders(status, -1)
            return
        }
        ex.sendResponseHeaders(status, if (length == 0L) -1 else length)
        if (length == 0L) return
        Files.newInputStream(local).use { input ->
            input.skipNBytes(start)
            copy(input, ex.responseBody, length)
        }
    }

    private fun copy(input: InputStream, out: java.io.OutputStream, length: Long) {
        val buf = ByteArray(64 * 1024)
        var left = length
        while (left > 0) {
            val n = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
            if (n < 0) break
            out.write(buf, 0, n)
            left -= n
        }
    }

    companion object {
        private val PATH = Regex("/([a-z2-7]{12})\\.([a-z0-9]{1,10})")
        private val RANGE = Regex("bytes=(\\d{0,19})-(\\d{0,19})")
        private val INVALID = -1L to -1L

        /** Range ヘッダー (1 つの範囲だけ対応)。なし・対応しない形は null (全体を返す)、範囲外は INVALID */
        internal fun parseRange(header: String?, size: Long): Pair<Long, Long>? {
            val m = header?.trim()?.let { RANGE.matchEntire(it) } ?: return null
            val (a, b) = m.destructured
            if (a.isEmpty() && b.isEmpty()) return null
            if (size == 0L) return INVALID
            val first = a.toLongOrNull()
            val last = b.toLongOrNull()
            return when {
                a.isEmpty() -> {
                    val suffix = last ?: return null
                    if (suffix == 0L) INVALID else maxOf(0L, size - suffix) to size - 1
                }
                first == null || first >= size -> INVALID
                b.isEmpty() -> first to size - 1
                last == null || last < first -> INVALID
                else -> first to minOf(last, size - 1)
            }
        }
    }
}
