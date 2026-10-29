package net.torosaba.marumasign.companion

import java.net.URI
import java.util.function.LongSupplier

/**
 * 看板の URL が生きているかを調べる (1 つの URL につき 1 回の検査)。
 *
 * 流れ: HEAD → (2xx/3xx 以外なら) 先頭 1 KB の Range つき GET で確かめ直す → 転送は自前で最大 N 回まで追う。
 * ホップごとに、URL の検査 ([UrlFilter] とポートの制限)、名前の解決、アドレスの検査 ([SafeHttp]) をやり直す。
 * https から http への転送は、判定には使うが、その先の本文は読まない (GET に切り替えない)。
 */
class LinkProbe internal constructor(
    private val http: SafeHttp,
    private val filter: UrlFilter,
    private val cfg: LinkSettings,
    private val throttle: HostThrottle,
    ports: Set<Int>?,
    private val clock: LongSupplier,
    private val sleeper: Sleeper,
) {

    enum class Verdict { OK, BROKEN, UNKNOWN, DEFER }

    /** 検査の結果。countsAsFailure は、UNKNOWN のとき連続した失敗の数に入れるか */
    data class Result(
        val verdict: Verdict,
        val reason: LinkReason,
        val httpCode: Int,
        val countsAsFailure: Boolean,
        val retryAfterMillis: Long,
    ) {
        companion object {
            internal fun ok(code: Int): Result {
                return Result(Verdict.OK, LinkReason.OK, code, false, 0)
            }

            internal fun broken(reason: LinkReason, code: Int): Result {
                return Result(Verdict.BROKEN, reason, code, true, 0)
            }

            internal fun unknown(reason: LinkReason, code: Int, counts: Boolean): Result {
                return Result(Verdict.UNKNOWN, reason, code, counts, 0)
            }

            internal fun defer(millis: Long): Result {
                return Result(Verdict.DEFER, LinkReason.RATE_LIMITED, 0, false, millis)
            }
        }
    }

    /** 検査する処理 (スケジューラーのテストで差し替える) */
    fun interface Prober {
        @Throws(InterruptedException::class)
        fun probe(url: String): Result

        /** 止めるとき: 実行中の検査のソケットを閉じる */
        fun shutdown() {
        }
    }

    internal fun interface Sleeper {
        @Throws(InterruptedException::class)
        fun sleep(millis: Long)
    }

    private val ports: Set<Int> = ports ?: DEFAULT_PORTS

    // ---- URL の確認 ----

    /**
     * 検査してよい URL なら URI、だめなら null。看板の URL の確認 ([UrlFilter]: http/https、ユーザー情報なし、
     * 空白・制御文字・バックスラッシュなし、2000 文字まで、変則の IP の書き方や内部の名前は拒否) に加えて、ポートは 80/443/8080/8443 だけ。
     */
    internal fun strictParse(url: String?): URI? {
        val uri = filter.parse(url) ?: return null
        if (uri.port != -1 && !ports.contains(uri.port)) return null
        return uri
    }

    // ---- 検査 ----

    /** スケジューラーに渡す形 (止めるときは、開いているソケットを閉じる) */
    fun asProber(): Prober {
        return object : Prober {
            override fun probe(url: String): Result {
                return this@LinkProbe.probe(url)
            }

            override fun shutdown() {
                http.close()
            }
        }
    }

    @Throws(InterruptedException::class)
    fun probe(url: String): Result {
        val start = strictParse(url) ?: return Result.unknown(LinkReason.UNSAFE_URL, 0, false)
        val ext = MediaKind.extension(start)
        val mediaExtension = ext.isNotEmpty() && MediaKind.of(ext) != MediaKind.OTHER
        val visited = HashSet<String>()
        var cur: URI = start
        var downgraded = false
        for (hop in 0..cfg.maxRedirects) {
            if (!visited.add(loopKey(cur))) return Result.broken(LinkReason.REDIRECT_LOOP, 0)
            val host = cur.host

            var gate = acquire(host)
            if (gate != null) return gate
            var resp: SafeHttp.Response
            try {
                resp = http.request("HEAD", cur, null)
            } catch (f: SafeHttp.HttpFailure) {
                return fromFailure(f, hop)
            }
            var status = resp.status
            val good = status >= 200 && status < 400
            if (!good && status != 429 && status != 410) {
                if (!downgraded) {
                    // HEAD を受け付けないサーバーが多いので、先頭だけの GET で確かめ直す
                    gate = acquire(host)
                    if (gate != null) return gate
                    try {
                        resp = http.request("GET", cur, "bytes=0-1023")
                    } catch (f: SafeHttp.HttpFailure) {
                        return fromFailure(f, hop)
                    }
                    status = resp.status
                }
            }
            if (status == 429) {
                val wait = retryAfterMillis(resp.retryAfter)
                throttle.penalize(host, clock.asLong + wait)
                return Result(Verdict.UNKNOWN, LinkReason.RATE_LIMITED, 429, false, wait)
            }
            if (status == 200 || status == 206 || status == 204 || status == 416) {
                if (isRemovedPlaceholder(cur)) return Result.broken(LinkReason.REMOVED_PLACEHOLDER, status)
                val type = resp.contentType
                if (mediaExtension && (type == "text/html" || type == "application/xhtml+xml")) {
                    return Result.broken(LinkReason.HTML_INSTEAD_OF_MEDIA, status)
                }
                return Result.ok(status)
            }
            if (status == 301 || status == 302 || status == 303 || status == 307 || status == 308) {
                val location = resp.location ?: return Result.unknown(LinkReason.UNEXPECTED_RESPONSE, status, true)
                val next: URI = try {
                    cur.resolve(location)
                } catch (e: IllegalArgumentException) {
                    return Result.unknown(LinkReason.REDIRECT_UNSAFE, status, false)
                }
                val checked = strictParse(next.toString()) ?: return Result.unknown(LinkReason.REDIRECT_UNSAFE, status, false)
                if (isRemovedPlaceholder(checked)) return Result.broken(LinkReason.REMOVED_PLACEHOLDER, status)
                if ("https".equals(cur.scheme, ignoreCase = true) && "http".equals(checked.scheme, ignoreCase = true)) downgraded = true
                cur = checked
                continue
            }
            if (status == 404) return Result.broken(LinkReason.HTTP_404, status)
            if (status == 410) return Result.broken(LinkReason.HTTP_410, status)
            if (status == 403) {
                return if (cfg.treat403AsBroken) Result.broken(LinkReason.ACCESS_DENIED, status)
                else Result.unknown(LinkReason.ACCESS_DENIED, status, false)
            }
            if (status == 401) return Result.unknown(LinkReason.ACCESS_DENIED, status, false)
            if (status >= 500) return Result.unknown(LinkReason.SERVER_ERROR, status, true)
            return Result.unknown(LinkReason.UNEXPECTED_RESPONSE, status, true)
        }
        return Result.unknown(LinkReason.REDIRECT_LIMIT, 0, true)
    }

    /** そのホストへの順番を予約して、時間が来るまで待つ。上限に達している・待ちが長すぎるときは DEFER を返す */
    @Throws(InterruptedException::class)
    private fun acquire(host: String): Result? {
        val now = clock.asLong
        val at = throttle.reserve(host, now)
        if (at < 0) return Result.defer(3_600_000L)
        val wait = at - now
        if (wait > MAX_THROTTLE_WAIT_MILLIS) return Result.defer(wait)
        if (wait > 0) sleeper.sleep(wait)
        return null
    }

    private fun isRemovedPlaceholder(uri: URI): Boolean {
        val rule = cfg.ruleFor(uri.host)
        if (rule == null || uri.path == null) return false
        for (p in rule.removedPaths) if (p.equals(uri.path, ignoreCase = true)) return true
        return false
    }

    companion object {
        private val DEFAULT_PORTS: Set<Int> = setOf(80, 443, 8080, 8443)
        private const val MAX_THROTTLE_WAIT_MILLIS = 15_000L

        internal fun create(cfg: LinkSettings, filter: UrlFilter, throttle: HostThrottle): LinkProbe {
            val o = SafeHttp.Options()
            o.connectTimeoutMillis = LinkSettings.clamp(cfg.connectTimeoutSeconds, 1, 30) * 1000
            o.totalTimeoutMillis = LinkSettings.clamp(cfg.totalTimeoutSeconds, 2, 120) * 1000
            o.maxBodyBytes = LinkSettings.clamp(cfg.maxBodyKb, 1, 256) * 1024
            o.userAgent = LinkSettings.cleanUserAgent(cfg.userAgent)
            o.denyList = AddressDenyList(cfg.extraBlockedAddresses)
            return LinkProbe(SafeHttp(o), filter, cfg, throttle, DEFAULT_PORTS, { System.currentTimeMillis() }, { Thread.sleep(it) })
        }

        private fun loopKey(uri: URI): String {
            val path = if (uri.rawPath == null) "" else uri.rawPath
            val query = if (uri.rawQuery == null) "" else "?" + uri.rawQuery
            return uri.scheme.lowercase() + "://" + uri.host.lowercase() + ":" + uri.port + path + query
        }

        internal fun retryAfterMillis(header: String?): Long {
            var seconds = 3600L
            if (header != null) {
                try {
                    seconds = java.lang.Long.parseLong(header.trim { it <= ' ' })
                } catch (e: NumberFormatException) {
                    // 日付の書き方は読まず、既定の時間待つ
                }
            }
            return Math.max(60L, Math.min(seconds, 24L * 3600)) * 1000L
        }

        private fun fromFailure(f: SafeHttp.HttpFailure, hop: Int): Result {
            return when (f.kind) {
                SafeHttp.Kind.DNS -> Result.broken(LinkReason.DNS_FAILED, 0)
                SafeHttp.Kind.CONNECT_REFUSED -> Result.broken(LinkReason.CONNECT_REFUSED, 0)
                SafeHttp.Kind.TLS -> Result.broken(LinkReason.TLS_ERROR, 0)
                SafeHttp.Kind.UNSAFE_ADDRESS -> Result.unknown(if (hop == 0) LinkReason.UNSAFE_URL else LinkReason.REDIRECT_UNSAFE, 0, false)
                SafeHttp.Kind.DNS_BUSY -> Result.defer(5 * 60_000L) // 名前を引く枠が埋まっている。こちらの失敗ではないので、あとでやり直す
                SafeHttp.Kind.DNS_TIMEOUT, SafeHttp.Kind.CONNECT_TIMEOUT, SafeHttp.Kind.CONNECT_FAILED, SafeHttp.Kind.TIMEOUT ->
                    Result.unknown(LinkReason.TIMEOUT, 0, true)
                SafeHttp.Kind.RESET -> Result.unknown(LinkReason.CONNECTION_RESET, 0, true)
                SafeHttp.Kind.BAD_RESPONSE -> Result.unknown(LinkReason.UNEXPECTED_RESPONSE, 0, true)
                SafeHttp.Kind.BAD_REQUEST -> Result.unknown(LinkReason.UNSAFE_URL, 0, false)
            }
        }
    }
}
