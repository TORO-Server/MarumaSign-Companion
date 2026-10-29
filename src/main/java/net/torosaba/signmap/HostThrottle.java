package net.torosaba.signmap;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 同じホストへの検査の間隔と、1 時間あたりの数を抑える (他人のサイトへの負荷にならないように)。
 * 間隔は少しゆらがせる。429 を返したホストは、Retry-After (なければ既定の時間) のあいだ待たせる。
 */
final class HostThrottle {

    private static final long HOUR = 3_600_000L;

    private static final class Host {
        long nextFree;
        long penalizedUntil;
        final ArrayDeque<Long> starts = new ArrayDeque<>();
    }

    private final LinkSettings cfg;
    private final Map<String, Host> hosts = new HashMap<>();
    private final ArrayDeque<Long> global = new ArrayDeque<>();

    HostThrottle(LinkSettings cfg) {
        this.cfg = cfg;
    }

    private Host host(String h, long now) {
        if (hosts.size() > 5000) hosts.values().removeIf(x -> x.nextFree < now - HOUR && x.penalizedUntil < now && x.starts.isEmpty());
        String key = h.toLowerCase(Locale.ROOT);
        while (key.endsWith(".")) key = key.substring(0, key.length() - 1); // a.com と a.com. は同じホスト
        return hosts.computeIfAbsent(key, k -> new Host());
    }

    private static void prune(ArrayDeque<Long> q, long now) {
        while (!q.isEmpty() && q.peekFirst() < now - HOUR) q.pollFirst();
    }

    /** すぐに始めてよいホストか (間隔が空いていて、止められておらず、1 時間の上限にも達していない) */
    synchronized boolean isReady(String h, long now) {
        final Host x = host(h, now);
        prune(x.starts, now);
        return x.nextFree <= now && x.penalizedUntil <= now && x.starts.size() < cfg.hostPerHourLimit;
    }

    /**
     * 次の 1 件を始めてよい時刻を予約する。1 時間の上限に達していたら -1。
     * 予約した時刻から、そのホストの間隔 (ゆらぎつき) のあいだは次を予約できない。
     */
    synchronized long reserve(String h, long now) {
        final Host x = host(h, now);
        prune(x.starts, now);
        if (x.starts.size() >= cfg.hostPerHourLimit) return -1;
        final long at = Math.max(now, Math.max(x.nextFree, x.penalizedUntil));
        x.starts.addLast(at);
        final long interval = cfg.minIntervalMillis(h);
        x.nextFree = at + interval + (long) (interval * 0.3 * ThreadLocalRandom.current().nextDouble());
        return at;
    }

    /** 429 を返されたホストを待たせる */
    synchronized void penalize(String h, long untilMillis) {
        final Host x = host(h, untilMillis);
        x.penalizedUntil = Math.max(x.penalizedUntil, untilMillis);
    }

    // ---- 全体の 1 時間あたりの上限 ----

    synchronized boolean globalAllowed(long now) {
        prune(global, now);
        return global.size() < cfg.perHourLimit;
    }

    synchronized void globalRecord(long now) {
        prune(global, now);
        global.addLast(now);
    }

    synchronized int globalUsed(long now) {
        prune(global, now);
        return global.size();
    }
}
