package net.torosaba.signmap;

import java.net.URI;

/**
 * 看板からマップのマーカーの HTML を作る。看板の文字は誰でも書けるので、必ずエスケープして組み立てる。
 */
public final class MarkerHtml {

    /** 表示してよいと判定した看板 */
    public record View(SignRecord sign, String url, MediaKind kind, String extension) {
    }

    private final UrlFilter filter;
    private final Settings settings;

    public MarkerHtml(UrlFilter filter, Settings settings) {
        this.filter = filter;
        this.settings = settings;
    }

    /** 表示してよい看板ならその内容、だめなら null */
    public View classify(SignRecord sign) {
        final URI uri = filter.parse(sign.url());
        if (uri == null) return null;
        final String ext = MediaKind.extension(uri);
        if (ext.isEmpty()) {
            if (!settings.allowNoExtension) return null;
        } else if (!settings.allowedExtensions.contains(ext)) {
            return null;
        }
        return new View(sign, sign.url(), MediaKind.of(ext), ext);
    }

    public String html(View view) {
        return html(view, settings.thumbnailSize);
    }

    /** size は画像のサムネイルの最大の大きさ (ピクセル) */
    public String html(View view, int size) {
        final String url = escape(view.url());
        final String link = "<a href=\"" + url + "\" target=\"_blank\" rel=\"noopener noreferrer nofollow\"";
        if (view.kind() == MediaKind.IMAGE && MediaKind.isBrowserImage(view.extension())) {
            return "<div style=\"background:#fff;padding:2px;border-radius:4px;box-shadow:0 1px 4px rgba(0,0,0,.6);line-height:0\">"
                    + link + " title=\"" + escape(shorten(view.url(), 80)) + "\">"
                    + "<img src=\"" + url + "\" alt=\"\" referrerpolicy=\"no-referrer\" loading=\"lazy\""
                    + " style=\"max-width:" + size + "px;max-height:" + size + "px;display:block\">"
                    + "</a></div>";
        }
        return "<div style=\"background:rgba(30,30,30,.85);color:#fff;padding:3px 7px;border-radius:4px;"
                + "font:12px/1.3 sans-serif;white-space:nowrap;box-shadow:0 1px 4px rgba(0,0,0,.6)\">"
                + link + " style=\"color:#fff;text-decoration:none\">"
                + escape(view.kind().icon) + " " + escape(view.kind().label)
                + "<br><span style=\"font-size:10px;opacity:.8\">" + escape(shorten(view.url(), 36)) + "</span>"
                + "</a></div>";
    }

    /** マーカー一覧に出す名前 (HTML ではなく文字) */
    public String label(View view) {
        return view.kind().label + " " + shorten(view.url(), 48);
    }

    /** URL を短く見せる (スキームを除き、長ければ真ん中を省く) */
    public static String shorten(String url, int max) {
        String s = url;
        final int i = s.indexOf("://");
        if (i >= 0) s = s.substring(i + 3);
        if (s.length() <= max) return s;
        final int head = max * 2 / 3;
        final int tail = max - head - 1;
        return s.substring(0, head) + "…" + s.substring(s.length() - tail);
    }

    public static String escape(String s) {
        final StringBuilder sb = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            final char c = s.charAt(i);
            switch (c) {
                case '&' -> sb.append("&amp;");
                case '<' -> sb.append("&lt;");
                case '>' -> sb.append("&gt;");
                case '"' -> sb.append("&quot;");
                case '\'' -> sb.append("&#39;");
                case '`' -> sb.append("&#96;");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }
}
