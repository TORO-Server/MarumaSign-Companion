package net.torosaba.signmap

import java.net.InetAddress
import java.net.URI

/**
 * 看板の URL をマップに出してよいかどうか。公開サイトに出るので、名前を引かずに文字だけで判定する
 * (http / https だけ。手元や家の中のネットワークを指すもの、ブラウザーがつながない番号のものは出さない)。
 */
class UrlFilter(extraBlockedHosts: Collection<String?>) {

    private val extraBlockedHosts = HashSet<String>()

    init {
        for (host in extraBlockedHosts) {
            val h = normalizeHost(host)
            if (h.isNotEmpty()) this.extraBlockedHosts.add(h)
        }
    }

    fun isAllowed(url: String?): Boolean {
        return parse(url) != null
    }

    /** 許可できる URL なら、パースした URI を返す。だめなら null */
    fun parse(url: String?): URI? {
        if (url == null || url.isEmpty() || url.length > MAX_URL_LENGTH) return null
        for (c in url) {
            if (c <= ' ' || c.code == 0x7F || c == '"' || c == '<' || c == '>' || c == '\\' || c == '`'
                || Character.isISOControl(c) || Character.isWhitespace(c)
            ) {
                return null
            }
        }
        val uri: URI = try {
            URI(url)
        } catch (e: Exception) {
            return null
        }
        val scheme = uri.scheme ?: return null
        val lowerScheme = scheme.lowercase()
        if (lowerScheme != "http" && lowerScheme != "https") return null
        if (uri.rawUserInfo != null) return null
        val rawHost = uri.host
        if (rawHost == null || rawHost.isEmpty()) return null
        var port = uri.port
        if (port < 0) port = if (lowerScheme == "https") 443 else 80
        if (isBlockedPort(port)) return null
        if (!isAllowedHost(rawHost)) return null
        return uri
    }

    internal fun isAllowedHost(rawHost: String): Boolean {
        val host = normalizeHost(rawHost)
        if (host.isEmpty()) return false
        for (blocked in extraBlockedHosts) {
            if (host == blocked || host.endsWith(".$blocked")) return false
        }
        if (host.startsWith("[")) {
            // IPv6 のアドレスそのもの (名前は引かない)
            return isPublicIpv6Literal(host.substring(1, host.length - 1))
        }
        if (isLocalName(host)) return false
        // 最後の部分が数字や 0x で始まるものは IP アドレスの書き方 (10 進、16 進、省略形など)。決まった形のものだけ通す
        val last = host.substring(host.lastIndexOf('.') + 1)
        if (last.isEmpty()) return false
        if (Character.isDigit(last[0]) || last.startsWith("0x")) {
            val v4 = strictIpv4(host)
            return v4 != null && !isLocalIpv4(v4)
        }
        // ドットのない名前 (ルーターなど) は出さない
        return host.indexOf('.') > 0
    }

    companion object {
        const val MAX_URL_LENGTH = 2000

        private fun normalizeHost(host: String?): String {
            var h = host?.trim { it <= ' ' }?.lowercase() ?: ""
            while (h.endsWith(".")) h = h.substring(0, h.length - 1)
            return h
        }

        private fun isLocalName(name: String): Boolean {
            return name == "localhost" || name.endsWith(".localhost") || name.endsWith(".local") || name.endsWith(".internal")
                || name.endsWith(".lan") || name.endsWith(".home.arpa") || name.endsWith(".intranet")
                || name.endsWith(".corp") || name.endsWith(".private")
        }

        // a.b.c.d (各 0-255 の 10 進数) だけ
        internal fun strictIpv4(host: String): ByteArray? {
            val parts = host.split(".")
            if (parts.size != 4) return null
            val b = ByteArray(4)
            for (i in 0 until 4) {
                val p = parts[i]
                if (p.isEmpty() || p.length > 3) return null
                var v = 0
                for (c in p) {
                    if (c < '0' || c > '9') return null
                    v = v * 10 + (c - '0')
                }
                if (v > 255) return null
                // 8 進数の書き方 (010 など) は解釈が分かれるので通さない
                if (p.length > 1 && p[0] == '0') return null
                b[i] = v.toByte()
            }
            return b
        }

        internal fun isLocalIpv4(raw: ByteArray): Boolean {
            val a = raw[0].toInt() and 0xFF
            val b = raw[1].toInt() and 0xFF
            if (a == 0 || a == 10 || a == 127 || a >= 224) return true
            if (a == 169 && b == 254) return true
            if (a == 172 && b >= 16 && b <= 31) return true
            if (a == 192 && b == 168) return true
            if (a == 192 && b == 0 && (raw[2].toInt() and 0xFF) == 0) return true // 192.0.0.0/24
            if (a == 100 && (b and 0xC0) == 64) return true // 共有アドレス
            return a == 198 && (b and 0xFE) == 18 // ベンチマーク用
        }

        private fun isPublicIpv6Literal(literal: String): Boolean {
            if (literal.indexOf('%') >= 0) return false
            val address: InetAddress
            try {
                // 文字そのものが IPv6 のアドレスのときは名前を引かない
                for (c in literal) {
                    if (!(c == ':' || c == '.' || Character.digit(c, 16) >= 0)) return false
                }
                address = InetAddress.getByName(literal)
            } catch (e: Exception) {
                return false
            }
            val raw = address.address
            if (address.isLoopbackAddress || address.isAnyLocalAddress || address.isLinkLocalAddress
                || address.isSiteLocalAddress || address.isMulticastAddress
            ) {
                return false
            }
            if (raw.size == 4) return !isLocalIpv4(raw)
            if ((raw[0].toInt() and 0xFE) == 0xFC) return false // fc00::/7
            var zeros = true
            for (i in 0 until 10) if (raw[i].toInt() != 0) zeros = false
            if (zeros) {
                // ::ffff:a.b.c.d と ::a.b.c.d
                return !isLocalIpv4(byteArrayOf(raw[12], raw[13], raw[14], raw[15]))
            }
            if ((raw[0].toInt() and 0xFF) == 0x00 && (raw[1].toInt() and 0xFF) == 0x64 && (raw[2].toInt() and 0xFF) == 0xFF && (raw[3].toInt() and 0xFF) == 0x9B) {
                return !isLocalIpv4(byteArrayOf(raw[12], raw[13], raw[14], raw[15]))
            }
            if ((raw[0].toInt() and 0xFF) == 0x20 && (raw[1].toInt() and 0xFF) == 0x02) {
                return !isLocalIpv4(byteArrayOf(raw[2], raw[3], raw[4], raw[5]))
            }
            return true
        }

        // ウェブのブラウザーがつながない番号 (WHATWG Fetch の「bad port」)
        private val BLOCKED_PORTS = intArrayOf(
            0, 1, 7, 9, 11, 13, 15, 17, 19, 20, 21, 22, 23, 25, 37, 42, 43, 53, 69, 77, 79, 87, 95, 101, 102, 103, 104, 109, 110,
            111, 113, 115, 117, 119, 123, 135, 137, 139, 143, 161, 179, 389, 427, 465, 512, 513, 514, 515, 526, 530, 531, 532,
            540, 548, 554, 556, 563, 587, 601, 636, 989, 990, 993, 995, 1719, 1720, 1723, 2049, 3659, 4045, 4190, 5060, 5061,
            6000, 6566, 6665, 6666, 6667, 6668, 6669, 6679, 6697, 10080,
        )

        internal fun isBlockedPort(port: Int): Boolean {
            for (blocked in BLOCKED_PORTS) if (port == blocked) return true
            return false
        }
    }
}
