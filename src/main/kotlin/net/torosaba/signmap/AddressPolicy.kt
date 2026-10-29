package net.torosaba.signmap

import java.net.InetAddress

/**
 * 接続してよい (インターネット上の公開) アドレスかどうか。
 *
 * IPv4 は、公開でないと決まっている範囲を除く。IPv6 は、グローバルユニキャスト (2000::/3) だけを許し、
 * その中の特別な範囲 (文書用、Teredo、6to4、NAT64 など、IPv4 を埋め込める形) を除く。迷う形は拒否する。
 * IPv4 射影 (::ffff:a.b.c.d の 2 つの書き方) は、Java が IPv4 のアドレスにするので、IPv4 の規則で判定される。
 */
object AddressPolicy {

    @JvmStatic
    fun isPublic(a: InetAddress?): Boolean {
        if (a == null) return false
        if (a.isAnyLocalAddress || a.isLoopbackAddress || a.isLinkLocalAddress || a.isSiteLocalAddress || a.isMulticastAddress) {
            return false
        }
        val b = a.address
        if (b.size == 4) return isPublicIpv4(b)
        if (b.size == 16) return isPublicIpv6(b)
        return false
    }

    internal fun isPublicIpv4(b: ByteArray): Boolean {
        val a = b[0].toInt() and 0xFF
        val c = b[1].toInt() and 0xFF
        val d = b[2].toInt() and 0xFF
        if (a == 0 || a == 10 || a == 127) return false // 0.0.0.0/8, 10/8, 127/8
        if (a == 100 && (c and 0xC0) == 64) return false // 100.64.0.0/10 (共有アドレス)
        if (a == 169 && c == 254) return false // リンクローカル (クラウドのメタデータ 169.254.169.254 を含む)
        if (a == 172 && (c and 0xF0) == 16) return false // 172.16.0.0/12 (Docker のブリッジを含む)
        if (a == 192 && c == 168) return false
        if (a == 192 && c == 0 && d == 0) return false // 192.0.0.0/24
        if (a == 192 && c == 0 && d == 2) return false // 192.0.2.0/24 (文書用)
        if (a == 192 && c == 88 && d == 99) return false // 6to4 中継
        if (a == 198 && (c == 18 || c == 19)) return false // 198.18.0.0/15
        if (a == 198 && c == 51 && d == 100) return false // 198.51.100.0/24
        if (a == 203 && c == 0 && d == 113) return false // 203.0.113.0/24
        return a < 224 // 224.0.0.0/4 (マルチキャスト)、240.0.0.0/4 (予約済み)、255.255.255.255
    }

    internal fun isPublicIpv6(b: ByteArray): Boolean {
        val b0 = b[0].toInt() and 0xFF
        val b1 = b[1].toInt() and 0xFF
        val b2 = b[2].toInt() and 0xFF
        val b3 = b[3].toInt() and 0xFF
        // グローバルユニキャスト 2000::/3 以外 (::/8、fc00::/7、fe80::/10、ff00::/8、64:ff9b::/96 など) は拒否
        if ((b0 and 0xE0) != 0x20) return false
        if (b0 == 0x20 && b1 == 0x01) {
            if (b2 == 0x00 && b3 == 0x00) return false // 2001::/32 Teredo (IPv4 を埋め込む)
            if (b2 == 0x0D && b3 == 0xB8) return false // 2001:db8::/32 文書用
            if (b2 == 0x00 && b3 == 0x02 && b[4].toInt() == 0 && b[5].toInt() == 0) return false // 2001:2::/48 ベンチマーク
            if (b2 == 0x00 && (b3 and 0xF0) == 0x10) return false // 2001:10::/28 ORCHID
            if (b2 == 0x00 && (b3 and 0xF0) == 0x20) return false // 2001:20::/28 ORCHIDv2
        }
        if (b0 == 0x20 && b1 == 0x02) return false // 2002::/16 6to4 (IPv4 を埋め込む)
        if (b0 == 0x3F && b1 == 0xFF && (b2 and 0xF0) == 0x00) return false // 3fff::/20 文書用
        return true
    }
}
