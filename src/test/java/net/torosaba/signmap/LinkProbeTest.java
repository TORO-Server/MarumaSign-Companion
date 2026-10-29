package net.torosaba.signmap;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/** リンク切れの判定 (ローカルのサーバーに対して、実際に HEAD / GET を送って確かめる) */
class LinkProbeTest {

    interface Route {
        void handle(String method, HttpExchange ex) throws IOException;
    }

    /** ループバックの HTTP サーバー。経路ごとに応答を決め、受けたリクエストを覚える */
    static final class Site {
        final HttpServer server;
        final int port;
        final List<String> hits = new CopyOnWriteArrayList<>();

        Site() throws IOException {
            server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            port = server.getAddress().getPort();
            server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool(r -> {
                final Thread t = new Thread(r);
                t.setDaemon(true);
                return t;
            }));
            server.start();
        }

        Site on(String path, Route r) {
            server.createContext(path, ex -> {
                hits.add(ex.getRequestMethod() + " " + ex.getRequestURI().getPath());
                try {
                    r.handle(ex.getRequestMethod(), ex);
                } finally {
                    ex.close();
                }
            });
            return this;
        }

        long count(String prefix) {
            return hits.stream().filter(h -> h.startsWith(prefix)).count();
        }
    }

    static void reply(HttpExchange ex, int code, String type, String location) throws IOException {
        if (type != null) ex.getResponseHeaders().add("Content-Type", type);
        if (location != null) ex.getResponseHeaders().add("Location", location);
        ex.sendResponseHeaders(code, -1);
    }

    private final List<Site> sites = new ArrayList<>();

    @AfterEach
    void stopSites() {
        for (Site s : sites) s.server.stop(0);
    }

    private Site site() throws IOException {
        final Site s = new Site();
        sites.add(s);
        return s;
    }

    private static final InetAddress LOOPBACK = InetAddress.getLoopbackAddress();

    private final AtomicLong now = new AtomicLong(1_000_000_000L);
    private final List<Long> sleeps = new CopyOnWriteArrayList<>();

    private LinkSettings cfg() {
        final LinkSettings c = new LinkSettings();
        c.hostMinIntervalSeconds = 0;
        c.hostRules = new ArrayList<>();
        return c;
    }

    private LinkProbe probe(LinkSettings cfg, int totalMillis, Set<Integer> ports, Map<String, InetAddress[]> table) {
        final SafeHttp.Options o = new SafeHttp.Options();
        o.allowNonPublicForTests = true;
        o.totalTimeoutMillis = totalMillis;
        o.connectTimeoutMillis = 2000;
        o.resolver = SafeHttp.fixedResolver(table);
        return new LinkProbe(new SafeHttp(o), new UrlFilter(List.of()), cfg, new HostThrottle(cfg), ports, now::get, sleeps::add);
    }

    private LinkProbe probe(Site... s) {
        final Set<Integer> ports = new java.util.HashSet<>();
        for (Site x : s) ports.add(x.port);
        return probe(cfg(), 5000, ports, Map.of(
                "site.example", new InetAddress[]{LOOPBACK},
                "other.example", new InetAddress[]{LOOPBACK},
                "third.example", new InetAddress[]{LOOPBACK}));
    }

    private static String url(Site s, String path) {
        return "http://site.example:" + s.port + path;
    }

    private static void assertResult(LinkProbe.Result r, LinkProbe.Verdict verdict, LinkReason reason) {
        assertEquals(verdict, r.verdict(), r.toString());
        assertEquals(reason, r.reason(), r.toString());
    }

    // ---- 基本 ----

    @Test
    void okWhenHeadIsOk() throws Exception {
        final Site s = site().on("/a.png", (m, ex) -> reply(ex, 200, "image/png", null));
        final LinkProbe.Result r = probe(s).probe(url(s, "/a.png"));
        assertResult(r, LinkProbe.Verdict.OK, LinkReason.OK);
        assertEquals(200, r.httpCode());
        assertEquals(0, s.count("GET"), "HEAD で足りるなら GET は送らない");
    }

    @Test
    void notFoundAndGoneAreBroken() throws Exception {
        final Site s = site().on("/nf", (m, ex) -> reply(ex, 404, "text/html", null)).on("/gone", (m, ex) -> reply(ex, 410, null, null));
        assertResult(probe(s).probe(url(s, "/nf")), LinkProbe.Verdict.BROKEN, LinkReason.HTTP_404);
        assertResult(probe(s).probe(url(s, "/gone")), LinkProbe.Verdict.BROKEN, LinkReason.HTTP_410);
        assertEquals(1, s.count("GET /nf"), "404 は GET で確かめ直す");
        assertEquals(0, s.count("GET /gone"), "410 はそのまま");
    }

    @Test
    void headNotAllowedFallsBackToRangeGet() throws Exception {
        final Site s = site().on("/x.png", (m, ex) -> {
            if (m.equals("HEAD")) reply(ex, 405, null, null);
            else {
                assertEquals("bytes=0-1023", ex.getRequestHeaders().getFirst("Range"));
                reply(ex, 206, "image/png", null);
            }
        });
        assertResult(probe(s).probe(url(s, "/x.png")), LinkProbe.Verdict.OK, LinkReason.OK);
        assertEquals(1, s.count("GET /x.png"));
    }

    @Test
    void headNotFoundButGetOkIsOk() throws Exception {
        final Site s = site().on("/y.png", (m, ex) -> reply(ex, m.equals("HEAD") ? 404 : 200, "image/png", null));
        assertResult(probe(s).probe(url(s, "/y.png")), LinkProbe.Verdict.OK, LinkReason.OK);
    }

    @Test
    void headAndGetBothNotFoundIsBroken() throws Exception {
        final Site s = site().on("/z.png", (m, ex) -> reply(ex, 404, null, null));
        assertResult(probe(s).probe(url(s, "/z.png")), LinkProbe.Verdict.BROKEN, LinkReason.HTTP_404);
    }

    @Test
    void forbiddenIsUnknownUnlessConfigured() throws Exception {
        final Site s = site().on("/f", (m, ex) -> reply(ex, 403, null, null)).on("/u", (m, ex) -> reply(ex, 401, null, null));
        LinkProbe.Result r = probe(s).probe(url(s, "/f"));
        assertResult(r, LinkProbe.Verdict.UNKNOWN, LinkReason.ACCESS_DENIED);
        assertFalse(r.countsAsFailure());
        assertResult(probe(s).probe(url(s, "/u")), LinkProbe.Verdict.UNKNOWN, LinkReason.ACCESS_DENIED);
        final LinkSettings c = cfg();
        c.treat403AsBroken = true;
        final LinkProbe strict = probe(c, 5000, Set.of(s.port), Map.of("site.example", new InetAddress[]{LOOPBACK}));
        assertResult(strict.probe(url(s, "/f")), LinkProbe.Verdict.BROKEN, LinkReason.ACCESS_DENIED);
        assertResult(strict.probe(url(s, "/u")), LinkProbe.Verdict.UNKNOWN, LinkReason.ACCESS_DENIED);
    }

    @Test
    void serverErrorsAndOddStatusAreUnknownAndCount() throws Exception {
        final Site s = site().on("/e500", (m, ex) -> reply(ex, 500, null, null)).on("/e503", (m, ex) -> reply(ex, 503, null, null))
                .on("/e418", (m, ex) -> reply(ex, 418, null, null));
        LinkProbe.Result r = probe(s).probe(url(s, "/e500"));
        assertResult(r, LinkProbe.Verdict.UNKNOWN, LinkReason.SERVER_ERROR);
        assertTrue(r.countsAsFailure());
        assertResult(probe(s).probe(url(s, "/e503")), LinkProbe.Verdict.UNKNOWN, LinkReason.SERVER_ERROR);
        assertResult(probe(s).probe(url(s, "/e418")), LinkProbe.Verdict.UNKNOWN, LinkReason.UNEXPECTED_RESPONSE);
    }

    @Test
    void rateLimitedMakesTheHostWaitAndIsNotAFailure() throws Exception {
        final Site s = site().on("/r", (m, ex) -> {
            ex.getResponseHeaders().add("Retry-After", "120");
            reply(ex, 429, null, null);
        });
        final LinkSettings c = cfg();
        final HostThrottle throttle = new HostThrottle(c);
        final SafeHttp.Options o = new SafeHttp.Options();
        o.allowNonPublicForTests = true;
        o.resolver = SafeHttp.fixedResolver(Map.of("site.example", new InetAddress[]{LOOPBACK}));
        final LinkProbe p = new LinkProbe(new SafeHttp(o), new UrlFilter(List.of()), c, throttle, Set.of(s.port), now::get, sleeps::add);
        final LinkProbe.Result r = p.probe(url(s, "/r"));
        assertResult(r, LinkProbe.Verdict.UNKNOWN, LinkReason.RATE_LIMITED);
        assertFalse(r.countsAsFailure());
        assertEquals(120_000, r.retryAfterMillis());
        assertFalse(throttle.isReady("site.example", now.get() + 60_000), "Retry-After のあいだは、そのホストを待たせる");
        assertTrue(throttle.isReady("site.example", now.get() + 130_000));
        assertEquals(0, s.count("GET"), "429 のホストに、確かめ直しの GET は送らない");
    }

    @Test
    void timeoutIsUnknownAndCounts() throws Exception {
        final Site s = site().on("/slow", (m, ex) -> {
            try {
                Thread.sleep(3000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            reply(ex, 200, null, null);
        });
        final LinkSettings c = cfg();
        final LinkProbe p = probe(c, 1000, Set.of(s.port), Map.of("site.example", new InetAddress[]{LOOPBACK}));
        final long t0 = System.nanoTime();
        final LinkProbe.Result r = p.probe(url(s, "/slow"));
        assertResult(r, LinkProbe.Verdict.UNKNOWN, LinkReason.TIMEOUT);
        assertTrue(r.countsAsFailure());
        assertTrue((System.nanoTime() - t0) / 1_000_000 < 3500);
    }

    // ---- 転送 ----

    @Test
    void followsRedirects() throws Exception {
        final Site s = site()
                .on("/ok", (m, ex) -> reply(ex, 301, null, "/final.png"))
                .on("/final.png", (m, ex) -> reply(ex, 200, "image/png", null))
                .on("/dead", (m, ex) -> reply(ex, 302, null, "/missing.png"))
                .on("/missing.png", (m, ex) -> reply(ex, 404, null, null))
                .on("/abs", (m, ex) -> reply(ex, 307, null, "http://other.example:" + ((InetSocketAddress) ex.getLocalAddress()).getPort() + "/final.png"));
        assertResult(probe(s).probe(url(s, "/ok")), LinkProbe.Verdict.OK, LinkReason.OK);
        assertResult(probe(s).probe(url(s, "/dead")), LinkProbe.Verdict.BROKEN, LinkReason.HTTP_404);
        assertResult(probe(s).probe(url(s, "/abs")), LinkProbe.Verdict.OK, LinkReason.OK);
    }

    @Test
    void redirectLoopIsBroken() throws Exception {
        final Site s = site().on("/a", (m, ex) -> reply(ex, 302, null, "/b")).on("/b", (m, ex) -> reply(ex, 302, null, "/a"))
                .on("/self", (m, ex) -> reply(ex, 302, null, "/self"));
        assertResult(probe(s).probe(url(s, "/a")), LinkProbe.Verdict.BROKEN, LinkReason.REDIRECT_LOOP);
        assertResult(probe(s).probe(url(s, "/self")), LinkProbe.Verdict.BROKEN, LinkReason.REDIRECT_LOOP);
    }

    @Test
    void tooManyRedirectsStopsAtTheLimit() throws Exception {
        final Site s = site().on("/hop/", (m, ex) -> {
            final int n = Integer.parseInt(ex.getRequestURI().getPath().substring("/hop/".length()));
            reply(ex, 302, null, "/hop/" + (n + 1));
        });
        final LinkProbe.Result r = probe(s).probe(url(s, "/hop/0"));
        assertResult(r, LinkProbe.Verdict.UNKNOWN, LinkReason.REDIRECT_LIMIT);
        assertEquals(6, s.count("HEAD"), "最初の 1 回 + 転送 5 回まで");
    }

    @Test
    void unsafeRedirectTargetsAreNotFollowed() throws Exception {
        final Site s = site();
        for (String target : List.of("http://127.0.0.1:" + s.port + "/x", "http://localhost:" + s.port + "/x", "http://[::1]:" + s.port + "/x",
                "http://10.0.0.5/x", "http://192.168.1.1/x", "http://169.254.169.254/latest/meta-data", "http://2130706433/x", "http://0177.0.0.1/x",
                "http://0x7f.1/x", "file:///etc/passwd", "ftp://site.example/x", "javascript:alert(1)", "gopher://site.example/",
                "http://user@site.example/x", "http://site.example:22/x", "http://site.example:" + (s.port + 1) + "/x",
                "http://site.example/a b", "http://nas.local/x", "http://router/x")) {
            final String path = "/r" + Math.abs(target.hashCode());
            s.on(path, (m, ex) -> reply(ex, 302, null, target));
            final LinkProbe.Result r = probe(s).probe(url(s, path));
            assertResult(r, LinkProbe.Verdict.UNKNOWN, LinkReason.REDIRECT_UNSAFE);
            assertFalse(r.countsAsFailure());
        }
        // どこにもつないでいない (最初のサーバーへの HEAD だけ)
        assertEquals(s.hits.stream().filter(h -> h.startsWith("HEAD /r")).count(), s.hits.size());
    }

    @Test
    void redirectToNameThatResolvesToPrivateAddressIsNotConnected() throws Exception {
        final Site s = site().on("/start", (m, ex) -> reply(ex, 302, null, "http://internal.example:" + ((InetSocketAddress) ex.getLocalAddress()).getPort() + "/x"));
        final List<InetAddress> opened = new ArrayList<>();
        final SafeHttp.Options o = new SafeHttp.Options();
        o.resolver = SafeHttp.fixedResolver(Map.of("public.example", new InetAddress[]{InetAddress.getByName("8.8.8.8")},
                "internal.example", new InetAddress[]{InetAddress.getByName("10.0.0.5")}));
        o.allowSelfConnectForTests = true; // 公開に見えるアドレスへの接続を、ループバックのサーバーへ向け直しているため
        o.connector = (a, p, t) -> {
            opened.add(a);
            return new Socket(LOOPBACK, s.port);
        };
        final LinkSettings c = cfg();
        final LinkProbe p = new LinkProbe(new SafeHttp(o), new UrlFilter(List.of()), c, new HostThrottle(c), Set.of(s.port), now::get, sleeps::add);
        final LinkProbe.Result r = p.probe("http://public.example:" + s.port + "/start");
        assertResult(r, LinkProbe.Verdict.UNKNOWN, LinkReason.REDIRECT_UNSAFE);
        assertEquals(List.of(InetAddress.getByName("8.8.8.8")), opened, "内部のアドレスには、つなごうとしない");
    }

    @Test
    void hostThatResolvesToPrivateAddressIsNotChecked() throws Exception {
        final AtomicInteger connects = new AtomicInteger();
        final SafeHttp.Options o = new SafeHttp.Options();
        o.resolver = SafeHttp.fixedResolver(Map.of("evil.example", new InetAddress[]{InetAddress.getByName("127.0.0.1")}));
        o.connector = (a, p, t) -> {
            connects.incrementAndGet();
            throw new IOException();
        };
        final LinkSettings c = cfg();
        final LinkProbe p = new LinkProbe(new SafeHttp(o), new UrlFilter(List.of()), c, new HostThrottle(c), Set.of(80), now::get, sleeps::add);
        final LinkProbe.Result r = p.probe("http://evil.example/a.png");
        assertResult(r, LinkProbe.Verdict.UNKNOWN, LinkReason.UNSAFE_URL);
        assertEquals(0, connects.get());
    }

    @Test
    void httpsToHttpDowngradeIsJudgedButItsBodyIsNotRead(@TempDir Path dir) throws Exception {
        final TestTls tls = TestTls.create(dir, false);
        final Site http = site().on("/gone", (m, ex) -> reply(ex, m.equals("HEAD") ? 404 : 200, "image/png", null))
                .on("/ok", (m, ex) -> reply(ex, 200, "image/png", null));
        final var https = tls.startServer(ex -> {
            final String path = ex.getRequestURI().getPath();
            ex.getResponseHeaders().add("Location", "http://site.example:" + http.port + (path.equals("/start-gone") ? "/gone" : "/ok"));
            ex.sendResponseHeaders(302, -1);
            ex.close();
        });
        try {
            final int tlsPort = https.getAddress().getPort();
            final SafeHttp.Options o = new SafeHttp.Options();
            o.allowNonPublicForTests = true;
            o.sslContext = tls.trustingClientContext;
            o.resolver = SafeHttp.fixedResolver(Map.of("site.example", new InetAddress[]{LOOPBACK}));
            final LinkSettings c = cfg();
            final LinkProbe p = new LinkProbe(new SafeHttp(o), new UrlFilter(List.of()), c, new HostThrottle(c), Set.of(tlsPort, http.port), now::get, sleeps::add);
            // ダウングレード先で HEAD が 404 のとき、GET に切り替えない (本文は読まない) ので、BROKEN のまま
            assertResult(p.probe("https://site.example:" + tlsPort + "/start-gone"), LinkProbe.Verdict.BROKEN, LinkReason.HTTP_404);
            assertEquals(0, http.count("GET"));
            // ダウングレード先が HEAD で OK なら、OK
            assertResult(p.probe("https://site.example:" + tlsPort + "/start-ok"), LinkProbe.Verdict.OK, LinkReason.OK);
        } finally {
            https.stop(0);
        }
    }

    // ---- 内容 ----

    @Test
    void htmlInsteadOfMediaIsBroken() throws Exception {
        final Site s = site().on("/pic.png", (m, ex) -> reply(ex, 200, "text/html; charset=utf-8", null))
                .on("/clip.mp4", (m, ex) -> reply(ex, 200, "text/html", null))
                .on("/page.html", (m, ex) -> reply(ex, 200, "text/html", null))
                .on("/noext", (m, ex) -> reply(ex, 200, "text/html", null))
                .on("/ok.png", (m, ex) -> reply(ex, 200, "image/png", null));
        assertResult(probe(s).probe(url(s, "/pic.png")), LinkProbe.Verdict.BROKEN, LinkReason.HTML_INSTEAD_OF_MEDIA);
        assertResult(probe(s).probe(url(s, "/clip.mp4")), LinkProbe.Verdict.BROKEN, LinkReason.HTML_INSTEAD_OF_MEDIA);
        assertResult(probe(s).probe(url(s, "/page.html")), LinkProbe.Verdict.OK, LinkReason.OK);
        assertResult(probe(s).probe(url(s, "/noext")), LinkProbe.Verdict.OK, LinkReason.OK);
        assertResult(probe(s).probe(url(s, "/ok.png")), LinkProbe.Verdict.OK, LinkReason.OK);
    }

    @Test
    void knownRemovedPlaceholderRedirectIsBroken() throws Exception {
        final Site s = site().on("/gone.png", (m, ex) -> reply(ex, 302, null, "/removed.png"))
                .on("/removed.png", (m, ex) -> reply(ex, 200, "image/png", null))
                .on("/fine.png", (m, ex) -> reply(ex, 200, "image/png", null));
        final LinkSettings c = cfg();
        c.hostRules = new ArrayList<>(List.of(new LinkSettings.HostRule("site.example", 0, List.of("/removed.png"))));
        final LinkProbe p = probe(c, 5000, Set.of(s.port), Map.of("site.example", new InetAddress[]{LOOPBACK}));
        assertResult(p.probe(url(s, "/gone.png")), LinkProbe.Verdict.BROKEN, LinkReason.REMOVED_PLACEHOLDER);
        assertResult(p.probe(url(s, "/fine.png")), LinkProbe.Verdict.OK, LinkReason.OK);
        // 決まりがなければ、置き換えの画像も 200 なので OK になってしまう (だから決まりを足せるようにしてある)
        assertResult(probe(s).probe(url(s, "/gone.png")), LinkProbe.Verdict.OK, LinkReason.OK);
    }

    // ---- 接続の失敗 ----

    @Test
    void dnsFailureConnectionRefusedAndTlsFailureAreBroken(@TempDir Path dir) throws Exception {
        final Site s = site();
        assertResult(probe(s).probe("http://nxdomain.example:" + s.port + "/a.png"), LinkProbe.Verdict.BROKEN, LinkReason.DNS_FAILED);
        final int dead;
        try (java.net.ServerSocket tmp = new java.net.ServerSocket(0, 1, LOOPBACK)) {
            dead = tmp.getLocalPort();
        }
        final LinkProbe p = probe(cfg(), 3000, Set.of(dead), Map.of("site.example", new InetAddress[]{LOOPBACK}));
        assertResult(p.probe("http://site.example:" + dead + "/a.png"), LinkProbe.Verdict.BROKEN, LinkReason.CONNECT_REFUSED);

        final TestTls tls = TestTls.create(dir, false);
        final var https = tls.startServer(ex -> {
            ex.sendResponseHeaders(200, -1);
            ex.close();
        });
        try {
            final int port = https.getAddress().getPort();
            final SafeHttp.Options o = new SafeHttp.Options();
            o.allowNonPublicForTests = true; // 証明書は既定のトラストストアで検証する (自己署名なので失敗する)
            o.resolver = SafeHttp.fixedResolver(Map.of("site.example", new InetAddress[]{LOOPBACK}));
            final LinkSettings c = cfg();
            final LinkProbe tlsProbe = new LinkProbe(new SafeHttp(o), new UrlFilter(List.of()), c, new HostThrottle(c), Set.of(port), now::get, sleeps::add);
            assertResult(tlsProbe.probe("https://site.example:" + port + "/a.png"), LinkProbe.Verdict.BROKEN, LinkReason.TLS_ERROR);
        } finally {
            https.stop(0);
        }
    }

    // ---- URL の確認 ----

    @Test
    void unsafeUrlsAreNeverConnected() throws Exception {
        final Site s = site().on("/", (m, ex) -> reply(ex, 200, null, null));
        final LinkProbe p = probe(s);
        final List<String> bad = new ArrayList<>(List.of(
                "http://user@site.example:" + s.port + "/a.png", "http://user:pw@site.example:" + s.port + "/",
                "http://site.example:22/a.png", "http://site.example:9999/a.png", "http://site.example:25/",
                "http://2130706433/a.png", "http://0177.0.0.1/a.png", "http://0x7f.1/a.png", "http://127.1/a.png", "http://127.0.0.1:" + s.port + "/",
                "http://[::1]:" + s.port + "/", "http://localhost:" + s.port + "/", "http://10.1.2.3/", "http://192.168.0.1/",
                "javascript:alert(1)", "data:text/html,x", "file:///etc/passwd", "ftp://site.example/a.png", "//site.example/a.png",
                "https://example.com＠evil.com/a.png", "http://site.example/a\\b", "http://site.example:" + s.port + "/a\r\nHost: evil.example",
                "http://site.example:" + s.port + "/a b", "http://site.example:" + s.port + "/\u0000", "http://site.example:" + s.port + "/a\u007f",
                "http://site.example:" + s.port + "/" + "a".repeat(2100), "", "not a url", "http://", "http:///a.png"));
        for (String u : bad) {
            final LinkProbe.Result r = p.probe(u);
            assertResult(r, LinkProbe.Verdict.UNKNOWN, LinkReason.UNSAFE_URL);
            assertFalse(r.countsAsFailure(), u);
        }
        assertEquals(0, s.hits.size(), "危ない URL には、1 回もつながない: " + s.hits);
    }

    @Test
    void strictParseAllowsOnlyTheFourPorts() {
        final LinkProbe p = probe(cfg(), 1000, null, Map.of());
        for (String u : List.of("http://example.com/a", "https://example.com/a", "http://example.com:80/a", "https://example.com:443/a",
                "http://example.com:8080/a", "https://example.com:8443/a")) {
            assertNotNull(p.strictParse(u), u);
        }
        for (String u : List.of("http://example.com:8000/a", "https://example.com:444/a", "http://example.com:81/", "http://example.com:3306/",
                "http://example.com:22/", "http://example.com:0/")) {
            assertNull(p.strictParse(u), u);
        }
    }

    // ---- ホストごとの間隔と上限 ----

    @Test
    void sameHostIsSpacedAndCappedPerHour() throws Exception {
        final Site s = site().on("/", (m, ex) -> reply(ex, 200, null, null));
        final LinkSettings c = cfg();
        c.hostMinIntervalSeconds = 2;
        c.hostPerHourLimit = 3;
        final HostThrottle throttle = new HostThrottle(c);
        final SafeHttp.Options o = new SafeHttp.Options();
        o.allowNonPublicForTests = true;
        o.resolver = SafeHttp.fixedResolver(Map.of("site.example", new InetAddress[]{LOOPBACK}));
        final LinkProbe p = new LinkProbe(new SafeHttp(o), new UrlFilter(List.of()), c, throttle, Set.of(s.port), now::get, sleeps::add);

        assertResult(p.probe(url(s, "/1")), LinkProbe.Verdict.OK, LinkReason.OK);
        assertTrue(sleeps.isEmpty(), "最初はすぐ");
        assertResult(p.probe(url(s, "/2")), LinkProbe.Verdict.OK, LinkReason.OK);
        assertEquals(1, sleeps.size(), "2 回目は間隔が空くまで待つ");
        assertTrue(sleeps.get(0) >= 2000 && sleeps.get(0) <= 2700, "2 秒 + ゆらぎ: " + sleeps.get(0));
        now.addAndGet(10_000);
        assertResult(p.probe(url(s, "/3")), LinkProbe.Verdict.OK, LinkReason.OK);
        now.addAndGet(10_000);
        // 1 時間 3 件の上限
        final LinkProbe.Result deferred = p.probe(url(s, "/4"));
        assertEquals(LinkProbe.Verdict.DEFER, deferred.verdict());
        assertEquals(3, s.count("HEAD"));
        now.addAndGet(3_700_000);
        assertResult(p.probe(url(s, "/5")), LinkProbe.Verdict.OK, LinkReason.OK);
    }

    @Test
    void hostRuleLengthensTheInterval() {
        final LinkSettings c = cfg();
        c.hostMinIntervalSeconds = 2;
        c.hostRules = new ArrayList<>(List.of(new LinkSettings.HostRule("imgur.com", 5, List.of("/removed.png"))));
        assertEquals(5000, c.minIntervalMillis("i.imgur.com"));
        assertEquals(5000, c.minIntervalMillis("imgur.com"));
        assertEquals(2000, c.minIntervalMillis("notimgur.com"));
        assertEquals(2000, c.minIntervalMillis("example.org"));
    }
}
