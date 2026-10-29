package net.torosaba.signmap;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.LongSupplier;
import java.util.function.Predicate;
import java.util.logging.Logger;

/**
 * 看板の URL のリンク切れを、少しずつ・非同期で検査して、結果を覚える。メインスレッドでは何もしない。
 * <ul>
 *   <li>URL 単位 (同じ URL の看板が複数あっても 1 回)。新しい URL・編集された URL は先に検査する。</li>
 *   <li>負荷: 同時に検査するのは少数、同じホストには間隔を空ける、全体と 1 ホストの 1 時間あたりの上限、起動後しばらく待つ。</li>
 *   <li>結果は JSON に保存して、再起動後も引き継ぐ (読むときは中身を検証する。壊れていればバックアップして空から)。</li>
 * </ul>
 */
public final class LinkChecker {

    private static final int FILE_VERSION = 1;
    private static final long MAX_FILE_BYTES = 64L * 1024 * 1024;
    private static final int MAX_FILE_ENTRIES = 200_000;
    private static final long PRUNE_AFTER_MILLIS = 30L * 24 * 3_600_000;
    private static final long YEAR = 366L * 24 * 3_600_000;

    private static final class Entry {
        volatile LinkState state;
        volatile boolean priority;

        Entry(LinkState state) {
            this.state = state;
        }
    }

    public record Counts(int ok, int broken, int unknown, int pending, int inFlight, int hourUsed) {
    }

    private final Logger log;
    private final Path file;
    private final LinkSettings cfg;
    private final Predicate<String> acceptable;
    private final LinkProbe.Prober prober;
    private final HostThrottle throttle;
    private final LongSupplier clock;
    private final Runnable onChange;
    private final Map<String, Entry> entries = new ConcurrentHashMap<>();
    private final Set<String> inFlight = ConcurrentHashMap.newKeySet();
    private volatile boolean dirty;
    private volatile boolean stopped;
    private volatile long startAfter;
    private volatile long lastOverflowLog;
    private ExecutorService workers;
    private Thread scheduler;

    /**
     * @param acceptable 検査してよい URL か ({@link LinkProbe#strictParse} を使う)
     * @param onChange   結果が変わったときに呼ぶ (まとめて反映するのは呼び出し側)
     */
    LinkChecker(Logger log, Path file, LinkSettings cfg, Predicate<String> acceptable, LinkProbe.Prober prober,
                HostThrottle throttle, LongSupplier clock, Runnable onChange) {
        this.log = log;
        this.file = file;
        this.cfg = cfg;
        this.acceptable = acceptable;
        this.prober = prober;
        this.throttle = throttle;
        this.clock = clock;
        this.onChange = onChange;
        this.startAfter = clock.getAsLong() + cfg.startupDelayMinutes * 60_000L;
    }

    // ---- 起動と停止 ----

    /** 保存した結果を読んで、検査を始める (専用のデーモンスレッド) */
    public synchronized void start() {
        load();
        if (scheduler != null) return;
        stopped = false;
        workers = Executors.newFixedThreadPool(LinkSettings.clamp(cfg.concurrency, 1, 4), r -> {
            final Thread t = new Thread(r, "BlueMapMarumaSign-linkcheck");
            t.setDaemon(true);
            t.setPriority(Thread.MIN_PRIORITY);
            return t;
        });
        scheduler = new Thread(this::run, "BlueMapMarumaSign-linkcheck-scheduler");
        scheduler.setDaemon(true);
        scheduler.setPriority(Thread.MIN_PRIORITY);
        scheduler.start();
    }

    /** 止める (実行中の検査は中断して、開いているソケットを閉じる。閉じないと、止まっている read が残る)。結果を保存する */
    public synchronized void stop() {
        stopped = true;
        if (scheduler != null) scheduler.interrupt();
        scheduler = null;
        if (workers != null) workers.shutdownNow();
        workers = null;
        try {
            prober.shutdown();
        } catch (RuntimeException e) {
            log.warning("Link check shutdown error: " + e.getClass().getSimpleName());
        }
        save();
    }

    private void run() {
        long lastSave = clock.getAsLong();
        try {
            while (!stopped) {
                final long now = clock.getAsLong();
                try {
                    step(now);
                    if (dirty && now - lastSave > 60_000) {
                        save();
                        lastSave = now;
                    }
                } catch (RuntimeException e) {
                    log.warning("Link check scheduler error: " + e.getClass().getSimpleName());
                }
                Thread.sleep(1000);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ---- 追跡 ----

    /**
     * いま看板にある URL を知らせる。新しいものは PENDING で追加する。
     * 先に、すでにある URL を「いま看板にある」と記録してから、新しいものを足す (上限を超えたら、
     * いま看板にない URL (しばらく知らされていないもの) から落とし、なければ古い PENDING から落とす)。
     */
    public void track(Collection<String> urls) {
        final long now = clock.getAsLong();
        final List<String> fresh = new ArrayList<>();
        for (String url : urls) {
            if (url == null) continue;
            final Entry e = entries.get(url);
            if (e != null) {
                if (now - e.state.lastSeenAt() > SEEN_REFRESH_MILLIS) e.state = e.state.withSeen(now);
            } else {
                fresh.add(url);
            }
        }
        for (String url : fresh) {
            if (entries.containsKey(url) || !acceptable.test(url)) continue;
            if (entries.size() >= cfg.maxTrackedUrls && !makeRoom(now)) continue;
            entries.putIfAbsent(url, new Entry(LinkState.pending(now)));
            dirty = true;
        }
    }

    private static final long SEEN_REFRESH_MILLIS = 30 * 60_000L;
    // これより長く知らされていない URL は、いま看板にない (看板が壊された・書き換えられた)
    private static final long STALE_MILLIS = 2 * 3_600_000L;
    private volatile long roomFailedAt;

    // 少し空ける。空けられなければ false (続けて失敗したら、しばらく探さない: 毎回全部を調べて CPU を使わないため)
    private boolean makeRoom(long now) {
        if (now - roomFailedAt < 60_000L) return false;
        final List<Map.Entry<String, Entry>> stale = new ArrayList<>();
        final List<Map.Entry<String, Entry>> pending = new ArrayList<>();
        for (Map.Entry<String, Entry> e : entries.entrySet()) {
            if (inFlight.contains(e.getKey())) continue;
            final LinkState s = e.getValue().state;
            if (now - s.lastSeenAt() > STALE_MILLIS) stale.add(e);
            else if (s.status() == LinkStatus.PENDING) pending.add(e);
        }
        final List<Map.Entry<String, Entry>> victims = stale.isEmpty() ? pending : stale;
        if (victims.isEmpty()) {
            roomFailedAt = now;
            return false;
        }
        victims.sort((a, b) -> Long.compare(a.getValue().state.lastSeenAt(), b.getValue().state.lastSeenAt()));
        final int drop = Math.max(1, Math.min(victims.size(), cfg.maxTrackedUrls / 10));
        for (int i = 0; i < drop; i++) entries.remove(victims.get(i).getKey());
        if (now - lastOverflowLog > 60_000) {
            lastOverflowLog = now;
            log.warning("Link check queue is full (" + cfg.maxTrackedUrls + "); dropped " + drop + (stale.isEmpty() ? " oldest pending" : " stale") + " URLs");
        }
        return true;
    }
    /** 新しい・編集された URL を先に検査する */
    public void prioritize(String url) {
        if (url == null) return;
        track(List.of(url));
        final Entry e = entries.get(url);
        if (e != null && e.state.status() == LinkStatus.PENDING) e.priority = true;
    }

    public LinkState state(String url) {
        final Entry e = entries.get(url);
        return e == null ? null : e.state;
    }

    /** すぐ再検査する (追跡している URL だけ)。当てはまった件数 */
    public int recheck(String url) {
        final Entry e = entries.get(url);
        if (e == null) return 0;
        e.state = e.state.withNext(0);
        e.priority = true;
        dirty = true;
        return 1;
    }

    public int recheckAllBroken() {
        int n = 0;
        for (Entry e : entries.values()) {
            if (e.state.status() == LinkStatus.BROKEN) {
                e.state = e.state.withNext(0);
                e.priority = true;
                n++;
            }
        }
        if (n > 0) dirty = true;
        return n;
    }

    public Counts counts() {
        final EnumMap<LinkStatus, Integer> c = new EnumMap<>(LinkStatus.class);
        for (Entry e : entries.values()) c.merge(e.state.status(), 1, Integer::sum);
        return new Counts(c.getOrDefault(LinkStatus.OK, 0), c.getOrDefault(LinkStatus.BROKEN, 0),
                c.getOrDefault(LinkStatus.UNKNOWN, 0), c.getOrDefault(LinkStatus.PENDING, 0), inFlight.size(),
                throttle.globalUsed(clock.getAsLong()));
    }

    public int tracked() {
        return entries.size();
    }

    // ---- 検査の割り当て ----

    /** 検査してよいものがあれば、空きの分だけ始める。始めた数 (テストからも呼ぶ) */
    int step(long now) {
        if (now < startAfter || stopped) return 0;
        int slots = LinkSettings.clamp(cfg.concurrency, 1, 4) - inFlight.size();
        if (slots <= 0 || !throttle.globalAllowed(now)) return 0;
        // 期限が来ていて、ホストに空きがあるもののうち、先に検査するもの (優先 → 期限の早い順)
        final List<Map.Entry<String, Entry>> due = new ArrayList<>();
        for (Map.Entry<String, Entry> e : entries.entrySet()) {
            final LinkState s = e.getValue().state;
            if (s.nextCheckAt() > now || inFlight.contains(e.getKey())) continue;
            due.add(e);
        }
        due.sort((a, b) -> {
            final int p = Boolean.compare(b.getValue().priority, a.getValue().priority);
            return p != 0 ? p : Long.compare(a.getValue().state.nextCheckAt(), b.getValue().state.nextCheckAt());
        });
        int started = 0;
        final Set<String> usedHosts = new java.util.HashSet<>();
        for (Map.Entry<String, Entry> e : due) {
            if (slots <= 0 || !throttle.globalAllowed(now)) break;
            final String host = hostOf(e.getKey());
            if (host == null) {
                entries.remove(e.getKey());
                continue;
            }
            // 同じ回に同じホストを 2 つ始めない (間隔は始めたあとに予約されるため)
            if (usedHosts.contains(host) || !throttle.isReady(host, now)) continue;
            usedHosts.add(host);
            if (!inFlight.add(e.getKey())) continue;
            throttle.globalRecord(now);
            slots--;
            started++;
            submit(e.getKey(), e.getValue());
        }
        return started;
    }

    private static String hostOf(String url) {
        try {
            return URI.create(url).getHost();
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private void submit(String url, Entry entry) {
        final ExecutorService w = workers;
        final Runnable task = () -> execute(url, entry);
        if (w == null) {
            task.run();
            return;
        }
        try {
            w.execute(task);
        } catch (RuntimeException e) {
            inFlight.remove(url);
        }
    }

    /** 1 件の検査 (テストでは step から同じスレッドで呼ばれる) */
    private void execute(String url, Entry entry) {
        try {
            LinkProbe.Result result;
            try {
                result = prober.probe(url);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (RuntimeException e) {
                log.warning("Link check failed for " + SafeText.logUrl(url) + ": " + e.getClass().getSimpleName());
                result = LinkProbe.Result.unknown(LinkReason.UNEXPECTED_RESPONSE, 0, true);
            }
            final LinkState before = entry.state;
            final LinkState after = before.apply(result, clock.getAsLong(), cfg, ThreadLocalRandom.current().nextDouble(-1, 1));
            entry.state = after;
            entry.priority = false;
            dirty = true;
            if (before.status() != after.status() || before.reason() != after.reason()) {
                try {
                    onChange.run();
                } catch (RuntimeException e) {
                    log.warning("Link check callback failed: " + e.getClass().getSimpleName());
                }
            }
        } finally {
            inFlight.remove(url);
        }
    }

    // ---- 保存と読み込み ----

    public synchronized void save() {
        dirty = false;
        final long now = clock.getAsLong();
        final JsonObject root = new JsonObject();
        root.addProperty("version", FILE_VERSION);
        final JsonObject links = new JsonObject();
        for (Map.Entry<String, Entry> e : entries.entrySet()) {
            final LinkState s = e.getValue().state;
            if (now - s.lastSeenAt() > PRUNE_AFTER_MILLIS) {
                entries.remove(e.getKey());
                continue;
            }
            final JsonObject o = new JsonObject();
            o.addProperty("status", s.status().name());
            o.addProperty("httpCode", s.httpCode());
            o.addProperty("reason", s.reason().name());
            o.addProperty("firstFailedAt", s.firstFailedAt());
            o.addProperty("lastFailedAt", s.lastFailedAt());
            o.addProperty("lastOkAt", s.lastOkAt());
            o.addProperty("lastCheckedAt", s.lastCheckedAt());
            o.addProperty("consecutiveFailures", s.consecutiveFailures());
            o.addProperty("nextCheckAt", s.nextCheckAt());
            o.addProperty("lastSeenAt", s.lastSeenAt());
            links.add(e.getKey(), o);
        }
        root.add("links", links);
        try {
            Files.createDirectories(file.getParent());
            final Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            try (Writer w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
                new Gson().toJson(root, w);
            }
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            dirty = true;
            log.warning("Could not save link check results: " + e.getClass().getSimpleName());
        }
    }

    /** 読めなければ (壊れている・大きすぎる) 退避して、空から始める。読んだ値は、型と範囲を確かめてから使う */
    synchronized void load() {
        if (!Files.isRegularFile(file)) return;
        final long now = clock.getAsLong();
        try {
            if (Files.size(file) > MAX_FILE_BYTES) throw new IOException("too large");
            final Map<String, Entry> loaded = new java.util.HashMap<>();
            try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                final JsonObject root = JsonParser.parseReader(r).getAsJsonObject();
                if (root.get("version").getAsInt() != FILE_VERSION) throw new IOException("version");
                final JsonObject links = root.getAsJsonObject("links");
                if (links.size() > MAX_FILE_ENTRIES) throw new IOException("too many entries");
                for (Map.Entry<String, JsonElement> e : links.entrySet()) {
                    final String url = e.getKey();
                    if (!acceptable.test(url)) continue; // 今の規則を通らない URL は読まない
                    loaded.put(url, new Entry(readState(e.getValue().getAsJsonObject(), now)));
                }
            }
            entries.putAll(loaded);
        } catch (IOException | RuntimeException e) {
            final Path backup = file.resolveSibling(file.getFileName() + ".corrupt-" + now);
            try {
                Files.move(file, backup, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException ignored) {
                // 退避できなくても、空から始める
            }
            entries.clear();
            log.warning("Link check results could not be read (" + e.getClass().getSimpleName() + "); moved aside and starting empty");
        }
    }

    private static LinkState readState(JsonObject o, long now) {
        final LinkStatus status = LinkStatus.valueOf(o.get("status").getAsString());
        final LinkReason reason = LinkReason.valueOf(o.get("reason").getAsString());
        final int code = boundedInt(o, "httpCode", 0, 599);
        final int failures = boundedInt(o, "consecutiveFailures", 0, 1_000_000);
        return new LinkState(status, reason, code,
                boundedLong(o, "firstFailedAt", now), boundedLong(o, "lastFailedAt", now), boundedLong(o, "lastOkAt", now),
                boundedLong(o, "lastCheckedAt", now), failures, boundedLong(o, "nextCheckAt", now + YEAR), boundedLong(o, "lastSeenAt", now));
    }

    private static int boundedInt(JsonObject o, String key, int lo, int hi) {
        final int v = o.get(key).getAsInt();
        if (v < lo || v > hi) throw new IllegalArgumentException("range");
        return v;
    }

    private static long boundedLong(JsonObject o, String key, long hi) {
        final long v = o.get(key).getAsLong();
        if (v < 0 || v > hi + YEAR) throw new IllegalArgumentException("range");
        return v;
    }

    /** コマンド用の写し (URL と状態) */
    public Map<String, LinkState> snapshot() {
        final Map<String, LinkState> copy = new java.util.HashMap<>();
        for (Map.Entry<String, Entry> e : entries.entrySet()) copy.put(e.getKey(), e.getValue().state);
        return copy;
    }
}
