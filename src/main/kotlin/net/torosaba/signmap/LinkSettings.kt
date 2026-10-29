package net.torosaba.signmap

/** リンク切れ検査の設定 (config.yml の link-check。Bukkit に依存しない)。範囲は読み込み時に収める */
class LinkSettings {

    /** ホストごとの決まり (ホストとその下のサブドメインに当てる) */
    data class HostRule(
        val host: String,
        val minIntervalSeconds: Int,
        val removedPaths: List<String>,
    )

    var enabled = true
    var concurrency = 2
    var startupDelayMinutes = 5
    var perHourLimit = 600
    var hostPerHourLimit = 60
    var hostMinIntervalSeconds = 2
    var connectTimeoutSeconds = 5
    var totalTimeoutSeconds = 20
    var maxBodyKb = 64
    var maxRedirects = 5
    var recheckOkDays = 7
    var recheckBrokenHours = 24
    /** UNKNOWN の再検査の間隔 (時間)。連続して失敗するほど長く、最後の値で頭打ち */
    var unknownBackoffHours: List<Int> = listOf(6, 12, 24)
    var brokenAfterFailures = 3
    var brokenAfterHours = 24
    var treat403AsBroken = false
    var maxTrackedUrls = 20_000
    /** 公開のアドレスに見えても、つながないアドレス・範囲 (サーバー自身の公開アドレスなど。「203.0.113.7」「198.51.100.0/24」) */
    var extraBlockedAddresses: MutableList<String> = ArrayList()
    var userAgent = "BlueMapMarumaSign-LinkCheck/1.0"
    var hostRules: MutableList<HostRule> = ArrayList(listOf(
        HostRule("imgur.com", 5, listOf("/removed.png"))))

    /** そのホストに当てはまる決まり (いちばん長く一致したもの)。なければ null */
    fun ruleFor(host: String?): HostRule? {
        if (host == null) return null
        val h = host.lowercase()
        var best: HostRule? = null
        for (r in hostRules) {
            if (h == r.host || h.endsWith("." + r.host)) {
                if (best == null || r.host.length > best.host.length) best = r
            }
        }
        return best
    }

    fun minIntervalMillis(host: String?): Int {
        val r = ruleFor(host)
        return Math.max(hostMinIntervalSeconds, r?.minIntervalSeconds ?: 0) * 1000
    }

    companion object {
        internal fun clamp(v: Int, lo: Int, hi: Int): Int {
            return Math.max(lo, Math.min(hi, v))
        }

        /** User-Agent は印字できる ASCII だけ、100 文字まで (ヘッダーの混入を防ぐ) */
        internal fun cleanUserAgent(ua: String?): String {
            if (ua == null) return "BlueMapMarumaSign-LinkCheck/1.0"
            val sb = StringBuilder()
            var i = 0
            while (i < ua.length && sb.length < 100) {
                val c = ua[i]
                if (c.code >= 0x20 && c.code < 0x7F) sb.append(c)
                i++
            }
            val s = sb.toString().trim { it <= ' ' }
            return if (s.isEmpty()) "BlueMapMarumaSign-LinkCheck/1.0" else s
        }
    }
}
