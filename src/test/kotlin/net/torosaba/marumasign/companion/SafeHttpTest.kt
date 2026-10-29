package net.torosaba.marumasign.companion

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.ConnectException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.net.UnknownHostException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.Random
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.GZIPOutputStream

/** 自前の HTTP クライアント (SafeHttp) の、安全面とふるまいの確認 */
class SafeHttpTest {

    /** 1 接続ごとに handler を呼ぶ、台本どおりに応答する生のサーバー */
    internal class RawServer(h: Handler) : AutoCloseable {
        fun interface Handler {
            @Throws(Exception::class)
            fun handle(s: Socket, request: String)
        }

        val server = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
        val port: Int = server.localPort
        val requests: MutableList<String> = CopyOnWriteArrayList()
        val connections = AtomicInteger()
        @Volatile
        private var closed = false

        init {
            val t = Thread {
                while (!closed) {
                    try {
                        val s = server.accept()
                        connections.incrementAndGet()
                        val c = Thread {
                            try {
                                s.use {
                                    val req = readRequest(s)
                                    requests.add(req)
                                    h.handle(s, req)
                                }
                            } catch (e: Exception) {
                                // クライアントが切ったなど
                            }
                        }
                        c.isDaemon = true
                        c.start()
                    } catch (e: IOException) {
                        return@Thread
                    }
                }
            }
            t.isDaemon = true
            t.start()
        }

        override fun close() {
            closed = true
            server.close()
        }

        companion object {
            @Throws(IOException::class)
            fun readRequest(s: Socket): String {
                val input = s.getInputStream()
                val b = ByteArrayOutputStream()
                while (true) {
                    val c = input.read()
                    if (c < 0) break
                    b.write(c)
                    val a = b.toByteArray()
                    val n = a.size
                    if (n >= 4 && a[n - 4] == '\r'.code.toByte() && a[n - 3] == '\n'.code.toByte() && a[n - 2] == '\r'.code.toByte() && a[n - 1] == '\n'.code.toByte()) break
                    if (n > 65536) break
                }
                return b.toString(StandardCharsets.ISO_8859_1)
            }
        }
    }

    private val servers = ArrayList<RawServer>()

    @AfterEach
    fun closeServers() {
        for (s in servers) s.close()
    }

    private fun serve(h: RawServer.Handler): RawServer {
        val s = RawServer(h)
        servers.add(s)
        return s
    }

    // ---- ふつうの応答 ----

    @Test
    fun headAndGetReadStatusAndHeaders() {
        val s = serve { sock, _ -> write(sock, "HTTP/1.1 200 OK\r\nContent-Type: Image/PNG; charset=x\r\nContent-Length: 5\r\n\r\nhello") }
        val head = client(5000).request("HEAD", uri(s, "/a.png"), null)
        assertEquals(200, head.status)
        assertEquals("image/png", head.contentType)
        assertEquals(0L, head.bodyBytes, "HEAD は本文を読まない")
        val get = client(5000).request("GET", uri(s, "/a.png"), "bytes=0-1023")
        assertEquals(5L, get.bodyBytes)
        assertTrue(s.requests[1].startsWith("GET /a.png HTTP/1.1\r\n"))
        assertTrue(s.requests[1].contains("Range: bytes=0-1023\r\n"))
    }

    @Test
    fun sendsOnlyMinimalHeadersNeverCookiesOrReferer() {
        val s = serve { sock, _ -> write(sock, "HTTP/1.1 204 No Content\r\n\r\n") }
        client(5000).request("GET", uri(s, "/x?a=1"), null)
        val req = s.requests[0].lowercase()
        assertTrue(req.startsWith("get /x?a=1 http/1.1\r\n"))
        assertTrue(req.contains("host: site.example:" + s.port + "\r\n"))
        assertTrue(req.contains("user-agent: marumasigncompanion-linkcheck/1.0\r\n"))
        assertTrue(req.contains("connection: close\r\n"))
        for (forbidden in listOf("cookie", "referer", "authorization", "accept-encoding", "origin")) {
            assertFalse(req.contains(forbidden), forbidden)
        }
    }

    @Test
    fun doesNotFollowRedirectsAndReturnsLocation() {
        val s = serve { sock, _ -> write(sock, "HTTP/1.1 302 Found\r\nLocation: http://other.example/x\r\n\r\n") }
        val r = client(5000).request("HEAD", uri(s, "/"), null)
        assertEquals(302, r.status)
        assertEquals("http://other.example/x", r.location)
        assertEquals(1, s.connections.get())
    }

    @Test
    fun skipsInformationalResponses() {
        val s = serve { sock, _ -> write(sock, "HTTP/1.1 100 Continue\r\n\r\nHTTP/1.1 200 OK\r\n\r\n") }
        assertEquals(200, client(5000).request("HEAD", uri(s, "/"), null).status)
    }

    // ---- 資源の上限 ----

    @Test
    fun bodyIsReadOnlyUpToTheLimitAndThenDropped() {
        val s = serve { sock, _ ->
            write(sock, "HTTP/1.1 200 OK\r\n\r\n")
            val chunk = ByteArray(8192)
            for (i in 0 until 1024) sock.getOutputStream().write(chunk) // 8 MB
        }
        val r = client(10_000).request("GET", uri(s, "/big"), null)
        assertEquals(200, r.status)
        assertEquals((64 * 1024).toLong(), r.bodyBytes, "本文は 64 KB までしか読まない")
    }

    @Test
    fun compressedBodyIsNeverInflated() {
        // 100 MB のゼロを gzip にした小さな本文 (圧縮爆弾)。展開せず、送られたバイトを読んで捨てるだけ
        val bomb = ByteArrayOutputStream()
        GZIPOutputStream(bomb).use { gz ->
            val zeros = ByteArray(1 shl 20)
            for (i in 0 until 100) gz.write(zeros)
        }
        val body = bomb.toByteArray()
        assertTrue(body.size < 200_000)
        val s = serve { sock, _ ->
            write(sock, "HTTP/1.1 200 OK\r\nContent-Encoding: gzip\r\nContent-Length: " + body.size + "\r\n\r\n")
            sock.getOutputStream().write(body)
        }
        val r = client(10_000).request("GET", uri(s, "/bomb"), null)
        assertEquals(200, r.status)
        assertTrue(r.bodyBytes <= 64 * 1024, "読んだのは、送られた (圧縮された) バイトの先頭だけ: " + r.bodyBytes)
    }

    @Test
    fun oversizedHeaderLinesAndTooManyHeadersAreRejected() {
        val longLine = serve { sock, _ -> write(sock, "HTTP/1.1 200 OK\r\nX-Long: " + "a".repeat(9000) + "\r\n\r\n") }
        assertEquals(SafeHttp.Kind.BAD_RESPONSE, failureOf(client(5000), uri(longLine, "/"), "HEAD"))

        val many = serve { sock, _ ->
            val sb = StringBuilder("HTTP/1.1 200 OK\r\n")
            for (i in 0 until 500) sb.append("X-").append(i).append(": v\r\n")
            write(sock, sb.append("\r\n").toString())
        }
        assertEquals(SafeHttp.Kind.BAD_RESPONSE, failureOf(client(5000), uri(many, "/"), "HEAD"))

        val huge = serve { sock, _ ->
            val sb = StringBuilder("HTTP/1.1 200 OK\r\n")
            for (i in 0 until 90) sb.append("X-").append(i).append(": ").append("b".repeat(1000)).append("\r\n")
            write(sock, sb.append("\r\n").toString())
        }
        assertEquals(SafeHttp.Kind.BAD_RESPONSE, failureOf(client(5000), uri(huge, "/"), "HEAD"), "合計の大きさの上限")
    }

    @Test
    fun endlessHeadersStopAtTheLimit() {
        val s = serve { sock, _ ->
            write(sock, "HTTP/1.1 200 OK\r\n")
            for (i in 0 until 100_000) write(sock, "X-Endless-" + i + ": " + "c".repeat(100) + "\r\n")
        }
        val t0 = System.nanoTime()
        assertEquals(SafeHttp.Kind.BAD_RESPONSE, failureOf(client(10_000), uri(s, "/"), "HEAD"))
        assertTrue((System.nanoTime() - t0) / 1_000_000 < 5000)
    }

    @Test
    fun slowDripResponseIsCutByTheOverallDeadline() {
        // 100 ms ごとに 1 バイトだけ送る。1 回の read のタイムアウトには引っかからないが、全体の期限で切れる
        val s = serve { sock, _ ->
            val line = "HTTP/1.1 200 OK\r\nX-Slow: " + "z".repeat(200) + "\r\n\r\n"
            for (i in line.indices) {
                sock.getOutputStream().write(line[i].code)
                sock.getOutputStream().flush()
                Thread.sleep(100)
            }
        }
        val t0 = System.nanoTime()
        assertEquals(SafeHttp.Kind.TIMEOUT, failureOf(client(1500), uri(s, "/"), "HEAD"))
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertTrue(ms >= 1200 && ms < 4000, "期限 (1.5 秒) で切れた: $ms ms")
    }

    @Test
    fun slowDripBodyStillEndsAtTheDeadline() {
        val s = serve { sock, _ ->
            write(sock, "HTTP/1.1 200 OK\r\n\r\n")
            for (i in 0 until 1000) {
                sock.getOutputStream().write('x'.code)
                sock.getOutputStream().flush()
                Thread.sleep(50)
            }
        }
        val t0 = System.nanoTime()
        val r = client(1500).request("GET", uri(s, "/"), null)
        assertEquals(200, r.status, "状態は分かっているので、本文が切れても結果は返る")
        assertTrue((System.nanoTime() - t0) / 1_000_000 < 4000)
    }

    @Test
    fun serverThatNeverRespondsTimesOut() {
        val s = serve { _, _ -> Thread.sleep(10_000) }
        val t0 = System.nanoTime()
        assertEquals(SafeHttp.Kind.TIMEOUT, failureOf(client(1000), uri(s, "/"), "HEAD"))
        assertTrue((System.nanoTime() - t0) / 1_000_000 < 3500)
    }

    // ---- 不正な応答 ----

    @Test
    fun malformedResponsesAreBadResponse() {
        for (raw in listOf(
            "garbage\r\n\r\n",
            "HTTP/9.9 200 OK\r\n\r\n",
            "HTTP/1.1 99 Low\r\n\r\n",
            "HTTP/1.1 700 High\r\n\r\n",
            "HTTP/1.1 200\u0000 OK\r\n\r\n",
            "HTTP/1.1 101 Switching\r\n\r\n",
            "HTTP/1.1 200 OK\r\nno colon here\r\n\r\n",
            "HTTP/1.1 200 OK\r\n folded: header\r\n\r\n",
            "HTTP/1.1 200 OK\r\nBad Name: v\r\n\r\n",
            "HTTP/1.1 200 OK\r\nX: a\u0001b\r\n\r\n",
            "")) {
            val s = serve { sock, _ -> write(sock, raw) }
            assertEquals(SafeHttp.Kind.BAD_RESPONSE, failureOf(client(3000), uri(s, "/"), "HEAD"), raw.replace("\r\n", "|"))
        }
    }

    @Test
    fun fuzzedResponsesNeverEscapeAsAnythingButAClassifiedFailure() {
        val rnd = Random(7)
        val pieces = arrayOf("HTTP/1.1 ", "200", " OK", "\r\n", "\n", ": ", "Location", "Content-Type", "text/html", "\u0000", "ÿ", "\t",
            " ", "HTTP/1.0 ", "302", "a".repeat(50), "\r\n\r\n", "HTTP/1.1 100 Continue\r\n\r\n", "Retry-After: 5\r\n")
        for (i in 0 until 150) {
            val sb = StringBuilder()
            val n = 1 + rnd.nextInt(12)
            for (k in 0 until n) sb.append(pieces[rnd.nextInt(pieces.size)])
            val raw = sb.toString()
            val s = serve { sock, _ -> write(sock, raw) }
            try {
                val r = client(2000).request(if (i % 2 == 0) "HEAD" else "GET", uri(s, "/"), null)
                assertTrue(r.status >= 200 && r.status <= 599)
                assertTrue(r.bodyBytes <= 64 * 1024)
            } catch (f: SafeHttp.HttpFailure) {
                assertTrue(f.kind == SafeHttp.Kind.BAD_RESPONSE || f.kind == SafeHttp.Kind.TIMEOUT || f.kind == SafeHttp.Kind.RESET,
                    "予想外の分類 " + f.kind + " for " + raw)
            }
            s.close()
        }
    }

    @Test
    fun failureMessagesCarryNoDetails() {
        val f = SafeHttp.HttpFailure(SafeHttp.Kind.TLS)
        assertEquals("TLS", f.message)
        assertNull(f.cause)
    }

    // ---- 接続先のアドレス ----

    @Test
    fun connectsOnlyWhenEveryAddressIsPublic() {
        val connects = AtomicInteger()
        val o = SafeHttp.Options()
        o.resolver = SafeHttp.fixedResolver(mapOf(
            "mixed.example" to arrayOf(InetAddress.getByName("8.8.8.8"), InetAddress.getByName("127.0.0.1")),
            "private.example" to arrayOf(InetAddress.getByName("192.168.0.1")),
            "meta.example" to arrayOf(InetAddress.getByName("169.254.169.254")),
            "mapped.example" to arrayOf(InetAddress.getByName("::ffff:127.0.0.1")),
            "mappedhex.example" to arrayOf(InetAddress.getByName("::ffff:7f00:1")),
            "teredo.example" to arrayOf(InetAddress.getByName("2001:0:4136:e378:8000:63bf:3fff:fdd2")),
            "nat64.example" to arrayOf(InetAddress.getByName("64:ff9b::c0a8:1")),
            "sixtofour.example" to arrayOf(InetAddress.getByName("2002:c0a8:0101::1")),
            "ula.example" to arrayOf(InetAddress.getByName("fd00::1")),
            "docker.example" to arrayOf(InetAddress.getByName("172.17.0.1"))))
        o.connector = SafeHttp.Connector { _, _, _ ->
            connects.incrementAndGet()
            throw ConnectException("Connection refused")
        }
        val http = SafeHttp(o)
        for (host in listOf("mixed", "private", "meta", "mapped", "mappedhex", "teredo", "nat64", "sixtofour", "ula", "docker")) {
            assertEquals(SafeHttp.Kind.UNSAFE_ADDRESS, failureOf(http, URI.create("http://$host.example/x"), "HEAD"), host)
        }
        assertEquals(0, connects.get(), "公開でないアドレスを含むホストには、接続を試みない")
    }

    @Test
    fun ipLiteralHostsAreCheckedToo() {
        val o = SafeHttp.Options()
        o.connector = SafeHttp.Connector { _, _, _ -> throw AssertionError("must not connect") }
        val http = SafeHttp(o)
        for (u in listOf("http://[::1]/", "http://[fe80::1]/", "http://[::ffff:127.0.0.1]/", "http://127.0.0.1/", "http://10.0.0.1/")) {
            assertEquals(SafeHttp.Kind.UNSAFE_ADDRESS, failureOf(http, URI.create(u), "HEAD"), u)
        }
    }

    @Test
    fun connectsOnlyToTheValidatedAddressAndResolvesOncePerHop() {
        // DNS rebinding: 1 回目は公開アドレス、2 回目は内部のアドレスを返す名前。検証したアドレスにだけつなぎ、引き直さない
        val lookups = AtomicInteger()
        val opened = ArrayList<InetAddress>()
        val o = SafeHttp.Options()
        o.resolver = SafeHttp.Resolver { _ ->
            val n = lookups.incrementAndGet()
            arrayOf(InetAddress.getByName(if (n == 1) "8.8.8.8" else "127.0.0.1"))
        }
        o.connector = SafeHttp.Connector { a, _, _ ->
            opened.add(a)
            throw ConnectException("Connection refused")
        }
        assertEquals(SafeHttp.Kind.CONNECT_REFUSED, failureOf(SafeHttp(o), URI.create("http://rebind.example/x"), "HEAD"))
        assertEquals(1, lookups.get(), "検証と接続のあいだで名前を引き直さない")
        assertEquals(listOf(InetAddress.getByName("8.8.8.8")), opened)
    }

    @Test
    fun resolutionFailuresAreClassified() {
        val o = SafeHttp.Options()
        o.resolver = SafeHttp.Resolver { host -> throw UnknownHostException(host) }
        assertEquals(SafeHttp.Kind.DNS, failureOf(SafeHttp(o), URI.create("http://nxdomain.example/"), "HEAD"))
    }

    @Test
    fun connectionRefusedIsClassified() {
        val port = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { tmp -> tmp.localPort }
        assertEquals(SafeHttp.Kind.CONNECT_REFUSED, failureOf(client(5000), URI.create("http://site.example:$port/"), "HEAD"))
    }

    @Test
    fun connectionResetIsClassified() {
        val s = serve { sock, _ ->
            sock.setSoLinger(true, 0)
            sock.close()
        }
        val k = failureOf(client(5000), uri(s, "/"), "HEAD")
        assertTrue(k == SafeHttp.Kind.RESET || k == SafeHttp.Kind.BAD_RESPONSE, k.toString())
    }

    @Test
    fun rejectsBadCallsWithoutConnecting() {
        val http = client(1000)
        assertEquals(SafeHttp.Kind.BAD_REQUEST, failureOf(http, URI.create("ftp://site.example/"), "HEAD"))
        assertEquals(SafeHttp.Kind.BAD_REQUEST, failureOf(http, URI.create("file:///etc/passwd"), "GET"))
        assertEquals(SafeHttp.Kind.BAD_REQUEST, failureOf(http, URI.create("http://site.example/"), "POST"))
    }

    // ---- TLS ----

    @Test
    fun tlsRejectsSelfSignedExpiredAndMismatchedCertificates(@TempDir dir: Path) {
        val tls = TestTls.create(dir, false)
        val server = tls.startServer { ex ->
            ex.sendResponseHeaders(200, -1)
            ex.close()
        }
        try {
            val port = server.address.port
            val table = mapOf(
                "localhost" to arrayOf(InetAddress.getLoopbackAddress()),
                "wrong.example" to arrayOf(InetAddress.getLoopbackAddress()))

            // 1. 自己署名の証明書は、既定のトラストストアでは信用されない
            val def = SafeHttp.Options()
            def.allowNonPublicForTests = true
            def.resolver = SafeHttp.fixedResolver(table)
            assertEquals(SafeHttp.Kind.TLS, failureOf(SafeHttp(def), URI.create("https://localhost:$port/"), "HEAD"))

            // 2. 証明書を信用するクライアントなら、正しいホスト名では成功する (対照)
            val trusting = SafeHttp.Options()
            trusting.allowNonPublicForTests = true
            trusting.resolver = SafeHttp.fixedResolver(table)
            trusting.sslContext = tls.trustingClientContext
            assertEquals(200, SafeHttp(trusting).request("HEAD", URI.create("https://localhost:$port/"), null).status)

            // 3. 信用していても、ホスト名が証明書と合わなければ失敗する (endpoint identification)
            assertEquals(SafeHttp.Kind.TLS, failureOf(SafeHttp(trusting), URI.create("https://wrong.example:$port/"), "HEAD"))
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun tlsRejectsExpiredCertificate(@TempDir dir: Path) {
        val tls = TestTls.create(dir, true)
        val server = tls.startServer { ex ->
            ex.sendResponseHeaders(200, -1)
            ex.close()
        }
        try {
            val o = SafeHttp.Options()
            o.allowNonPublicForTests = true
            o.resolver = SafeHttp.fixedResolver(mapOf("localhost" to arrayOf(InetAddress.getLoopbackAddress())))
            o.sslContext = tls.trustingClientContext // 信用していても、期限切れは失敗する
            assertEquals(SafeHttp.Kind.TLS, failureOf(SafeHttp(o), URI.create("https://localhost:" + server.address.port + "/"), "HEAD"))
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun sourceNeverDisablesCertificateOrHostnameChecks() {
        val src = Files.readString(Path.of("src/main/kotlin/net/torosaba/marumasign/companion/SafeHttp.kt"), StandardCharsets.UTF_8)
        for (forbidden in listOf("X509TrustManager", "HostnameVerifier", "TrustAll", "checkServerTrusted", "setDefaultHostnameVerifier",
            "HttpURLConnection", "HttpClient", "Accept-Encoding", "Cookie", "Referer", "Authorization")) {
            assertFalse(src.replace(Regex("(?s)/\\*.*?\\*/"), "").replace(Regex("(?m)^\\s*\\*.*\$"), "").replace(Regex("(?m)//.*\$"), "").contains(forbidden), forbidden)
        }
        assertTrue(src.contains("setEndpointIdentificationAlgorithm(\"HTTPS\")"))
        assertTrue(src.contains("TLSv1.3") && src.contains("TLSv1.2") && !src.contains("TLSv1.1") && !src.contains("SSLv3"))
        assertTrue(src.contains("setServerNames"))
    }

    companion object {
        @Throws(IOException::class)
        private fun write(s: Socket, text: String) {
            s.getOutputStream().write(text.toByteArray(StandardCharsets.ISO_8859_1))
            s.getOutputStream().flush()
        }

        /** ループバックにつなぐテスト用のクライアント (名前 site.example → 127.0.0.1) */
        private fun client(totalMillis: Int): SafeHttp {
            val o = SafeHttp.Options()
            o.allowNonPublicForTests = true
            o.totalTimeoutMillis = totalMillis
            o.connectTimeoutMillis = 2000
            o.resolver = SafeHttp.fixedResolver(mapOf("site.example" to arrayOf(InetAddress.getLoopbackAddress())))
            return SafeHttp(o)
        }

        private fun uri(s: RawServer, path: String): URI {
            return URI.create("http://site.example:" + s.port + path)
        }

        private fun failureOf(http: SafeHttp, u: URI, method: String): SafeHttp.Kind? {
            try {
                http.request(method, u, null)
            } catch (f: SafeHttp.HttpFailure) {
                return f.kind
            }
            return null
        }
    }
}
