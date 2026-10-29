package net.torosaba.marumasign.companion

import java.net.InetAddress

/**
 * 接続しないアドレスの一覧 (設定 link-check.extra-blocked-addresses)。「203.0.113.7」のような 1 つのアドレスか、
 * 「198.51.100.0/24」「2001:db8::/32」のような範囲。サーバー自身の公開アドレスや、サイトごとの NAT64 の範囲など、
 * 公開のアドレスに見えても、つないではいけないものを管理者が足せる。名前は引かない (数字の形だけを読む)。
 */
class AddressDenyList(entries: List<String?>) {

    private class Range(val network: ByteArray, val prefixBits: Int) {
        fun contains(address: ByteArray): Boolean {
            if (address.size != network.size) return false
            var bits = prefixBits
            var i = 0
            while (i < address.size && bits > 0) {
                val mask = if (bits >= 8) 0xFF else (0xFF shl (8 - bits)) and 0xFF
                if ((address[i].toInt() and mask) != (network[i].toInt() and mask)) return false
                i++
                bits -= 8
            }
            return true
        }
    }

    private val ranges = ArrayList<Range>()
    private val rejected = ArrayList<String>()

    /** 読めない行は無視して、rejected() に残す */
    init {
        for (raw in entries) {
            val e = raw?.trim { it <= ' ' } ?: ""
            if (e.isEmpty()) continue
            if (!parse(e)) rejected.add(e)
        }
    }

    private fun parse(e: String): Boolean {
        val slash = e.indexOf('/')
        val addr = if (slash >= 0) e.substring(0, slash) else e
        // 数字・16 進・: . だけ (名前や別の書き方は受け付けない)
        for (c in addr) {
            if (!(c == ':' || c == '.' || Character.digit(c, 16) >= 0)) return false
        }
        val bytes: ByteArray = try {
            InetAddress.getByName(addr).address
        } catch (ex: Exception) {
            return false
        }
        var prefix = bytes.size * 8
        if (slash >= 0) {
            prefix = try {
                Integer.parseInt(e.substring(slash + 1))
            } catch (ex: NumberFormatException) {
                return false
            }
            if (prefix < 0 || prefix > bytes.size * 8) return false
        }
        ranges.add(Range(bytes, prefix))
        return true
    }

    fun matches(a: InetAddress): Boolean {
        val b = a.address
        for (r in ranges) if (r.contains(b)) return true
        return false
    }

    fun rejected(): List<String> {
        return rejected
    }

    fun size(): Int {
        return ranges.size
    }
}
