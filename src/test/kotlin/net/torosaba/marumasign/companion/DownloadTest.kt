package net.torosaba.marumasign.companion

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.Socket
import java.net.URI
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

/** ホストのための読み込み (SafeHttp の本文の書き出しと HostFetcher) */
class DownloadTest {

    private val servers = ArrayList<SafeHttpTest.RawServer>()

    @AfterEach
    fun closeServers() {
        for (s in servers) s.close()
    }

    private fun serve(h: SafeHttpTest.RawServer.Handler) = SafeHttpTest.RawServer(h).also { servers.add(it) }

    private fun http(): SafeHttp {
        val o = SafeHttp.Options()
        o.allowNonPublicForTests = true
        o.connectTimeoutMillis = 2000
        o.resolver = SafeHttp.fixedResolver(mapOf("site.example" to arrayOf(InetAddress.getLoopbackAddress())))
        return SafeHttp(o)
    }

    private fun uri(s: SafeHttpTest.RawServer, path: String) = URI.create("http://site.example:" + s.port + path)

    private fun write(s: Socket, head: String, body: ByteArray = ByteArray(0)) {
        s.getOutputStream().write(head.toByteArray(StandardCharsets.ISO_8859_1))
        s.getOutputStream().write(body)
        s.getOutputStream().flush()
    }

    private fun download(s: SafeHttpTest.RawServer, path: String, max: Long = 1000): Pair<SafeHttp.Response, ByteArray> {
        val out = ByteArrayOutputStream()
        val r = http().request("GET", uri(s, path), null, SafeHttp.Download(max, 5000, out) { true })
        return r to out.toByteArray()
    }

    @Test
    fun contentLengthBodyIsWrittenExactly() {
        val body = ByteArray(300) { it.toByte() }
        val s = serve { sock, _ -> write(sock, "HTTP/1.1 200 OK\r\nContent-Type: image/png\r\nContent-Length: 300\r\n\r\n", body + ByteArray(50)) }
        val (r, got) = download(s, "/a.png")
        assertEquals(200, r.status)
        assertEquals(300L, r.contentLength)
        assertFalse(r.truncated)
        assertArrayEquals(body, got)
    }

    @Test
    fun chunkedBodyIsDecoded() {
        val s = serve { sock, _ ->
            write(sock, "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n5;ext=1\r\nhello\r\n6\r\n world\r\n0\r\nX-Trailer: 1\r\n\r\n")
        }
        val (r, got) = download(s, "/a.png")
        assertFalse(r.truncated)
        assertEquals("hello world", String(got, StandardCharsets.US_ASCII))
    }

    @Test
    fun oversizedOrShortBodiesAreTruncated() {
        val big = serve { sock, _ -> write(sock, "HTTP/1.1 200 OK\r\n\r\n", ByteArray(2000)) }
        assertTrue(download(big, "/a.png").first.truncated, "長さのない本文が上限を超える")
        val declared = serve { sock, _ -> write(sock, "HTTP/1.1 200 OK\r\nContent-Length: 5000\r\n\r\n", ByteArray(10)) }
        assertTrue(download(declared, "/a.png").first.truncated, "Content-Length が上限を超える")
        val short = serve { sock, _ -> write(sock, "HTTP/1.1 200 OK\r\nContent-Length: 100\r\n\r\n", ByteArray(10)) }
        assertTrue(download(short, "/a.png").first.truncated, "途中で切れた")
        val chunks = serve { sock, _ -> write(sock, "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n" + "3e8\r\n" + "x".repeat(1000) + "\r\n3e8\r\n") }
        assertTrue(download(chunks, "/a.png", 1500).first.truncated, "chunked で上限を超える")
        val badChunk = serve { sock, _ -> write(sock, "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\nzz\r\n") }
        assertTrue(download(badChunk, "/a.png").first.truncated)
    }

    @Test
    fun conflictingLengthsAndOtherCodingsAreRefused() {
        val conflict = serve { sock, _ -> write(sock, "HTTP/1.1 200 OK\r\nContent-Length: 3\r\nContent-Length: 4\r\n\r\nabcd") }
        assertEquals(SafeHttp.Kind.BAD_RESPONSE, kindOf { download(conflict, "/a.png") })
        val gzip = serve { sock, _ -> write(sock, "HTTP/1.1 200 OK\r\nTransfer-Encoding: gzip, chunked\r\n\r\n0\r\n\r\n") }
        assertEquals(SafeHttp.Kind.BAD_RESPONSE, kindOf { download(gzip, "/a.png") })
    }

    @Test
    fun acceptCanSkipTheBody() {
        val s = serve { sock, _ -> write(sock, "HTTP/1.1 200 OK\r\nContent-Type: audio/mpeg\r\nicy-name: live\r\n\r\n", ByteArray(500)) }
        val out = ByteArrayOutputStream()
        var seen: SafeHttp.Response? = null
        http().request("GET", uri(s, "/live.mp3"), null, SafeHttp.Download(1000, 5000, out) { seen = it; false })
        assertTrue(seen!!.icy)
        assertEquals(0, out.size())
    }

    // ---- HostFetcher ----

    private fun fetcher(): HostFetcher {
        val cfg = LinkSettings()
        cfg.hostMinIntervalSeconds = 0
        return HostFetcher(http(), { URI.create(it) }, HostThrottle(cfg), 5, MediaKind.defaultExtensions())
    }

    @Test
    fun fetchFollowsRedirectsAndNamesByContent(@TempDir dir: Path) {
        val png = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 1, 2, 3)
        lateinit var s: SafeHttpTest.RawServer
        s = serve { sock, req ->
            if (req.startsWith("GET /short ")) write(sock, "HTTP/1.1 302 Found\r\nLocation: /img\r\nContent-Length: 0\r\n\r\n")
            else write(sock, "HTTP/1.1 200 OK\r\nContent-Type: image/png\r\nContent-Length: ${png.size}\r\n\r\n", png)
        }
        val r = fetcher().fetch(uri(s, "/short").toString(), dir, 1000, 5000)
        assertTrue(r is HostFetcher.Fetched, r.toString())
        r as HostFetcher.Fetched
        assertEquals("png", r.ext, "拡張子のない URL は Content-Type から")
        assertTrue(r.id.matches(HostedStore.ID))
        assertArrayEquals(png, Files.readAllBytes(r.file))
        val again = fetcher().fetch(uri(s, "/img").toString(), dir, 1000, 5000) as HostFetcher.Fetched
        assertEquals(r.id, again.id, "同じ中身は同じ id")
    }

    @Test
    fun fetchRejectsStreamsHtmlAndLargeFiles(@TempDir dir: Path) {
        val s = serve { sock, req ->
            when {
                req.startsWith("GET /live.mp3") -> write(sock, "HTTP/1.1 200 OK\r\nContent-Type: audio/mpeg\r\nicy-br: 128\r\n\r\n", ByteArray(100))
                req.startsWith("GET /page.png") -> write(sock, "HTTP/1.1 200 OK\r\nContent-Type: text/html\r\nContent-Length: 4\r\n\r\n<p/>")
                req.startsWith("GET /big.png") -> write(sock, "HTTP/1.1 200 OK\r\nContent-Type: image/png\r\nContent-Length: 99999\r\n\r\n")
                req.startsWith("GET /cam") -> write(sock, "HTTP/1.1 200 OK\r\nContent-Type: multipart/x-mixed-replace; boundary=x\r\n\r\n")
                else -> write(sock, "HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\n\r\n")
            }
        }
        fun reason(path: String) = (fetcher().fetch(uri(s, path).toString(), dir, 1000, 5000) as HostFetcher.Rejected).reason
        assertEquals(HostingPolicy.Reject.STREAM, reason("/live.mp3"))
        assertEquals(HostingPolicy.Reject.STREAM, reason("/stream.m3u8"), "目録の拡張子は読みにも行かない")
        assertEquals(HostingPolicy.Reject.HTML, reason("/page.png"))
        assertEquals(HostingPolicy.Reject.TOO_LARGE, reason("/big.png"))
        assertEquals(HostingPolicy.Reject.STREAM, reason("/cam"))
        assertEquals(HostingPolicy.Reject.HTTP, reason("/gone.png"))
        assertEquals(1, s.requests.count { it.contains("/live.mp3") })
        assertEquals(0, s.requests.count { it.contains("m3u8") })
        Files.list(dir).use { assertEquals(0L, it.count(), "一時ファイルを残さない") }
    }

    @Test
    fun idIsTwelveBase32Characters() {
        val a = HostFetcher.idOf(ByteArray(32) { 0 })
        val b = HostFetcher.idOf(ByteArray(32) { 0xFF.toByte() })
        assertEquals("aaaaaaaaaaaa", a)
        assertEquals("777777777777", b)
        assertNotEquals(HostFetcher.idOf(ByteArray(32) { it.toByte() }), a)
    }

    private fun kindOf(block: () -> Unit): SafeHttp.Kind? = try {
        block()
        null
    } catch (f: SafeHttp.HttpFailure) {
        f.kind
    }
}
