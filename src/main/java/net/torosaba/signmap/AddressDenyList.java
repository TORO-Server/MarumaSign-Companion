package net.torosaba.signmap;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;

/**
 * 接続しないアドレスの一覧 (設定 link-check.extra-blocked-addresses)。「203.0.113.7」のような 1 つのアドレスか、
 * 「198.51.100.0/24」「2001:db8::/32」のような範囲。サーバー自身の公開アドレスや、サイトごとの NAT64 の範囲など、
 * 公開のアドレスに見えても、つないではいけないものを管理者が足せる。名前は引かない (数字の形だけを読む)。
 */
public final class AddressDenyList {

    private record Range(byte[] network, int prefixBits) {
        boolean contains(byte[] address) {
            if (address.length != network.length) return false;
            int bits = prefixBits;
            for (int i = 0; i < address.length && bits > 0; i++, bits -= 8) {
                final int mask = bits >= 8 ? 0xFF : (0xFF << (8 - bits)) & 0xFF;
                if ((address[i] & mask) != (network[i] & mask)) return false;
            }
            return true;
        }
    }

    private final List<Range> ranges = new ArrayList<>();
    private final List<String> rejected = new ArrayList<>();

    /** 読めない行は無視して、rejected() に残す */
    public AddressDenyList(List<String> entries) {
        for (String raw : entries) {
            final String e = raw == null ? "" : raw.trim();
            if (e.isEmpty()) continue;
            if (!parse(e)) rejected.add(e);
        }
    }

    private boolean parse(String e) {
        final int slash = e.indexOf('/');
        final String addr = slash >= 0 ? e.substring(0, slash) : e;
        // 数字・16 進・: . だけ (名前や別の書き方は受け付けない)
        for (int i = 0; i < addr.length(); i++) {
            final char c = addr.charAt(i);
            if (!(c == ':' || c == '.' || Character.digit(c, 16) >= 0)) return false;
        }
        final byte[] bytes;
        try {
            bytes = InetAddress.getByName(addr).getAddress();
        } catch (UnknownHostException | RuntimeException ex) {
            return false;
        }
        int prefix = bytes.length * 8;
        if (slash >= 0) {
            try {
                prefix = Integer.parseInt(e.substring(slash + 1));
            } catch (NumberFormatException ex) {
                return false;
            }
            if (prefix < 0 || prefix > bytes.length * 8) return false;
        }
        ranges.add(new Range(bytes, prefix));
        return true;
    }

    public boolean matches(InetAddress a) {
        final byte[] b = a.getAddress();
        for (Range r : ranges) if (r.contains(b)) return true;
        return false;
    }

    public List<String> rejected() {
        return rejected;
    }

    public int size() {
        return ranges.size();
    }
}
