package net.torosaba.signmap;

import java.net.InetAddress;
import java.net.URI;
import java.util.Collection;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * 看板の URL をマップに出してよいかどうか。公開サイトに出るので、名前を引かずに文字だけで判定する
 * (http / https だけ。手元や家の中のネットワークを指すもの、ブラウザーがつながない番号のものは出さない)。
 */
public final class UrlFilter {

    public static final int MAX_URL_LENGTH = 2000;

    private final Set<String> extraBlockedHosts = new HashSet<>();

    public UrlFilter(Collection<String> extraBlockedHosts) {
        for (String host : extraBlockedHosts) {
            final String h = normalizeHost(host);
            if (!h.isEmpty()) this.extraBlockedHosts.add(h);
        }
    }

    public boolean isAllowed(String url) {
        return parse(url) != null;
    }

    /** 許可できる URL なら、パースした URI を返す。だめなら null */
    public URI parse(String url) {
        if (url == null || url.isEmpty() || url.length() > MAX_URL_LENGTH) return null;
        for (int i = 0; i < url.length(); i++) {
            final char c = url.charAt(i);
            if (c <= ' ' || c == 0x7F || c == '"' || c == '<' || c == '>' || c == '\\' || c == '`'
                    || Character.isISOControl(c) || Character.isWhitespace(c)) {
                return null;
            }
        }
        final URI uri;
        try {
            uri = new URI(url);
        } catch (Exception e) {
            return null;
        }
        final String scheme = uri.getScheme();
        if (scheme == null) return null;
        final String lowerScheme = scheme.toLowerCase(Locale.ROOT);
        if (!lowerScheme.equals("http") && !lowerScheme.equals("https")) return null;
        if (uri.getRawUserInfo() != null) return null;
        final String rawHost = uri.getHost();
        if (rawHost == null || rawHost.isEmpty()) return null;
        int port = uri.getPort();
        if (port < 0) port = lowerScheme.equals("https") ? 443 : 80;
        if (isBlockedPort(port)) return null;
        if (!isAllowedHost(rawHost)) return null;
        return uri;
    }

    boolean isAllowedHost(String rawHost) {
        final String host = normalizeHost(rawHost);
        if (host.isEmpty()) return false;
        for (String blocked : extraBlockedHosts) {
            if (host.equals(blocked) || host.endsWith("." + blocked)) return false;
        }
        if (host.startsWith("[")) {
            // IPv6 のアドレスそのもの (名前は引かない)
            return isPublicIpv6Literal(host.substring(1, host.length() - 1));
        }
        if (isLocalName(host)) return false;
        // 最後の部分が数字や 0x で始まるものは IP アドレスの書き方 (10 進、16 進、省略形など)。決まった形のものだけ通す
        final String last = host.substring(host.lastIndexOf('.') + 1);
        if (last.isEmpty()) return false;
        if (Character.isDigit(last.charAt(0)) || last.startsWith("0x")) {
            final byte[] v4 = strictIpv4(host);
            return v4 != null && !isLocalIpv4(v4);
        }
        // ドットのない名前 (ルーターなど) は出さない
        return host.indexOf('.') > 0;
    }

    private static String normalizeHost(String host) {
        String h = host == null ? "" : host.trim().toLowerCase(Locale.ROOT);
        while (h.endsWith(".")) h = h.substring(0, h.length() - 1);
        return h;
    }

    private static boolean isLocalName(String name) {
        return name.equals("localhost") || name.endsWith(".localhost") || name.endsWith(".local") || name.endsWith(".internal")
                || name.endsWith(".lan") || name.endsWith(".home.arpa") || name.endsWith(".intranet")
                || name.endsWith(".corp") || name.endsWith(".private");
    }

    // a.b.c.d (各 0-255 の 10 進数) だけ
    static byte[] strictIpv4(String host) {
        final String[] parts = host.split("\\.", -1);
        if (parts.length != 4) return null;
        final byte[] b = new byte[4];
        for (int i = 0; i < 4; i++) {
            final String p = parts[i];
            if (p.isEmpty() || p.length() > 3) return null;
            int v = 0;
            for (int j = 0; j < p.length(); j++) {
                final char c = p.charAt(j);
                if (c < '0' || c > '9') return null;
                v = v * 10 + (c - '0');
            }
            if (v > 255) return null;
            // 8 進数の書き方 (010 など) は解釈が分かれるので通さない
            if (p.length() > 1 && p.charAt(0) == '0') return null;
            b[i] = (byte) v;
        }
        return b;
    }

    static boolean isLocalIpv4(byte[] raw) {
        final int a = raw[0] & 0xFF, b = raw[1] & 0xFF;
        if (a == 0 || a == 10 || a == 127 || a >= 224) return true;
        if (a == 169 && b == 254) return true;
        if (a == 172 && b >= 16 && b <= 31) return true;
        if (a == 192 && b == 168) return true;
        if (a == 192 && b == 0 && (raw[2] & 0xFF) == 0) return true; // 192.0.0.0/24
        if (a == 100 && (b & 0xC0) == 64) return true; // 共有アドレス
        return a == 198 && (b & 0xFE) == 18; // ベンチマーク用
    }

    private static boolean isPublicIpv6Literal(String literal) {
        if (literal.indexOf('%') >= 0) return false;
        final InetAddress address;
        try {
            // 文字そのものが IPv6 のアドレスのときは名前を引かない
            for (int i = 0; i < literal.length(); i++) {
                final char c = literal.charAt(i);
                if (!(c == ':' || c == '.' || Character.digit(c, 16) >= 0)) return false;
            }
            address = InetAddress.getByName(literal);
        } catch (Exception e) {
            return false;
        }
        final byte[] raw = address.getAddress();
        if (address.isLoopbackAddress() || address.isAnyLocalAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress()) {
            return false;
        }
        if (raw.length == 4) return !isLocalIpv4(raw);
        if ((raw[0] & 0xFE) == 0xFC) return false; // fc00::/7
        boolean zeros = true;
        for (int i = 0; i < 10; i++) if (raw[i] != 0) zeros = false;
        if (zeros) {
            // ::ffff:a.b.c.d と ::a.b.c.d
            return !isLocalIpv4(new byte[]{raw[12], raw[13], raw[14], raw[15]});
        }
        if ((raw[0] & 0xFF) == 0x00 && (raw[1] & 0xFF) == 0x64 && (raw[2] & 0xFF) == 0xFF && (raw[3] & 0xFF) == 0x9B) {
            return !isLocalIpv4(new byte[]{raw[12], raw[13], raw[14], raw[15]});
        }
        if ((raw[0] & 0xFF) == 0x20 && (raw[1] & 0xFF) == 0x02) {
            return !isLocalIpv4(new byte[]{raw[2], raw[3], raw[4], raw[5]});
        }
        return true;
    }

    // ウェブのブラウザーがつながない番号 (WHATWG Fetch の「bad port」)
    private static final int[] BLOCKED_PORTS = {
            0, 1, 7, 9, 11, 13, 15, 17, 19, 20, 21, 22, 23, 25, 37, 42, 43, 53, 69, 77, 79, 87, 95, 101, 102, 103, 104, 109, 110,
            111, 113, 115, 117, 119, 123, 135, 137, 139, 143, 161, 179, 389, 427, 465, 512, 513, 514, 515, 526, 530, 531, 532,
            540, 548, 554, 556, 563, 587, 601, 636, 989, 990, 993, 995, 1719, 1720, 1723, 2049, 3659, 4045, 4190, 5060, 5061,
            6000, 6566, 6665, 6666, 6667, 6668, 6669, 6679, 6697, 10080,
    };

    static boolean isBlockedPort(int port) {
        for (int blocked : BLOCKED_PORTS) if (port == blocked) return true;
        return false;
    }
}
