package net.torosaba.signmap;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.net.ssl.SSLSocket;
import java.io.IOException;
import java.io.InputStream;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

/** 独立したセキュリティレビューの指摘への対策の確認 */
class ReviewFixesTest {

    private static SafeHttp.Kind failureOf(SafeHttp http, String url) {
        try {
            http.request("HEAD", URI.create(url), null);
        } catch (SafeHttp.HttpFailure f) {
            return f.kind;
        }
        return null;
    }

    // ---- 1. TLS で小出しに送られても、全体の期限で切れる ----

    @Test
    void tlsHandshakeDrippedByteByByteStillEndsAtTheDeadline(@TempDir Path dir) throws Exception {
        // TLS のレコードのヘッダーだけ送って、そのあと 1 バイトずつ送る。SO_TIMEOUT (1 回の read) だけでは、いつまでも終わらない
        final TestTls tls = TestTls.create(dir, false);
        try (ServerSocket server = new ServerSocket(0, 5, InetAddress.getLoopbackAddress())) {
            final Thread t = new Thread(() -> {
                try (Socket s = server.accept()) {
                    final InputStream in = s.getInputStream();
                    in.read(new byte[1024]); // ClientHello
                    final byte[] header = {0x16, 0x03, 0x03, 0x3F, (byte) 0xFF}; // handshake、16 KB のレコード
                    s.getOutputStream().write(header);
                    for (int i = 0; i < 100; i++) {
                        s.getOutputStream().write(0x02);
                        s.getOutputStream().flush();
                        Thread.sleep(400);
                    }
                } catch (Exception e) {
                    // クライアントが切った
                }
            });
            t.setDaemon(true);
            t.start();
            final SafeHttp.Options o = new SafeHttp.Options();
            o.allowNonPublicForTests = true;
            o.sslContext = tls.trustingClientContext;
            o.totalTimeoutMillis = 1500;
            o.resolver = SafeHttp.fixedResolver(Map.of("site.example", new InetAddress[]{InetAddress.getLoopbackAddress()}));
            final long t0 = System.nanoTime();
            final SafeHttp.Kind kind = failureOf(new SafeHttp(o), "https://site.example:" + server.getLocalPort() + "/");
            final long ms = (System.nanoTime() - t0) / 1_000_000;
            assertEquals(SafeHttp.Kind.TIMEOUT, kind);
            assertTrue(ms < 4000, "期限 (1.5 秒) で切れた: " + ms + " ms");
        }
    }

    @Test
    void stopClosesOpenSocketsSoBlockedReadsEnd() throws Exception {
        final CountDownLatch connected = new CountDownLatch(1);
        try (ServerSocket server = new ServerSocket(0, 5, InetAddress.getLoopbackAddress())) {
            final Thread t = new Thread(() -> {
                try (Socket s = server.accept()) {
                    connected.countDown();
                    Thread.sleep(30_000); // 何も返さない
                } catch (Exception e) {
                    // 閉じられた
                }
            });
            t.setDaemon(true);
            t.start();
            final SafeHttp.Options o = new SafeHttp.Options();
            o.allowNonPublicForTests = true;
            o.totalTimeoutMillis = 60_000;
            o.resolver = SafeHttp.fixedResolver(Map.of("site.example", new InetAddress[]{InetAddress.getLoopbackAddress()}));
            final SafeHttp http = new SafeHttp(o);
            final AtomicLong elapsed = new AtomicLong();
            final Thread caller = new Thread(() -> {
                final long t0 = System.nanoTime();
                failureOf(http, "http://site.example:" + server.getLocalPort() + "/");
                elapsed.set((System.nanoTime() - t0) / 1_000_000);
            });
            caller.start();
            assertTrue(connected.await(5, TimeUnit.SECONDS));
            Thread.sleep(300);
            http.closeAll();
            caller.join(5000);
            assertFalse(caller.isAlive(), "止めたあとに、止まっている read が残らない");
            assertTrue(elapsed.get() < 5000);
        }
    }

    // ---- 2. サーバー自身のアドレス・設定で除いたアドレスには、つながない ----

    @Test
    void ownAddressesAndConfiguredRangesAreNeverConnected() throws Exception {
        final AtomicInteger connects = new AtomicInteger();
        final SafeHttp.Options o = new SafeHttp.Options();
        // この機械のアドレスが「公開」のものだった場合 (ここでは 8.8.8.8 とする)。同じ機械の中を通って、外から守られたサービスに届くので除く
        o.localAddresses = () -> List.of(inet("8.8.8.8"), inet("2606:4700:4700::1111"));
        o.denyList = new AddressDenyList(List.of("93.184.216.0/24", "203.0.113.7", "2a00:1450::/32", "not an address", "1.2.3.4/99", "evil.example"));
        o.resolver = SafeHttp.fixedResolver(Map.of(
                "own4.example", new InetAddress[]{inet("8.8.8.8")},
                "own6.example", new InetAddress[]{inet("2606:4700:4700::1111")},
                "range.example", new InetAddress[]{inet("93.184.216.34")},
                "single.example", new InetAddress[]{inet("203.0.113.7")},
                "range6.example", new InetAddress[]{inet("2a00:1450:4001:81b::200e")},
                "mixed.example", new InetAddress[]{inet("1.1.1.1"), inet("8.8.8.8")},
                "fine.example", new InetAddress[]{inet("1.1.1.1")}));
        o.connector = (a, p, t) -> {
            connects.incrementAndGet();
            throw new ConnectException("Connection refused");
        };
        final SafeHttp http = new SafeHttp(o);
        for (String h : List.of("own4", "own6", "range", "single", "range6", "mixed")) {
            assertEquals(SafeHttp.Kind.UNSAFE_ADDRESS, failureOf(http, "http://" + h + ".example/"), h);
        }
        assertEquals(0, connects.get());
        assertEquals(SafeHttp.Kind.CONNECT_REFUSED, failureOf(http, "http://fine.example/"), "対照: 普通の公開アドレスにはつなごうとする");
        assertEquals(1, connects.get());
        assertEquals(List.of("not an address", "1.2.3.4/99", "evil.example"), o.denyList.rejected());
    }

    @Test
    void denyListRangesAreExact() throws Exception {
        final AddressDenyList l = new AddressDenyList(List.of("198.51.100.0/24", "10.0.0.0/8", "2001:db8::/32", "192.0.2.5", "0.0.0.0/0"));
        assertTrue(l.matches(inet("198.51.100.255")));
        assertTrue(l.matches(inet("8.8.8.8")), "0.0.0.0/0 はすべて");
        final AddressDenyList narrow = new AddressDenyList(List.of("198.51.100.0/25", "2001:db8:1::/48", "192.0.2.5", "::ffff:203.0.113.9"));
        assertTrue(narrow.matches(inet("198.51.100.127")));
        assertFalse(narrow.matches(inet("198.51.100.128")));
        assertTrue(narrow.matches(inet("2001:db8:1:ffff::1")));
        assertFalse(narrow.matches(inet("2001:db8:2::1")));
        assertTrue(narrow.matches(inet("192.0.2.5")));
        assertFalse(narrow.matches(inet("192.0.2.6")));
        assertTrue(narrow.matches(inet("203.0.113.9")), "IPv4 射影の書き方でも、IPv4 のアドレスとして扱う");
        assertEquals(0, new AddressDenyList(List.of()).size());
    }

    @Test
    void connectingToOurOwnAddressIsClosed() throws Exception {
        // 名前の引き方に関係なく、つないだ先が自分自身のアドレスなら閉じる (ここでは ServerSocket にループバックでつなぐ)
        try (ServerSocket server = new ServerSocket(0, 5, InetAddress.getLoopbackAddress())) {
            final SafeHttp.Options o = new SafeHttp.Options();
            o.resolver = SafeHttp.fixedResolver(Map.of("pub.example", new InetAddress[]{inet("8.8.8.8")}));
            o.connector = (a, p, t) -> new Socket(InetAddress.getLoopbackAddress(), server.getLocalPort(), InetAddress.getLoopbackAddress(), 0);
            assertEquals(SafeHttp.Kind.UNSAFE_ADDRESS, failureOf(new SafeHttp(o), "http://pub.example/"));
        }
    }

    // ---- 3. 内部アドレスへの転送は、公開の地図に出さない ----

    @Test
    void unsafeRedirectsAreNotShownOnThePublicMap() {
        final LinkSettings cfg = new LinkSettings();
        LinkState s = LinkState.pending(1_000_000).apply(LinkProbe.Result.unknown(LinkReason.REDIRECT_UNSAFE, 302, false), 1_000_000, cfg, 0);
        assertEquals(LinkStatus.UNKNOWN, s.status());
        assertFalse(s.needsAttention());
        assertNull(DynmapBackend.layerName(s, true));
        s = LinkState.pending(1_000_000).apply(LinkProbe.Result.unknown(LinkReason.UNSAFE_URL, 0, false), 1_000_000, cfg, 0);
        assertNull(DynmapBackend.layerName(s, true));
    }

    // ---- 4. 名前を引く枠が埋まっても、ほかの URL を誤って BROKEN にしない ----

    @Test
    void dnsSlotsAreLimitedAndSaturationIsNotAFailure() throws Exception {
        final CountDownLatch release = new CountDownLatch(1);
        final AtomicInteger started = new AtomicInteger();
        final SafeHttp.Resolver resolver = SafeHttp.systemResolver(200, host -> {
            started.incrementAndGet();
            // 引き終わらない問い合わせ。getAllByName と同じく、中断されても続ける
            while (release.getCount() > 0) {
                try {
                    release.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException ignored) {
                    // 中断できない
                }
            }
            throw new UnknownHostException(host);
        }, 2);
        final SafeHttp.Options o = new SafeHttp.Options();
        o.resolver = resolver;
        o.connector = (a, p, t) -> {
            throw new ConnectException("Connection refused");
        };
        final SafeHttp http = new SafeHttp(o);
        // 2 つが引き終わらず、時間切れ (DNS_TIMEOUT)。その間、枠は返らない
        assertEquals(SafeHttp.Kind.DNS_TIMEOUT, failureOf(http, "http://hang1.example/"));
        assertEquals(SafeHttp.Kind.DNS_TIMEOUT, failureOf(http, "http://hang2.example/"));
        // 枠が埋まったので、3 つ目は引こうとせず DNS_BUSY
        assertEquals(SafeHttp.Kind.DNS_BUSY, failureOf(http, "http://legit.example/"));
        assertEquals(2, started.get());
        release.countDown();

        // LinkProbe は DNS_BUSY を DEFER (失敗に数えない) にする
        final LinkSettings cfg = new LinkSettings();
        final SafeHttp.Options o2 = new SafeHttp.Options();
        o2.resolver = SafeHttp.systemResolver(100, host -> {
            throw new UnknownHostException(host);
        }, 0); // 枠が 0 = 常に DNS_BUSY
        final LinkProbe probe = new LinkProbe(new SafeHttp(o2), new UrlFilter(List.of()), cfg, new HostThrottle(cfg), Set.of(80), System::currentTimeMillis, ms -> {
        });
        final LinkProbe.Result r = probe.probe("http://legit.example/a.png");
        assertEquals(LinkProbe.Verdict.DEFER, r.verdict());
        final LinkState after = LinkState.pending(1000).apply(r, 1000, cfg, 0);
        assertEquals(LinkStatus.PENDING, after.status(), "状態は変わらず、あとでやり直す");
        assertEquals(0, after.consecutiveFailures());
    }

    // ---- 5. 追跡の表を、看板にない URL で占められない ----

    private final AtomicLong clock = new AtomicLong(10_000_000_000L);

    private LinkChecker checker(Path dir, int max) {
        final LinkSettings c = new LinkSettings();
        c.maxTrackedUrls = max;
        c.startupDelayMinutes = 0;
        c.hostRules = new ArrayList<>();
        return new LinkChecker(Logger.getLogger("rf-" + System.nanoTime()), dir.resolve("l.json"), c, u -> u.startsWith("https://"),
                u -> LinkProbe.Result.ok(200), new HostThrottle(c), clock::get, () -> {
        });
    }

    private static String url(String tag, int i) {
        return "https://h" + i + ".example/" + tag + ".png";
    }

    @Test
    void urlsNoLongerOnAnySignAreEvictedFirstEvenIfChecked(@TempDir Path dir) {
        final LinkChecker c = checker(dir, 100);
        final List<String> attacker = new ArrayList<>();
        for (int i = 0; i < 100; i++) attacker.add(url("spam", i));
        c.track(attacker);
        c.step(clock.get()); // 検査して、OK にする
        c.step(clock.get() + 1);
        // 看板が壊されて、攻撃者の URL は知らされなくなる。しばらくして、正規の看板が増える
        clock.addAndGet(3 * 3_600_000L);
        final List<String> legit = new ArrayList<>();
        for (int i = 0; i < 30; i++) legit.add(url("real", i));
        c.track(legit);
        for (String u : legit) assertNotNull(c.state(u), "正規の URL は追跡できる: " + u);
        assertTrue(c.tracked() <= 100);
    }

    @Test
    void urlsStillOnSignsAreNeverEvictedByNewOnes(@TempDir Path dir) {
        final LinkChecker c = checker(dir, 50);
        final List<String> live = new ArrayList<>();
        for (int i = 0; i < 50; i++) live.add(url("live", i));
        c.track(live);
        for (int i = 0; i < 40; i++) c.step(clock.addAndGet(1000)); // 検査して、PENDING でなくする
        assertEquals(50, c.counts().ok());
        clock.addAndGet(10 * 60_000L);
        final List<String> both = new ArrayList<>(live);
        both.add(url("new", 1));
        c.track(both); // 全部がいま看板にある。新しいものは入らない (既存を落とさない)
        for (String u : live) assertNotNull(c.state(u), u);
        assertNull(c.state(url("new", 1)));
    }

    @Test
    void whenNothingCanBeEvictedWeDoNotRescanOnEveryNewUrl(@TempDir Path dir) {
        final LinkChecker c = checker(dir, 200);
        final List<String> live = new ArrayList<>();
        for (int i = 0; i < 200; i++) live.add(url("live", i));
        c.track(live);
        clock.addAndGet(60_000L);
        final long t0 = System.nanoTime();
        for (int i = 0; i < 20_000; i++) c.track(List.of(url("flood", i)));
        final long ms = (System.nanoTime() - t0) / 1_000_000;
        assertTrue(ms < 3000, "満杯のとき、新しい URL のたびに全体を調べない: " + ms + " ms");
        assertEquals(200, c.tracked());
    }

    // ---- 6. § (書式コード) ----

    @Test
    void sectionSignIsRemovedFromEverythingShown() {
        assertEquals("a4blkc", SafeText.clean("a§4b§l§kc", 100), "§ そのものを除く (残る文字は、ただの文字)");
        assertFalse(SafeText.displayUrl("http://nx.invalid/§4§lSERVER§r§k", 100).contains("§"));
        assertFalse(SafeText.logUrl("http://nx.invalid/§4x").contains("§"));
        final String popup = DynmapBackend.problemPopup(
                LinkState.pending(0), new MarkerHtml.View(new SignRecord(1, 2, 3, "u"), "http://nx.invalid/§4§l.png", MediaKind.IMAGE, "png"),
                "wor§4ld", new SignRecord(1, 2, 3, "u"), List.of(), true, 0);
        assertFalse(popup.contains("§"));
    }

    // ---- 7. ホストの判定の揺れ ----

    @Test
    void trailingDotAndCaseShareTheSameHostBudget() {
        final LinkSettings c = new LinkSettings();
        c.hostPerHourLimit = 2;
        c.hostMinIntervalSeconds = 0;
        final HostThrottle t = new HostThrottle(c);
        assertTrue(t.reserve("a.com", 1000) >= 0);
        assertTrue(t.reserve("A.COM.", 1001) >= 0);
        assertEquals(-1, t.reserve("a.com..", 1002), "a.com と a.com. と A.COM は同じホスト");
    }

    private static InetAddress inet(String s) {
        try {
            return InetAddress.getByName(s);
        } catch (UnknownHostException e) {
            throw new IllegalArgumentException(s, e);
        }
    }
}
