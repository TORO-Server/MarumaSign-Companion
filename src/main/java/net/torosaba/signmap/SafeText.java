package net.torosaba.signmap;

import java.net.IDN;
import java.net.URI;
import java.util.Locale;

/**
 * 看板の URL など、プレイヤーが書いた文字を、地図・チャット・ログに出す前に無害にする。
 * 制御文字、双方向の制御文字 (U+202E など)、ゼロ幅の文字は取り除く。URL は、ユーザー情報・クエリ・フラグメント
 * (署名付き URL のトークンなど) を除き、ホストとパスだけにする。
 */
public final class SafeText {

    private SafeText() {
    }

    /** 画面に出してはいけない文字 (制御文字、双方向の制御、ゼロ幅、行・段落の区切り、私用領域、未割り当て) */
    static boolean isUnsafeChar(int cp) {
        if (cp < 0x20 || (cp >= 0x7F && cp <= 0x9F)) return true;
        if (cp == 0x00AD || cp == 0x061C || cp == 0x180E) return true;
        if (cp == 0x00A7) return true; // § (Minecraft の書式コード。チャットやコンソールで色・装飾として解釈される)
        if (cp >= 0x200B && cp <= 0x200F) return true; // ゼロ幅、LRM、RLM
        if (cp >= 0x2028 && cp <= 0x202E) return true; // 行・段落の区切り、LRE-RLO
        if (cp >= 0x2060 && cp <= 0x206F) return true; // WJ、不可視の演算子、LRI-PDI など
        if (cp == 0xFEFF || (cp >= 0xFFF9 && cp <= 0xFFFB) || cp == 0xFFFC || cp == 0xFFFD) return true;
        if (cp >= 0xE000 && cp <= 0xF8FF) return true;
        if (cp >= 0xE0000) return true;
        final int type = Character.getType(cp);
        return type == Character.UNASSIGNED || type == Character.SURROGATE || type == Character.CONTROL
                || type == Character.FORMAT || type == Character.PRIVATE_USE;
    }

    /** 危険な文字を取り除き、max 文字までにする */
    public static String clean(String s, int max) {
        if (s == null) return "";
        final StringBuilder sb = new StringBuilder(Math.min(s.length(), max));
        int count = 0;
        for (int i = 0; i < s.length() && count < max; ) {
            final int cp = s.codePointAt(i);
            i += Character.charCount(cp);
            if (isUnsafeChar(cp)) continue;
            sb.appendCodePoint(cp);
            count++;
        }
        return sb.toString();
    }

    /**
     * 画面 (地図・チャット) に出す URL。ホスト (国際化ドメインは punycode も併記) とパスだけ。長ければ真ん中を省く。
     * URL として読めなければ、危険な文字を除いた文字を短くしたもの。
     */
    public static String displayUrl(String url, int max) {
        if (url == null) return "";
        final java.util.regex.Matcher m = URL_PARTS.matcher(url);
        if (m.find()) {
            String authority = m.group(1);
            final int at = authority.lastIndexOf('@');
            if (at >= 0) authority = authority.substring(at + 1); // ユーザー情報は出さない
            String host = authority;
            if (!host.startsWith("[")) {
                final int colon = host.lastIndexOf(':');
                if (colon >= 0) host = host.substring(0, colon); // ポートは出さない
            }
            if (!host.isEmpty()) return middle(clean(hostWithPunycode(host) + m.group(2), 4096), max);
        }
        return middle(clean(stripQuery(url), 4096), max);
    }

    // scheme://authority/path (クエリとフラグメントは含まない)。URI クラスでは読めない形 (国際化ドメインなど) も扱うため、自前で分ける
    private static final java.util.regex.Pattern URL_PARTS = java.util.regex.Pattern.compile("^[A-Za-z][A-Za-z0-9+.-]*://([^/?#]*)([^?#]*)");
    /** ログに出す URL。クエリ・フラグメント・ユーザー情報を除き、制御文字は見える形 (\\uXXXX) にする */
    public static String logUrl(String url) {
        if (url == null) return "";
        String s = stripQuery(url);
        final int scheme = s.indexOf("://");
        if (scheme >= 0) {
            final int at = s.indexOf('@', scheme + 3);
            final int slash = s.indexOf('/', scheme + 3);
            if (at >= 0 && (slash < 0 || at < slash)) s = s.substring(0, scheme + 3) + s.substring(at + 1);
        }
        final StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length() && sb.length() < 200; ) {
            final int cp = s.codePointAt(i);
            i += Character.charCount(cp);
            if (isUnsafeChar(cp) || cp == '\u001b') sb.append(String.format("\\u%04x", cp));
            else sb.appendCodePoint(cp);
        }
        return sb.toString();
    }

    private static String stripQuery(String url) {
        int cut = url.length();
        final int q = url.indexOf('?');
        final int h = url.indexOf('#');
        if (q >= 0) cut = Math.min(cut, q);
        if (h >= 0) cut = Math.min(cut, h);
        return url.substring(0, cut);
    }

    private static String hostWithPunycode(String host) {
        final String lower = host.toLowerCase(Locale.ROOT);
        boolean ascii = true;
        for (int i = 0; i < lower.length(); i++) if (lower.charAt(i) > 0x7E) ascii = false;
        if (ascii) {
            if (lower.contains("xn--")) {
                try {
                    final String uni = IDN.toUnicode(lower);
                    if (!uni.equals(lower)) return lower + " (" + clean(uni, 253) + ")";
                } catch (RuntimeException e) {
                    // そのまま
                }
            }
            return lower;
        }
        try {
            final String puny = IDN.toASCII(lower);
            return puny + " (" + clean(lower, 253) + ")";
        } catch (RuntimeException e) {
            return clean(lower, 253);
        }
    }

    private static String middle(String s, int max) {
        if (s.length() <= max) return s;
        final int head = max * 2 / 3;
        final int tail = max - head - 1;
        return s.substring(0, head) + "…" + s.substring(s.length() - tail);
    }
}
