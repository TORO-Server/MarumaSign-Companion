package net.torosaba.signmap;

/**
 * 1 つの URL の検査の結果と、次にいつ検査するか。
 * <p>
 * 状態の移り変わり ({@link #apply}):
 * <ul>
 *   <li>OK: 失敗の記録を消す。</li>
 *   <li>BROKEN (404/410、名前が引けない、接続拒否、証明書エラー、画像でなく HTML、削除済みの置き換え、転送ループ): すぐ BROKEN。</li>
 *   <li>一時的かもしれない失敗 (タイムアウト、5xx、接続リセット、想定外の応答、転送が多すぎる): UNKNOWN で、連続した失敗の数を数える。
 *       連続 N 回 (既定 3) で、最初の失敗から H 時間 (既定 24) 以上たっていれば BROKEN。</li>
 *   <li>失敗に数えないもの (アクセス拒否、アクセス制限、転送先が未検査、検査できない URL): UNKNOWN のまま、連続の数は変えない。</li>
 *   <li>前に BROKEN で、今回が UNKNOWN: BROKEN のまま (確かめられなかっただけで、復活とは言えない)。</li>
 * </ul>
 */
public record LinkState(LinkStatus status, LinkReason reason, int httpCode, long firstFailedAt, long lastFailedAt,
                        long lastOkAt, long lastCheckedAt, int consecutiveFailures, long nextCheckAt, long lastSeenAt) {

    private static final long HOUR = 3_600_000L;
    private static final long DAY = 24 * HOUR;

    public static LinkState pending(long now) {
        return new LinkState(LinkStatus.PENDING, LinkReason.OK, 0, 0, 0, 0, 0, 0, 0, now);
    }

    public LinkState withSeen(long now) {
        return new LinkState(status, reason, httpCode, firstFailedAt, lastFailedAt, lastOkAt, lastCheckedAt,
                consecutiveFailures, nextCheckAt, now);
    }

    public LinkState withNext(long next) {
        return new LinkState(status, reason, httpCode, firstFailedAt, lastFailedAt, lastOkAt, lastCheckedAt,
                consecutiveFailures, next, lastSeenAt);
    }

    /** jitter は -1..1 (再検査の間隔に少しゆらぎを入れる) */
    public LinkState apply(LinkProbe.Result r, long now, LinkSettings cfg, double jitter) {
        switch (r.verdict()) {
            case DEFER:
                return withNext(now + Math.max(r.retryAfterMillis(), 60_000L));
            case OK:
                return new LinkState(LinkStatus.OK, LinkReason.OK, r.httpCode(), 0, lastFailedAt, now, now, 0,
                        now + jittered(cfg.recheckOkDays * DAY, jitter), lastSeenAt);
            case BROKEN: {
                final long first = firstFailedAt > 0 && (status == LinkStatus.BROKEN || status == LinkStatus.UNKNOWN) ? firstFailedAt : now;
                return new LinkState(LinkStatus.BROKEN, r.reason(), r.httpCode(), first, now, lastOkAt, now,
                        consecutiveFailures + 1, now + jittered(cfg.recheckBrokenHours * HOUR, jitter), lastSeenAt);
            }
            default:
                break;
        }
        // UNKNOWN
        if (!r.countsAsFailure()) {
            final long wait = r.retryAfterMillis() > 0 ? r.retryAfterMillis() : (r.reason() == LinkReason.RATE_LIMITED ? HOUR : DAY);
            if (status == LinkStatus.BROKEN) {
                return new LinkState(status, reason, httpCode, firstFailedAt, lastFailedAt, lastOkAt, now, consecutiveFailures,
                        now + Math.min(wait, 6 * HOUR), lastSeenAt);
            }
            return new LinkState(LinkStatus.UNKNOWN, r.reason(), r.httpCode(), firstFailedAt, lastFailedAt, lastOkAt, now,
                    consecutiveFailures, now + wait, lastSeenAt);
        }
        final int failures = consecutiveFailures + 1;
        final long first = firstFailedAt > 0 && status != LinkStatus.OK && status != LinkStatus.PENDING ? firstFailedAt : now;
        if (status == LinkStatus.BROKEN) {
            // 前から BROKEN。確かめられなかっただけなので、BROKEN のまま 6 時間後にもう一度
            return new LinkState(LinkStatus.BROKEN, reason, httpCode, firstFailedAt, now, lastOkAt, now, failures,
                    now + 6 * HOUR, lastSeenAt);
        }
        if (failures >= cfg.brokenAfterFailures && now - first >= cfg.brokenAfterHours * HOUR) {
            return new LinkState(LinkStatus.BROKEN, r.reason(), r.httpCode(), first, now, lastOkAt, now, failures,
                    now + jittered(cfg.recheckBrokenHours * HOUR, jitter), lastSeenAt);
        }
        final var backoff = cfg.unknownBackoffHours;
        final int hours = backoff.isEmpty() ? 6 : backoff.get(Math.min(failures, backoff.size()) - 1);
        return new LinkState(LinkStatus.UNKNOWN, r.reason(), r.httpCode(), first, now, lastOkAt, now, failures,
                now + jittered(hours * HOUR, jitter), lastSeenAt);
    }

    private static long jittered(long base, double jitter) {
        return base + (long) (base * 0.1 * Math.max(-1, Math.min(1, jitter)));
    }

    /** 地図の「要確認」に出すか: UNKNOWN で、連続して失敗している (2 回以上)、または検査を拒まれている (アクセス拒否) */
    public boolean needsAttention() {
        if (status != LinkStatus.UNKNOWN) return false;
        // 転送先が未検査 (内部アドレスへの転送) は、公開の地図に出さない: 出すと、「どの名前が内部のアドレスを指すか」を探る手がかりになる
        return consecutiveFailures >= 2 || reason == LinkReason.ACCESS_DENIED;
    }
}
