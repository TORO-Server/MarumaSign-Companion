package net.torosaba.signmap

import java.util.ArrayDeque
import java.util.concurrent.ThreadLocalRandom

/**
 * 同じホストへの検査の間隔と、1 時間あたりの数を抑える (他人のサイトへの負荷にならないように)。
 * 間隔は少しゆらがせる。429 を返したホストは、Retry-After (なければ既定の時間) のあいだ待たせる。
 */
internal class HostThrottle(private val cfg: LinkSettings) {

    private class Host {
        var nextFree: Long = 0
        var penalizedUntil: Long = 0
        val starts = ArrayDeque<Long>()
    }

    private val hosts = HashMap<String, Host>()
    private val global = ArrayDeque<Long>()

    private fun host(h: String, now: Long): Host {
        if (hosts.size > 5000) hosts.values.removeIf { x -> x.nextFree < now - HOUR && x.penalizedUntil < now && x.starts.isEmpty() }
        var key = h.lowercase()
        while (key.endsWith(".")) key = key.substring(0, key.length - 1) // a.com と a.com. は同じホスト
        return hosts.computeIfAbsent(key) { Host() }
    }

    /** すぐに始めてよいホストか (間隔が空いていて、止められておらず、1 時間の上限にも達していない) */
    @Synchronized
    fun isReady(h: String, now: Long): Boolean {
        val x = host(h, now)
        prune(x.starts, now)
        return x.nextFree <= now && x.penalizedUntil <= now && x.starts.size < cfg.hostPerHourLimit
    }

    /**
     * 次の 1 件を始めてよい時刻を予約する。1 時間の上限に達していたら -1。
     * 予約した時刻から、そのホストの間隔 (ゆらぎつき) のあいだは次を予約できない。
     */
    @Synchronized
    fun reserve(h: String, now: Long): Long {
        val x = host(h, now)
        prune(x.starts, now)
        if (x.starts.size >= cfg.hostPerHourLimit) return -1
        val at = Math.max(now, Math.max(x.nextFree, x.penalizedUntil))
        x.starts.addLast(at)
        val interval = cfg.minIntervalMillis(h).toLong()
        x.nextFree = at + interval + (interval * 0.3 * ThreadLocalRandom.current().nextDouble()).toLong()
        return at
    }

    /** 429 を返されたホストを待たせる */
    @Synchronized
    fun penalize(h: String, untilMillis: Long) {
        val x = host(h, untilMillis)
        x.penalizedUntil = Math.max(x.penalizedUntil, untilMillis)
    }

    // ---- 全体の 1 時間あたりの上限 ----

    @Synchronized
    fun globalAllowed(now: Long): Boolean {
        prune(global, now)
        return global.size < cfg.perHourLimit
    }

    @Synchronized
    fun globalRecord(now: Long) {
        prune(global, now)
        global.addLast(now)
    }

    @Synchronized
    fun globalUsed(now: Long): Int {
        prune(global, now)
        return global.size
    }

    companion object {
        private const val HOUR = 3_600_000L

        private fun prune(q: ArrayDeque<Long>, now: Long) {
            while (!q.isEmpty() && q.peekFirst() < now - HOUR) q.pollFirst()
        }
    }
}
