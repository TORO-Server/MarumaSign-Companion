package net.torosaba.signmap;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/** 自前の HTTP クライアント (SafeHttp) の、安全面とふるまいの確認 */
class SafeHttpTest {

    /** 1 接続ごとに handler を呼ぶ、台本どおりに応答する生のサーバー */
    static final class RawServer implements AutoCloseable {
        interface Handler {
            void handle(Socket s, String request) throws Exception;
        }

        final ServerSocket server;
        final int port;
        final List<String> requests = new CopyOnWriteArrayList<>();
        final AtomicInteger connections = new AtomicInteger();
        private volatile boolean closed;

        RawServer(Handler h) throws IOException {
            server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
            port = server.getLocalPort();
            final Thread t = new Thread(() -> {
                while (!closed) {
                    try {
                        final Socket s = server.accept();
                        connections.incrementAndGet();
                        final Thread c = new Thread(() -> {
                            try (s) {
                                final String req = readRequest(s);
                                requests.add(req);
                                h.handle(s, req);
                            } catch (Exception e) {
                                // クライアントが切ったなど
                            }
                        });
                        c.setDaemon(true);
                        c.start();
                    } catch (IOException e) {
                        return;
                    }
                }
            });
            t.setDaemon(true);
            t.start();
        }

        static String readRequest(Socket s) throws IOException {
            final InputStream in = s.getInputStream();
            final ByteArrayOutputStream b = new ByteArrayOutputStream();
            int c;
            while ((c = in.read()) >= 0) {
                b.write(c);
                final byte[] a = b.toByteArray();
                final int n = a.length;
                if (n >= 4 && a[n - 4] == '\r' && a[n - 3] == '\n' && a[n - 2] == '\r' && a[n - 1] == '\n') break;
                if (n > 65536) break;
            }
            return b.toString(StandardCharsets.ISO_8859_1);
        }

        @Override
        public void close() throws IOException {
            closed = true;
            server.close();
        }
    }

    private final List<RawServer> servers = new ArrayList<>();

    @AfterEach
    void closeServers() throws IOException {
        for (RawServer s : servers) s.close();
    }

    private RawServer serve(RawServer.Handler h) throws IOException {
        final RawServer s = new RawServer(h);
        servers.add(s);
        return s;
    }

    private static void write(Socket s, String text) throws IOException {
        s.getOutputStream().write(text.getBytes(StandardCharsets.ISO_8859_1));
        s.getOutputStream().flush();
    }

    /** ループバックにつなぐテスト用のクライアント (名前 site.example → 127.0.0.1) */
    private static SafeHttp client(int totalMillis) {
        final SafeHttp.Options o = new SafeHttp.Options();
        o.allowNonPublicForTests = true;
        o.totalTimeoutMillis = totalMillis;
        o.connectTimeoutMillis = 2000;
        o.resolver = SafeHttp.fixedResolver(Map.of("site.example", new InetAddress[]{InetAddress.getLoopbackAddress()}));
        return new SafeHttp(o);
    }

    private static URI uri(RawServer s, String path) {
        return URI.create("http://site.example:" + s.port + path);
    }

    private static SafeHttp.Kind failureOf(SafeHttp http, URI u, String method) {
        try {
            http.request(method, u, null);
        } catch (SafeHttp.HttpFailure f) {
            return f.kind;
        }
        return null;
    }

    // ---- ふつうの応答 ----

    @Test
    void headAndGetReadStatusAndHeaders() throws Exception {
        final RawServer s = serve((sock, req) -> write(sock, "HTTP/1.1 200 OK\r\nContent-Type: Image/PNG; charset=x\r\nContent-Length: 5\r\n\r\nhello"));
        final SafeHttp.Response head = client(5000).request("HEAD", uri(s, "/a.png"), null);
        assertEquals(200, head.status());
        assertEquals("image/png", head.contentType());
        assertEquals(0, head.bodyBytes(), "HEAD は本文を読まない");
        final SafeHttp.Response get = client(5000).request("GET", uri(s, "/a.png"), "bytes=0-1023");
        assertEquals(5, get.bodyBytes());
        assertTrue(s.requests.get(1).startsWith("GET /a.png HTTP/1.1\r\n"));
        assertTrue(s.requests.get(1).contains("Range: bytes=0-1023\r\n"));
    }

    @Test
    void sendsOnlyMinimalHeadersNeverCookiesOrReferer() throws Exception {
        final RawServer s = serve((sock, req) -> write(sock, "HTTP/1.1 204 No Content\r\n\r\n"));
        client(5000).request("GET", uri(s, "/x?a=1"), null);
        final String req = s.requests.get(0).toLowerCase();
        assertTrue(req.startsWith("get /x?a=1 http/1.1\r\n"));
        assertTrue(req.contains("host: site.example:" + s.port + "\r\n"));
        assertTrue(req.contains("user-agent: bluemapmarumasign-linkcheck/1.0\r\n"));
        assertTrue(req.contains("connection: close\r\n"));
        for (String forbidden : List.of("cookie", "referer", "authorization", "accept-encoding", "origin")) {
            assertFalse(req.contains(forbidden), forbidden);
        }
    }

    @Test
    void doesNotFollowRedirectsAndReturnsLocation() throws Exception {
        final RawServer s = serve((sock, req) -> write(sock, "HTTP/1.1 302 Found\r\nLocation: http://other.example/x\r\n\r\n"));
        final SafeHttp.Response r = client(5000).request("HEAD", uri(s, "/"), null);
        assertEquals(302, r.status());
        assertEquals("http://other.example/x", r.location());
        assertEquals(1, s.connections.get());
    }

    @Test
    void skipsInformationalResponses() throws Exception {
        final RawServer s = serve((sock, req) -> write(sock, "HTTP/1.1 100 Continue\r\n\r\nHTTP/1.1 200 OK\r\n\r\n"));
        assertEquals(200, client(5000).request("HEAD", uri(s, "/"), null).status());
    }

    // ---- 資源の上限 ----

    @Test
    void bodyIsReadOnlyUpToTheLimitAndThenDropped() throws Exception {
        final RawServer s = serve((sock, req) -> {
            write(sock, "HTTP/1.1 200 OK\r\n\r\n");
            final byte[] chunk = new byte[8192];
            for (int i = 0; i < 1024; i++) sock.getOutputStream().write(chunk); // 8 MB
        });
        final SafeHttp.Response r = client(10_000).request("GET", uri(s, "/big"), null);
        assertEquals(200, r.status());
        assertEquals(64 * 1024, r.bodyBytes(), "本文は 64 KB までしか読まない");
    }

    @Test
    void compressedBodyIsNeverInflated() throws Exception {
        // 100 MB のゼロを gzip にした小さな本文 (圧縮爆弾)。展開せず、送られたバイトを読んで捨てるだけ
        final ByteArrayOutputStream bomb = new ByteArrayOutputStream();
        try (GZIPOutputStream gz = new GZIPOutputStream(bomb)) {
            final byte[] zeros = new byte[1 << 20];
            for (int i = 0; i < 100; i++) gz.write(zeros);
        }
        final byte[] body = bomb.toByteArray();
        assertTrue(body.length < 200_000);
        final RawServer s = serve((sock, req) -> {
            write(sock, "HTTP/1.1 200 OK\r\nContent-Encoding: gzip\r\nContent-Length: " + body.length + "\r\n\r\n");
            sock.getOutputStream().write(body);
        });
        final SafeHttp.Response r = client(10_000).request("GET", uri(s, "/bomb"), null);
        assertEquals(200, r.status());
        assertTrue(r.bodyBytes() <= 64 * 1024, "読んだのは、送られた (圧縮された) バイトの先頭だけ: " + r.bodyBytes());
    }

    @Test
    void oversizedHeaderLinesAndTooManyHeadersAreRejected() throws Exception {
        final RawServer longLine = serve((sock, req) -> write(sock, "HTTP/1.1 200 OK\r\nX-Long: " + "a".repeat(9000) + "\r\n\r\n"));
        assertEquals(SafeHttp.Kind.BAD_RESPONSE, failureOf(client(5000), uri(longLine, "/"), "HEAD"));

        final RawServer many = serve((sock, req) -> {
            final StringBuilder sb = new StringBuilder("HTTP/1.1 200 OK\r\n");
            for (int i = 0; i < 500; i++) sb.append("X-").append(i).append(": v\r\n");
            write(sock, sb.append("\r\n").toString());
        });
        assertEquals(SafeHttp.Kind.BAD_RESPONSE, failureOf(client(5000), uri(many, "/"), "HEAD"));

        final RawServer huge = serve((sock, req) -> {
            final StringBuilder sb = new StringBuilder("HTTP/1.1 200 OK\r\n");
            for (int i = 0; i < 90; i++) sb.append("X-").append(i).append(": ").append("b".repeat(1000)).append("\r\n");
            write(sock, sb.append("\r\n").toString());
        });
        assertEquals(SafeHttp.Kind.BAD_RESPONSE, failureOf(client(5000), uri(huge, "/"), "HEAD"), "合計の大きさの上限");
    }

    @Test
    void endlessHeadersStopAtTheLimit() throws Exception {
        final RawServer s = serve((sock, req) -> {
            write(sock, "HTTP/1.1 200 OK\r\n");
            for (int i = 0; i < 100_000; i++) write(sock, "X-Endless-" + i + ": " + "c".repeat(100) + "\r\n");
        });
        final long t0 = System.nanoTime();
        assertEquals(SafeHttp.Kind.BAD_RESPONSE, failureOf(client(10_000), uri(s, "/"), "HEAD"));
        assertTrue((System.nanoTime() - t0) / 1_000_000 < 5000);
    }

    @Test
    void slowDripResponseIsCutByTheOverallDeadline() throws Exception {
        // 100 ms ごとに 1 バイトだけ送る。1 回の read のタイムアウトには引っかからないが、全体の期限で切れる
        final RawServer s = serve((sock, req) -> {
            final String line = "HTTP/1.1 200 OK\r\nX-Slow: " + "z".repeat(200) + "\r\n\r\n";
            for (int i = 0; i < line.length(); i++) {
                sock.getOutputStream().write(line.charAt(i));
                sock.getOutputStream().flush();
                Thread.sleep(100);
            }
        });
        final long t0 = System.nanoTime();
        assertEquals(SafeHttp.Kind.TIMEOUT, failureOf(client(1500), uri(s, "/"), "HEAD"));
        final long ms = (System.nanoTime() - t0) / 1_000_000;
        assertTrue(ms >= 1200 && ms < 4000, "期限 (1.5 秒) で切れた: " + ms + " ms");
    }

    @Test
    void slowDripBodyStillEndsAtTheDeadline() throws Exception {
        final RawServer s = serve((sock, req) -> {
            write(sock, "HTTP/1.1 200 OK\r\n\r\n");
            for (int i = 0; i < 1000; i++) {
                sock.getOutputStream().write('x');
                sock.getOutputStream().flush();
                Thread.sleep(50);
            }
        });
        final long t0 = System.nanoTime();
        final SafeHttp.Response r = client(1500).request("GET", uri(s, "/"), null);
        assertEquals(200, r.status(), "状態は分かっているので、本文が切れても結果は返る");
        assertTrue((System.nanoTime() - t0) / 1_000_000 < 4000);
    }

    @Test
    void serverThatNeverRespondsTimesOut() throws Exception {
        final RawServer s = serve((sock, req) -> Thread.sleep(10_000));
        final long t0 = System.nanoTime();
        assertEquals(SafeHttp.Kind.TIMEOUT, failureOf(client(1000), uri(s, "/"), "HEAD"));
        assertTrue((System.nanoTime() - t0) / 1_000_000 < 3500);
    }

    // ---- 不正な応答 ----

    @Test
    void malformedResponsesAreBadResponse() throws Exception {
        for (String raw : List.of(
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
            final RawServer s = serve((sock, req) -> write(sock, raw));
            assertEquals(SafeHttp.Kind.BAD_RESPONSE, failureOf(client(3000), uri(s, "/"), "HEAD"), raw.replace("\r\n", "|"));
        }
    }

    @Test
    void fuzzedResponsesNeverEscapeAsAnythingButAClassifiedFailure() throws Exception {
        final Random rnd = new Random(7);
        final String[] pieces = {"HTTP/1.1 ", "200", " OK", "\r\n", "\n", ": ", "Location", "Content-Type", "text/html", "\u0000", "ÿ", "\t",
                " ", "HTTP/1.0 ", "302", "a".repeat(50), "\r\n\r\n", "HTTP/1.1 100 Continue\r\n\r\n", "Retry-After: 5\r\n"};
        for (int i = 0; i < 150; i++) {
            final StringBuilder sb = new StringBuilder();
            final int n = 1 + rnd.nextInt(12);
            for (int k = 0; k < n; k++) sb.append(pieces[rnd.nextInt(pieces.length)]);
            final String raw = sb.toString();
            final RawServer s = serve((sock, req) -> write(sock, raw));
            try {
                final SafeHttp.Response r = client(2000).request(i % 2 == 0 ? "HEAD" : "GET", uri(s, "/"), null);
                assertTrue(r.status() >= 200 && r.status() <= 599);
                assertTrue(r.bodyBytes() <= 64 * 1024);
            } catch (SafeHttp.HttpFailure f) {
                assertTrue(f.kind == SafeHttp.Kind.BAD_RESPONSE || f.kind == SafeHttp.Kind.TIMEOUT || f.kind == SafeHttp.Kind.RESET,
                        "予想外の分類 " + f.kind + " for " + raw);
            }
            s.close();
        }
    }

    @Test
    void failureMessagesCarryNoDetails() {
        final SafeHttp.HttpFailure f = new SafeHttp.HttpFailure(SafeHttp.Kind.TLS);
        assertEquals("TLS", f.getMessage());
        assertNull(f.getCause());
    }

    // ---- 接続先のアドレス ----

    @Test
    void connectsOnlyWhenEveryAddressIsPublic() throws Exception {
        final AtomicInteger connects = new AtomicInteger();
        final SafeHttp.Options o = new SafeHttp.Options();
        o.resolver = SafeHttp.fixedResolver(Map.of(
                "mixed.example", new InetAddress[]{InetAddress.getByName("8.8.8.8"), InetAddress.getByName("127.0.0.1")},
                "private.example", new InetAddress[]{InetAddress.getByName("192.168.0.1")},
                "meta.example", new InetAddress[]{InetAddress.getByName("169.254.169.254")},
                "mapped.example", new InetAddress[]{InetAddress.getByName("::ffff:127.0.0.1")},
                "mappedhex.example", new InetAddress[]{InetAddress.getByName("::ffff:7f00:1")},
                "teredo.example", new InetAddress[]{InetAddress.getByName("2001:0:4136:e378:8000:63bf:3fff:fdd2")},
                "nat64.example", new InetAddress[]{InetAddress.getByName("64:ff9b::c0a8:1")},
                "sixtofour.example", new InetAddress[]{InetAddress.getByName("2002:c0a8:0101::1")},
                "ula.example", new InetAddress[]{InetAddress.getByName("fd00::1")},
                "docker.example", new InetAddress[]{InetAddress.getByName("172.17.0.1")}));
        o.connector = (a, p, t) -> {
            connects.incrementAndGet();
            throw new ConnectException("Connection refused");
        };
        final SafeHttp http = new SafeHttp(o);
        for (String host : List.of("mixed", "private", "meta", "mapped", "mappedhex", "teredo", "nat64", "sixtofour", "ula", "docker")) {
            assertEquals(SafeHttp.Kind.UNSAFE_ADDRESS, failureOf(http, URI.create("http://" + host + ".example/x"), "HEAD"), host);
        }
        assertEquals(0, connects.get(), "公開でないアドレスを含むホストには、接続を試みない");
    }

    @Test
    void ipLiteralHostsAreCheckedToo() throws Exception {
        final SafeHttp.Options o = new SafeHttp.Options();
        o.connector = (a, p, t) -> {
            throw new AssertionError("must not connect");
        };
        final SafeHttp http = new SafeHttp(o);
        for (String u : List.of("http://[::1]/", "http://[fe80::1]/", "http://[::ffff:127.0.0.1]/", "http://127.0.0.1/", "http://10.0.0.1/")) {
            assertEquals(SafeHttp.Kind.UNSAFE_ADDRESS, failureOf(http, URI.create(u), "HEAD"), u);
        }
    }

    @Test
    void connectsOnlyToTheValidatedAddressAndResolvesOncePerHop() throws Exception {
        // DNS rebinding: 1 回目は公開アドレス、2 回目は内部のアドレスを返す名前。検証したアドレスにだけつなぎ、引き直さない
        final AtomicInteger lookups = new AtomicInteger();
        final List<InetAddress> opened = new ArrayList<>();
        final SafeHttp.Options o = new SafeHttp.Options();
        o.resolver = host -> {
            final int n = lookups.incrementAndGet();
            return new InetAddress[]{InetAddress.getByName(n == 1 ? "8.8.8.8" : "127.0.0.1")};
        };
        o.connector = (a, p, t) -> {
            opened.add(a);
            throw new ConnectException("Connection refused");
        };
        assertEquals(SafeHttp.Kind.CONNECT_REFUSED, failureOf(new SafeHttp(o), URI.create("http://rebind.example/x"), "HEAD"));
        assertEquals(1, lookups.get(), "検証と接続のあいだで名前を引き直さない");
        assertEquals(List.of(InetAddress.getByName("8.8.8.8")), opened);
    }

    @Test
    void resolutionFailuresAreClassified() {
        final SafeHttp.Options o = new SafeHttp.Options();
        o.resolver = host -> {
            throw new UnknownHostException(host);
        };
        assertEquals(SafeHttp.Kind.DNS, failureOf(new SafeHttp(o), URI.create("http://nxdomain.example/"), "HEAD"));
    }

    @Test
    void connectionRefusedIsClassified() throws Exception {
        final int port;
        try (ServerSocket tmp = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            port = tmp.getLocalPort();
        }
        assertEquals(SafeHttp.Kind.CONNECT_REFUSED, failureOf(client(5000), URI.create("http://site.example:" + port + "/"), "HEAD"));
    }

    @Test
    void connectionResetIsClassified() throws Exception {
        final RawServer s = serve((sock, req) -> {
            sock.setSoLinger(true, 0);
            sock.close();
        });
        final SafeHttp.Kind k = failureOf(client(5000), uri(s, "/"), "HEAD");
        assertTrue(k == SafeHttp.Kind.RESET || k == SafeHttp.Kind.BAD_RESPONSE, String.valueOf(k));
    }

    @Test
    void rejectsBadCallsWithoutConnecting() {
        final SafeHttp http = client(1000);
        assertEquals(SafeHttp.Kind.BAD_REQUEST, failureOf(http, URI.create("ftp://site.example/"), "HEAD"));
        assertEquals(SafeHttp.Kind.BAD_REQUEST, failureOf(http, URI.create("file:///etc/passwd"), "GET"));
        assertEquals(SafeHttp.Kind.BAD_REQUEST, failureOf(http, URI.create("http://site.example/"), "POST"));
    }

    // ---- TLS ----

    @Test
    void tlsRejectsSelfSignedExpiredAndMismatchedCertificates(@TempDir Path dir) throws Exception {
        final TestTls tls = TestTls.create(dir, false);
        final var server = tls.startServer(ex -> {
            ex.sendResponseHeaders(200, -1);
            ex.close();
        });
        try {
            final int port = server.getAddress().getPort();
            final Map<String, InetAddress[]> table = Map.of(
                    "localhost", new InetAddress[]{InetAddress.getLoopbackAddress()},
                    "wrong.example", new InetAddress[]{InetAddress.getLoopbackAddress()});

            // 1. 自己署名の証明書は、既定のトラストストアでは信用されない
            final SafeHttp.Options def = new SafeHttp.Options();
            def.allowNonPublicForTests = true;
            def.resolver = SafeHttp.fixedResolver(table);
            assertEquals(SafeHttp.Kind.TLS, failureOf(new SafeHttp(def), URI.create("https://localhost:" + port + "/"), "HEAD"));

            // 2. 証明書を信用するクライアントなら、正しいホスト名では成功する (対照)
            final SafeHttp.Options trusting = new SafeHttp.Options();
            trusting.allowNonPublicForTests = true;
            trusting.resolver = SafeHttp.fixedResolver(table);
            trusting.sslContext = tls.trustingClientContext;
            assertEquals(200, new SafeHttp(trusting).request("HEAD", URI.create("https://localhost:" + port + "/"), null).status());

            // 3. 信用していても、ホスト名が証明書と合わなければ失敗する (endpoint identification)
            assertEquals(SafeHttp.Kind.TLS, failureOf(new SafeHttp(trusting), URI.create("https://wrong.example:" + port + "/"), "HEAD"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void tlsRejectsExpiredCertificate(@TempDir Path dir) throws Exception {
        final TestTls tls = TestTls.create(dir, true);
        final var server = tls.startServer(ex -> {
            ex.sendResponseHeaders(200, -1);
            ex.close();
        });
        try {
            final SafeHttp.Options o = new SafeHttp.Options();
            o.allowNonPublicForTests = true;
            o.resolver = SafeHttp.fixedResolver(Map.of("localhost", new InetAddress[]{InetAddress.getLoopbackAddress()}));
            o.sslContext = tls.trustingClientContext; // 信用していても、期限切れは失敗する
            assertEquals(SafeHttp.Kind.TLS, failureOf(new SafeHttp(o), URI.create("https://localhost:" + server.getAddress().getPort() + "/"), "HEAD"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void sourceNeverDisablesCertificateOrHostnameChecks() throws Exception {
        final String src = java.nio.file.Files.readString(Path.of("src/main/java/net/torosaba/signmap/SafeHttp.java"), StandardCharsets.UTF_8);
        for (String forbidden : List.of("X509TrustManager", "HostnameVerifier", "TrustAll", "checkServerTrusted", "setDefaultHostnameVerifier",
                "HttpURLConnection", "HttpClient", "Accept-Encoding", "Cookie", "Referer", "Authorization")) {
            assertFalse(src.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("(?m)^\\s*\\*.*$", "").replaceAll("(?m)//.*$", "").contains(forbidden), forbidden);
        }
        assertTrue(src.contains("setEndpointIdentificationAlgorithm(\"HTTPS\")"));
        assertTrue(src.contains("TLSv1.3") && src.contains("TLSv1.2") && !src.contains("TLSv1.1") && !src.contains("SSLv3"));
        assertTrue(src.contains("setServerNames"));
    }
}
