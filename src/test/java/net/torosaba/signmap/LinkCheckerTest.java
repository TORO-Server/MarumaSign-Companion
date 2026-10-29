package net.torosaba.signmap;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

/** 検査の割り当て (負荷の抑制、優先、上限)、保存と読み込み */
class LinkCheckerTest {

    private static final long MIN = 60_000L;
    private static final long H = 3_600_000L;
    private static final long T0 = 5_000_000_000L;

    private final AtomicLong clock = new AtomicLong(T0);
    private final List<String> probed = new CopyOnWriteArrayList<>();
    private final List<String> logged = new CopyOnWriteArrayList<>();
    private final Logger log = Logger.getLogger("linkchecker-test-" + System.nanoTime());
    private final AtomicInteger changes = new AtomicInteger();

    LinkCheckerTest() {
        log.setUseParentHandlers(false);
        log.addHandler(new Handler() {
            @Override
            public void publish(LogRecord r) {
                logged.add(r.getLevel() + " " + r.getMessage());
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        });
    }

    private LinkSettings cfg() {
        final LinkSettings c = new LinkSettings();
        c.startupDelayMinutes = 5;
        c.hostMinIntervalSeconds = 0;
        c.concurrency = 2;
        c.hostRules = new ArrayList<>();
        return c;
    }

    private LinkChecker checker(Path file, LinkSettings c, LinkProbe.Prober prober, HostThrottle throttle) {
        return new LinkChecker(log, file, c, u -> u.startsWith("https://") && u.length() < 2000, prober, throttle, clock::get, changes::incrementAndGet);
    }

    private LinkChecker checker(Path file, LinkSettings c) {
        return checker(file, c, u -> {
            probed.add(u);
            return LinkProbe.Result.ok(200);
        }, new HostThrottle(c));
    }

    private static String url(int i) {
        return "https://host" + i + ".example/a.png";
    }

    @Test
    void waitsForTheStartupDelayBeforeTheFirstCheck(@TempDir Path dir) {
        final LinkChecker c = checker(dir.resolve("l.json"), cfg());
        c.track(List.of(url(1), url(2)));
        assertEquals(0, c.step(T0));
        assertEquals(0, c.step(T0 + 4 * MIN));
        assertEquals(2, c.step(T0 + 5 * MIN));
        assertEquals(2, probed.size());
    }

    @Test
    void sameUrlOnManySignsIsCheckedOnce(@TempDir Path dir) {
        final LinkChecker c = checker(dir.resolve("l.json"), cfg());
        c.track(List.of(url(1), url(1), url(1)));
        c.track(List.of(url(1)));
        assertEquals(1, c.tracked());
        c.step(T0 + 6 * MIN);
        assertEquals(List.of(url(1)), probed);
        // 次の期限 (7 日) までは、もう検査しない
        clock.set(T0 + 7 * MIN);
        assertEquals(0, c.step(T0 + 7 * MIN));
        assertEquals(LinkStatus.OK, c.state(url(1)).status());
    }

    @Test
    void newOrEditedUrlsAreCheckedFirst(@TempDir Path dir) {
        final LinkSettings s = cfg();
        s.concurrency = 1;
        final LinkChecker c = checker(dir.resolve("l.json"), s);
        c.track(List.of(url(1), url(2), url(3)));
        c.prioritize(url(3));
        c.step(T0 + 6 * MIN);
        assertEquals(List.of(url(3)), probed);
    }

    @Test
    void unacceptableUrlsAreNeverTracked(@TempDir Path dir) {
        final LinkChecker c = checker(dir.resolve("l.json"), cfg());
        c.track(java.util.Arrays.asList("http://insecure.example/a", "javascript:alert(1)", null, "https://" + "a".repeat(3000), url(1)));
        assertEquals(1, c.tracked());
        c.prioritize("ftp://x.example/");
        assertEquals(1, c.tracked());
        assertEquals(0, c.recheck("https://not-tracked.example/"));
    }

    @Test
    void sameHostIsNotStartedTwiceInOneRoundAndRespectsTheInterval(@TempDir Path dir) {
        final LinkSettings s = cfg();
        s.hostMinIntervalSeconds = 2;
        final HostThrottle throttle = new HostThrottle(s);
        final LinkChecker c = checker(dir.resolve("l.json"), s, u -> {
            probed.add(u);
            throttle.reserve(java.net.URI.create(u).getHost(), clock.get());
            return LinkProbe.Result.ok(200);
        }, throttle);
        c.track(List.of("https://same.example/1.png", "https://same.example/2.png", "https://other.example/3.png"));
        clock.set(T0 + 6 * MIN);
        assertEquals(2, c.step(clock.get()), "同じホストは 1 件ずつ (別のホストは同時に)");
        clock.set(T0 + 6 * MIN + 100);
        assertEquals(0, c.step(clock.get()));
        clock.set(T0 + 6 * MIN + 4000);
        assertEquals(1, c.step(clock.get()));
        assertEquals(3, probed.size());
    }

    @Test
    void perHostHourlyCapDefersTheRest(@TempDir Path dir) {
        final LinkSettings s = cfg();
        s.hostPerHourLimit = 2;
        s.concurrency = 4;
        final HostThrottle throttle = new HostThrottle(s);
        final LinkChecker c = checker(dir.resolve("l.json"), s, u -> {
            probed.add(u);
            throttle.reserve(java.net.URI.create(u).getHost(), clock.get());
            return LinkProbe.Result.ok(200);
        }, throttle);
        final List<String> urls = new ArrayList<>();
        for (int i = 0; i < 6; i++) urls.add("https://busy.example/" + i + ".png");
        c.track(urls);
        long t = T0 + 6 * MIN;
        for (int i = 0; i < 6; i++) {
            c.step(t);
            t += 10_000;
        }
        assertEquals(2, probed.size(), "1 時間に 2 件まで");
        t += H;
        c.step(t);
        assertEquals(3, probed.size(), "1 時間たてば、また進む");
    }

    @Test
    void globalHourlyLimitStopsNewChecks(@TempDir Path dir) {
        final LinkSettings s = cfg();
        s.perHourLimit = 3;
        s.concurrency = 4;
        final LinkChecker c = checker(dir.resolve("l.json"), s);
        final List<String> urls = new ArrayList<>();
        for (int i = 0; i < 10; i++) urls.add(url(i));
        c.track(urls);
        long t = T0 + 6 * MIN;
        int started = 0;
        for (int i = 0; i < 5; i++) {
            started += c.step(t);
            t += 1000;
        }
        assertEquals(3, started);
        assertEquals(3, c.counts().hourUsed());
        assertTrue(c.step(t + H + 1000) > 0);
    }

    @Test
    void concurrencyIsLimited(@TempDir Path dir) throws Exception {
        final LinkSettings s = cfg();
        s.concurrency = 2;
        s.startupDelayMinutes = 0;
        final AtomicInteger running = new AtomicInteger();
        final AtomicInteger max = new AtomicInteger();
        final CountDownLatch release = new CountDownLatch(1);
        final CountDownLatch twoRunning = new CountDownLatch(2);
        final LinkChecker c = new LinkChecker(log, dir.resolve("l.json"), s, u -> true, u -> {
            max.accumulateAndGet(running.incrementAndGet(), Math::max);
            twoRunning.countDown();
            try {
                release.await(10, TimeUnit.SECONDS);
            } finally {
                running.decrementAndGet();
            }
            return LinkProbe.Result.ok(200);
        }, new HostThrottle(s), System::currentTimeMillis, () -> {
        });
        final List<String> urls = new ArrayList<>();
        for (int i = 0; i < 10; i++) urls.add(url(i));
        c.track(urls);
        c.start();
        try {
            assertTrue(twoRunning.await(10, TimeUnit.SECONDS));
            Thread.sleep(1500);
            assertEquals(2, max.get(), "同時に検査するのは 2 件まで");
            assertEquals(2, c.counts().inFlight());
        } finally {
            release.countDown();
            c.stop();
        }
    }

    @Test
    void stopEndsTheThreadsAndSavesTheResults(@TempDir Path dir) throws Exception {
        final LinkSettings s = cfg();
        s.startupDelayMinutes = 0;
        final Path file = dir.resolve("l.json");
        final LinkChecker c = new LinkChecker(log, file, s, u -> true, u -> LinkProbe.Result.ok(200), new HostThrottle(s), System::currentTimeMillis, () -> {
        });
        c.track(List.of(url(1)));
        c.start();
        c.stop();
        assertTrue(Files.isRegularFile(file));
        Thread.sleep(300);
        final long alive = Thread.getAllStackTraces().keySet().stream()
                .filter(t -> t.getName().startsWith("BlueMapMarumaSign-linkcheck") && t.isAlive()).count();
        assertEquals(0, alive, "止めたあとに、検査のスレッドが残らない");
    }

    @Test
    void queueLimitDropsTheOldestPendingAndLogsOnce(@TempDir Path dir) {
        final LinkSettings s = cfg();
        s.maxTrackedUrls = 100;
        final LinkChecker c = checker(dir.resolve("l.json"), s);
        for (int i = 0; i < 150; i++) {
            clock.addAndGet(10);
            c.track(List.of(url(i)));
        }
        assertTrue(c.tracked() <= 100, "上限を超えない: " + c.tracked());
        assertNull(c.state(url(0)), "古い未検査から落ちる");
        assertNotNull(c.state(url(149)));
        assertEquals(1, logged.stream().filter(l -> l.contains("queue is full")).count(), "ログは 1 行だけ");
    }

    @Test
    void failingProberBecomesUnknownAndTheLogCarriesNoSecrets(@TempDir Path dir) {
        final LinkChecker c = checker(dir.resolve("l.json"), cfg(), u -> {
            throw new IllegalStateException("SECRET-MESSAGE token=SECRET");
        }, new HostThrottle(cfg()));
        c.track(List.of("https://secret.example/a.png?token=SECRET#frag"));
        c.step(T0 + 6 * MIN);
        final LinkState st = c.state("https://secret.example/a.png?token=SECRET#frag");
        assertEquals(LinkStatus.UNKNOWN, st.status());
        for (String line : logged) {
            assertFalse(line.contains("SECRET"), line);
            assertFalse(line.contains("token"), line);
        }
        assertTrue(logged.stream().anyMatch(l -> l.contains("IllegalStateException") && l.contains("secret.example/a.png")));
    }

    @Test
    void onChangeIsCalledOnlyWhenTheStatusOrReasonChanges(@TempDir Path dir) {
        final LinkChecker c = checker(dir.resolve("l.json"), cfg());
        c.track(List.of(url(1)));
        c.step(T0 + 6 * MIN);
        assertEquals(1, changes.get(), "PENDING → OK");
        clock.set(T0 + 8 * 24 * H);
        c.step(clock.get());
        assertEquals(1, changes.get(), "OK のまま変わらなければ呼ばない");
    }

    @Test
    void recheckMakesUrlsDueAtOnce(@TempDir Path dir) {
        final LinkChecker c = checker(dir.resolve("l.json"), cfg(), u -> LinkProbe.Result.broken(LinkReason.HTTP_404, 404), new HostThrottle(cfg()));
        c.track(List.of(url(1), url(2)));
        c.step(T0 + 6 * MIN);
        assertEquals(2, c.counts().broken());
        assertEquals(0, c.step(T0 + 7 * MIN));
        assertEquals(2, c.recheckAllBroken());
        assertEquals(2, c.step(T0 + 7 * MIN));
        assertEquals(1, c.recheck(url(1)));
    }

    // ---- 保存と読み込み ----

    @Test
    void resultsSurviveARestart(@TempDir Path dir) throws Exception {
        final Path file = dir.resolve("data").resolve("linkcheck.json");
        final LinkChecker a = checker(file, cfg(), u -> u.endsWith("/1.png") ? LinkProbe.Result.broken(LinkReason.HTTP_404, 404)
                : LinkProbe.Result.ok(200), new HostThrottle(cfg()));
        a.track(List.of("https://x1.example/1.png", "https://x2.example/2.png", "https://x3.example/3.png"));
        a.step(T0 + 6 * MIN);
        a.step(T0 + 6 * MIN + 5000);
        a.save();
        assertFalse(Files.exists(file.resolveSibling("linkcheck.json.tmp")), "一時ファイルは残さない");

        final LinkChecker b = checker(file, cfg());
        b.load();
        assertEquals(3, b.tracked());
        final LinkState s1 = b.state("https://x1.example/1.png");
        assertEquals(a.state("https://x1.example/1.png"), s1);
        assertEquals(LinkStatus.BROKEN, s1.status());
        assertEquals(LinkReason.HTTP_404, s1.reason());
        assertEquals(404, s1.httpCode());
        assertTrue(s1.firstFailedAt() > 0 && s1.lastCheckedAt() > 0 && s1.consecutiveFailures() == 1);
        final String json = Files.readString(file, StandardCharsets.UTF_8);
        for (String key : List.of("status", "httpCode", "reason", "firstFailedAt", "lastFailedAt", "lastOkAt", "lastCheckedAt", "consecutiveFailures")) {
            assertTrue(json.contains("\"" + key + "\""), key);
        }
    }

    @Test
    void oldUnseenEntriesArePrunedOnSave(@TempDir Path dir) throws Exception {
        final Path file = dir.resolve("l.json");
        final LinkChecker a = checker(file, cfg());
        a.track(List.of(url(1)));
        clock.addAndGet(40L * 24 * H);
        a.track(List.of(url(2)));
        a.save();
        assertNull(a.state(url(1)));
        assertNotNull(a.state(url(2)));
    }

    private LinkChecker loadFrom(Path file, String content) throws IOException {
        Files.writeString(file, content, StandardCharsets.UTF_8);
        final LinkChecker c = checker(file, cfg());
        c.load();
        return c;
    }

    @Test
    void corruptFilesAreMovedAsideAndWeStartEmpty(@TempDir Path dir) throws Exception {
        final String good = "{\"status\":\"OK\",\"httpCode\":200,\"reason\":\"OK\",\"firstFailedAt\":0,\"lastFailedAt\":0,\"lastOkAt\":1,"
                + "\"lastCheckedAt\":1,\"consecutiveFailures\":0,\"nextCheckAt\":1,\"lastSeenAt\":1}";
        final List<String> corrupt = List.of(
                "not json at all", "[]", "{}", "{\"version\":2,\"links\":{}}", "{\"version\":1}", "{\"version\":1,\"links\":[]}",
                "{\"version\":1,\"links\":{\"https://a.example/x\":5}}",
                "{\"version\":1,\"links\":{\"https://a.example/x\":" + good.replace("\"OK\",\"httpCode\"", "\"WEIRD\",\"httpCode\"") + "}}",
                "{\"version\":1,\"links\":{\"https://a.example/x\":" + good.replace("\"httpCode\":200", "\"httpCode\":99999") + "}}",
                "{\"version\":1,\"links\":{\"https://a.example/x\":" + good.replace("\"consecutiveFailures\":0", "\"consecutiveFailures\":-5") + "}}",
                "{\"version\":1,\"links\":{\"https://a.example/x\":" + good.replace("\"lastOkAt\":1", "\"lastOkAt\":-1") + "}}",
                "{\"version\":1,\"links\":{\"https://a.example/x\":" + good.replace("\"lastOkAt\":1", "\"lastOkAt\":999999999999999999") + "}}",
                "{\"version\":1,\"links\":{\"https://a.example/x\":" + good.replace("\"httpCode\":200,", "") + "}}",
                "{\"version\":1,\"links\":{\"https://a.example/x\":" + good.replace("\"reason\":\"OK\"", "\"reason\":\"<script>alert(1)</script>\"") + "}}");
        int n = 0;
        for (String content : corrupt) {
            final Path file = dir.resolve("c" + (n++) + ".json");
            final LinkChecker c = loadFrom(file, content);
            assertEquals(0, c.tracked(), content);
            assertFalse(Files.exists(file), "壊れたファイルは退避する: " + content);
            try (var files = Files.list(dir)) {
                assertTrue(files.anyMatch(p -> p.getFileName().toString().startsWith(file.getFileName() + ".corrupt-")), content);
            }
        }
        assertTrue(logged.stream().anyMatch(l -> l.contains("could not be read")));
    }

    @Test
    void loadedUrlsMustPassTheCurrentRulesAndAreNotTrusted(@TempDir Path dir) throws Exception {
        final String entry = "{\"status\":\"BROKEN\",\"httpCode\":404,\"reason\":\"HTTP_404\",\"firstFailedAt\":1,\"lastFailedAt\":1,\"lastOkAt\":0,"
                + "\"lastCheckedAt\":1,\"consecutiveFailures\":1,\"nextCheckAt\":1,\"lastSeenAt\":" + T0 + "}";
        final LinkChecker c = loadFrom(dir.resolve("l.json"), "{\"version\":1,\"links\":{"
                + "\"https://ok.example/a.png\":" + entry + ",\"javascript:alert(1)\":" + entry + ",\"http://plain.example/a\":" + entry
                + ",\"https://a.example/<script>\":" + entry.replace("\"BROKEN\"", "\"OK\"") + "}}");
        assertNotNull(c.state("https://ok.example/a.png"));
        assertNull(c.state("javascript:alert(1)"));
        assertNull(c.state("http://plain.example/a"));
        assertEquals(1, c.tracked() - (c.state("https://a.example/<script>") == null ? 0 : 1), "acceptable を通る URL だけ読む (ここでは <script> も通る形)");
    }

    @Test
    void missingFileStartsEmptyWithoutWarnings(@TempDir Path dir) {
        final LinkChecker c = checker(dir.resolve("none.json"), cfg());
        c.load();
        assertEquals(0, c.tracked());
        assertTrue(logged.isEmpty());
    }
}
