package net.torosaba.signmap

/**
 * 1 つの URL の検査の結果と、次にいつ検査するか。
 *
 * 状態の移り変わり ([apply]):
 * - OK: 失敗の記録を消す。
 * - BROKEN (404/410、名前が引けない、接続拒否、証明書エラー、画像でなく HTML、削除済みの置き換え、転送ループ): すぐ BROKEN。
 * - 一時的かもしれない失敗 (タイムアウト、5xx、接続リセット、想定外の応答、転送が多すぎる): UNKNOWN で、連続した失敗の数を数える。
 *   連続 N 回 (既定 3) で、最初の失敗から H 時間 (既定 24) 以上たっていれば BROKEN。
 * - 失敗に数えないもの (アクセス拒否、アクセス制限、転送先が未検査、検査できない URL): UNKNOWN のまま、連続の数は変えない。
 * - 前に BROKEN で、今回が UNKNOWN: BROKEN のまま (確かめられなかっただけで、復活とは言えない)。
 */
data class LinkState(
    val status: LinkStatus,
    val reason: LinkReason,
    val httpCode: Int,
    val firstFailedAt: Long,
    val lastFailedAt: Long,
    val lastOkAt: Long,
    val lastCheckedAt: Long,
    val consecutiveFailures: Int,
    val nextCheckAt: Long,
    val lastSeenAt: Long,
) {

    fun withSeen(now: Long): LinkState {
        return LinkState(status, reason, httpCode, firstFailedAt, lastFailedAt, lastOkAt, lastCheckedAt,
            consecutiveFailures, nextCheckAt, now)
    }

    fun withNext(next: Long): LinkState {
        return LinkState(status, reason, httpCode, firstFailedAt, lastFailedAt, lastOkAt, lastCheckedAt,
            consecutiveFailures, next, lastSeenAt)
    }

    /** jitter は -1..1 (再検査の間隔に少しゆらぎを入れる) */
    fun apply(r: LinkProbe.Result, now: Long, cfg: LinkSettings, jitter: Double): LinkState {
        when (r.verdict) {
            LinkProbe.Verdict.DEFER ->
                return withNext(now + Math.max(r.retryAfterMillis, 60_000L))
            LinkProbe.Verdict.OK ->
                return LinkState(LinkStatus.OK, LinkReason.OK, r.httpCode, 0, lastFailedAt, now, now, 0,
                    now + jittered(cfg.recheckOkDays * DAY, jitter), lastSeenAt)
            LinkProbe.Verdict.BROKEN -> {
                val first = if (firstFailedAt > 0 && (status == LinkStatus.BROKEN || status == LinkStatus.UNKNOWN)) firstFailedAt else now
                return LinkState(LinkStatus.BROKEN, r.reason, r.httpCode, first, now, lastOkAt, now,
                    consecutiveFailures + 1, now + jittered(cfg.recheckBrokenHours * HOUR, jitter), lastSeenAt)
            }
            else -> {
            }
        }
        // UNKNOWN
        if (!r.countsAsFailure) {
            val wait = if (r.retryAfterMillis > 0) r.retryAfterMillis else (if (r.reason == LinkReason.RATE_LIMITED) HOUR else DAY)
            if (status == LinkStatus.BROKEN) {
                return LinkState(status, reason, httpCode, firstFailedAt, lastFailedAt, lastOkAt, now, consecutiveFailures,
                    now + Math.min(wait, 6 * HOUR), lastSeenAt)
            }
            return LinkState(LinkStatus.UNKNOWN, r.reason, r.httpCode, firstFailedAt, lastFailedAt, lastOkAt, now,
                consecutiveFailures, now + wait, lastSeenAt)
        }
        val failures = consecutiveFailures + 1
        val first = if (firstFailedAt > 0 && status != LinkStatus.OK && status != LinkStatus.PENDING) firstFailedAt else now
        if (status == LinkStatus.BROKEN) {
            // 前から BROKEN。確かめられなかっただけなので、BROKEN のまま 6 時間後にもう一度
            return LinkState(LinkStatus.BROKEN, reason, httpCode, firstFailedAt, now, lastOkAt, now, failures,
                now + 6 * HOUR, lastSeenAt)
        }
        if (failures >= cfg.brokenAfterFailures && now - first >= cfg.brokenAfterHours * HOUR) {
            return LinkState(LinkStatus.BROKEN, r.reason, r.httpCode, first, now, lastOkAt, now, failures,
                now + jittered(cfg.recheckBrokenHours * HOUR, jitter), lastSeenAt)
        }
        val backoff = cfg.unknownBackoffHours
        val hours = if (backoff.isEmpty()) 6 else backoff[Math.min(failures, backoff.size) - 1]
        return LinkState(LinkStatus.UNKNOWN, r.reason, r.httpCode, first, now, lastOkAt, now, failures,
            now + jittered(hours * HOUR, jitter), lastSeenAt)
    }

    /** 地図の「要確認」に出すか: UNKNOWN で、連続して失敗している (2 回以上)、または検査を拒まれている (アクセス拒否) */
    fun needsAttention(): Boolean {
        if (status != LinkStatus.UNKNOWN) return false
        // 転送先が未検査 (内部アドレスへの転送) は、公開の地図に出さない: 出すと、「どの名前が内部のアドレスを指すか」を探る手がかりになる
        return consecutiveFailures >= 2 || reason == LinkReason.ACCESS_DENIED
    }

    companion object {
        private const val HOUR = 3_600_000L
        private const val DAY = 24 * HOUR

        fun pending(now: Long): LinkState {
            return LinkState(LinkStatus.PENDING, LinkReason.OK, 0, 0, 0, 0, 0, 0, 0, now)
        }

        private fun jittered(base: Long, jitter: Double): Long {
            return base + (base * 0.1 * Math.max(-1.0, Math.min(1.0, jitter))).toLong()
        }
    }
}
