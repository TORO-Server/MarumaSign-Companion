package net.torosaba.signmap;

import java.net.URI;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.function.LongSupplier;

/**
 * 看板の URL が生きているかを調べる (1 つの URL につき 1 回の検査)。
 * <p>
 * 流れ: HEAD → (2xx/3xx 以外なら) 先頭 1 KB の Range つき GET で確かめ直す → 転送は自前で最大 N 回まで追う。
 * ホップごとに、URL の検査 ({@link UrlFilter} とポートの制限)、名前の解決、アドレスの検査 ({@link SafeHttp}) をやり直す。
 * https から http への転送は、判定には使うが、その先の本文は読まない (GET に切り替えない)。
 */
public final class LinkProbe {

    public enum Verdict {OK, BROKEN, UNKNOWN, DEFER}

    /** 検査の結果。countsAsFailure は、UNKNOWN のとき連続した失敗の数に入れるか */
    public record Result(Verdict verdict, LinkReason reason, int httpCode, boolean countsAsFailure, long retryAfterMillis) {
        static Result ok(int code) {
            return new Result(Verdict.OK, LinkReason.OK, code, false, 0);
        }

        static Result broken(LinkReason reason, int code) {
            return new Result(Verdict.BROKEN, reason, code, true, 0);
        }

        static Result unknown(LinkReason reason, int code, boolean counts) {
            return new Result(Verdict.UNKNOWN, reason, code, counts, 0);
        }

        static Result defer(long millis) {
            return new Result(Verdict.DEFER, LinkReason.RATE_LIMITED, 0, false, millis);
        }
    }

    /** 検査する処理 (スケジューラーのテストで差し替える) */
    public interface Prober {
        Result probe(String url) throws InterruptedException;

        /** 止めるとき: 実行中の検査のソケットを閉じる */
        default void shutdown() {
        }
    }

    interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }

    private static final Set<Integer> DEFAULT_PORTS = Set.of(80, 443, 8080, 8443);
    private static final long MAX_THROTTLE_WAIT_MILLIS = 15_000;

    private final SafeHttp http;
    private final UrlFilter filter;
    private final LinkSettings cfg;
    private final HostThrottle throttle;
    private final Set<Integer> ports;
    private final LongSupplier clock;
    private final Sleeper sleeper;

    LinkProbe(SafeHttp http, UrlFilter filter, LinkSettings cfg, HostThrottle throttle, Set<Integer> ports, LongSupplier clock, Sleeper sleeper) {
        this.http = http;
        this.filter = filter;
        this.cfg = cfg;
        this.throttle = throttle;
        this.ports = ports == null ? DEFAULT_PORTS : ports;
        this.clock = clock;
        this.sleeper = sleeper;
    }

    static LinkProbe create(LinkSettings cfg, UrlFilter filter, HostThrottle throttle) {
        final SafeHttp.Options o = new SafeHttp.Options();
        o.connectTimeoutMillis = LinkSettings.clamp(cfg.connectTimeoutSeconds, 1, 30) * 1000;
        o.totalTimeoutMillis = LinkSettings.clamp(cfg.totalTimeoutSeconds, 2, 120) * 1000;
        o.maxBodyBytes = LinkSettings.clamp(cfg.maxBodyKb, 1, 256) * 1024;
        o.userAgent = LinkSettings.cleanUserAgent(cfg.userAgent);
        o.denyList = new AddressDenyList(cfg.extraBlockedAddresses);
        return new LinkProbe(new SafeHttp(o), filter, cfg, throttle, DEFAULT_PORTS, System::currentTimeMillis, Thread::sleep);
    }

    // ---- URL の確認 ----

    /**
     * 検査してよい URL なら URI、だめなら null。看板の URL の確認 ({@link UrlFilter}: http/https、ユーザー情報なし、
     * 空白・制御文字・バックスラッシュなし、2000 文字まで、変則の IP の書き方や内部の名前は拒否) に加えて、ポートは 80/443/8080/8443 だけ。
     */
    URI strictParse(String url) {
        final URI uri = filter.parse(url);
        if (uri == null) return null;
        if (uri.getPort() != -1 && !ports.contains(uri.getPort())) return null;
        return uri;
    }

    // ---- 検査 ----

    /** スケジューラーに渡す形 (止めるときは、開いているソケットを閉じる) */
    public Prober asProber() {
        return new Prober() {
            @Override
            public Result probe(String url) throws InterruptedException {
                return LinkProbe.this.probe(url);
            }

            @Override
            public void shutdown() {
                http.close();
            }
        };
    }

    public Result probe(String url) throws InterruptedException {
        final URI start = strictParse(url);
        if (start == null) return Result.unknown(LinkReason.UNSAFE_URL, 0, false);
        final String ext = MediaKind.extension(start);
        final boolean mediaExtension = !ext.isEmpty() && MediaKind.of(ext) != MediaKind.OTHER;
        final Set<String> visited = new HashSet<>();
        URI cur = start;
        boolean downgraded = false;
        for (int hop = 0; hop <= cfg.maxRedirects; hop++) {
            if (!visited.add(loopKey(cur))) return Result.broken(LinkReason.REDIRECT_LOOP, 0);
            final String host = cur.getHost();

            Result gate = acquire(host);
            if (gate != null) return gate;
            SafeHttp.Response resp;
            try {
                resp = http.request("HEAD", cur, null);
            } catch (SafeHttp.HttpFailure f) {
                return fromFailure(f, hop);
            }
            int status = resp.status();
            final boolean good = status >= 200 && status < 400;
            if (!good && status != 429 && status != 410) {
                if (!downgraded) {
                    // HEAD を受け付けないサーバーが多いので、先頭だけの GET で確かめ直す
                    gate = acquire(host);
                    if (gate != null) return gate;
                    try {
                        resp = http.request("GET", cur, "bytes=0-1023");
                    } catch (SafeHttp.HttpFailure f) {
                        return fromFailure(f, hop);
                    }
                    status = resp.status();
                }
            }
            if (status == 429) {
                final long wait = retryAfterMillis(resp.retryAfter());
                throttle.penalize(host, clock.getAsLong() + wait);
                return new Result(Verdict.UNKNOWN, LinkReason.RATE_LIMITED, 429, false, wait);
            }
            if (status == 200 || status == 206 || status == 204 || status == 416) {
                if (isRemovedPlaceholder(cur)) return Result.broken(LinkReason.REMOVED_PLACEHOLDER, status);
                final String type = resp.contentType();
                if (mediaExtension && (type.equals("text/html") || type.equals("application/xhtml+xml"))) {
                    return Result.broken(LinkReason.HTML_INSTEAD_OF_MEDIA, status);
                }
                return Result.ok(status);
            }
            if (status == 301 || status == 302 || status == 303 || status == 307 || status == 308) {
                if (resp.location() == null) return Result.unknown(LinkReason.UNEXPECTED_RESPONSE, status, true);
                final URI next;
                try {
                    next = cur.resolve(resp.location());
                } catch (IllegalArgumentException e) {
                    return Result.unknown(LinkReason.REDIRECT_UNSAFE, status, false);
                }
                final URI checked = strictParse(next.toString());
                if (checked == null) return Result.unknown(LinkReason.REDIRECT_UNSAFE, status, false);
                if (isRemovedPlaceholder(checked)) return Result.broken(LinkReason.REMOVED_PLACEHOLDER, status);
                if ("https".equalsIgnoreCase(cur.getScheme()) && "http".equalsIgnoreCase(checked.getScheme())) downgraded = true;
                cur = checked;
                continue;
            }
            if (status == 404) return Result.broken(LinkReason.HTTP_404, status);
            if (status == 410) return Result.broken(LinkReason.HTTP_410, status);
            if (status == 403) {
                return cfg.treat403AsBroken ? Result.broken(LinkReason.ACCESS_DENIED, status)
                        : Result.unknown(LinkReason.ACCESS_DENIED, status, false);
            }
            if (status == 401) return Result.unknown(LinkReason.ACCESS_DENIED, status, false);
            if (status >= 500) return Result.unknown(LinkReason.SERVER_ERROR, status, true);
            return Result.unknown(LinkReason.UNEXPECTED_RESPONSE, status, true);
        }
        return Result.unknown(LinkReason.REDIRECT_LIMIT, 0, true);
    }

    /** そのホストへの順番を予約して、時間が来るまで待つ。上限に達している・待ちが長すぎるときは DEFER を返す */
    private Result acquire(String host) throws InterruptedException {
        final long now = clock.getAsLong();
        final long at = throttle.reserve(host, now);
        if (at < 0) return Result.defer(3_600_000L);
        final long wait = at - now;
        if (wait > MAX_THROTTLE_WAIT_MILLIS) return Result.defer(wait);
        if (wait > 0) sleeper.sleep(wait);
        return null;
    }

    private static String loopKey(URI uri) {
        final String path = uri.getRawPath() == null ? "" : uri.getRawPath();
        final String query = uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery();
        return uri.getScheme().toLowerCase(Locale.ROOT) + "://" + uri.getHost().toLowerCase(Locale.ROOT) + ":" + uri.getPort() + path + query;
    }

    private boolean isRemovedPlaceholder(URI uri) {
        final LinkSettings.HostRule rule = cfg.ruleFor(uri.getHost());
        if (rule == null || uri.getPath() == null) return false;
        for (String p : rule.removedPaths()) if (p.equalsIgnoreCase(uri.getPath())) return true;
        return false;
    }

    static long retryAfterMillis(String header) {
        long seconds = 3600;
        if (header != null) {
            try {
                seconds = Long.parseLong(header.trim());
            } catch (NumberFormatException e) {
                // 日付の書き方は読まず、既定の時間待つ
            }
        }
        return Math.max(60, Math.min(seconds, 24 * 3600)) * 1000L;
    }

    private static Result fromFailure(SafeHttp.HttpFailure f, int hop) {
        return switch (f.kind) {
            case DNS -> Result.broken(LinkReason.DNS_FAILED, 0);
            case CONNECT_REFUSED -> Result.broken(LinkReason.CONNECT_REFUSED, 0);
            case TLS -> Result.broken(LinkReason.TLS_ERROR, 0);
            case UNSAFE_ADDRESS -> Result.unknown(hop == 0 ? LinkReason.UNSAFE_URL : LinkReason.REDIRECT_UNSAFE, 0, false);
            case DNS_BUSY -> Result.defer(5 * 60_000L); // 名前を引く枠が埋まっている。こちらの失敗ではないので、あとでやり直す
            case DNS_TIMEOUT, CONNECT_TIMEOUT, CONNECT_FAILED, TIMEOUT -> Result.unknown(LinkReason.TIMEOUT, 0, true);
            case RESET -> Result.unknown(LinkReason.CONNECTION_RESET, 0, true);
            case BAD_RESPONSE -> Result.unknown(LinkReason.UNEXPECTED_RESPONSE, 0, true);
            case BAD_REQUEST -> Result.unknown(LinkReason.UNSAFE_URL, 0, false);
        };
    }
}
