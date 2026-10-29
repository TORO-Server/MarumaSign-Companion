package net.torosaba.marumasign.companion

/**
 * 看板からマップのマーカーの HTML を作る。看板の文字は誰でも書けるので、必ずエスケープして組み立てる。
 */
class MarkerHtml(private val filter: UrlFilter, private val settings: Settings) {

    /** 表示してよいと判定した看板 */
    data class View(val sign: SignRecord, val url: String, val kind: MediaKind, val extension: String)

    /** 表示してよい看板ならその内容、だめなら null */
    fun classify(sign: SignRecord): View? {
        val uri = filter.parse(sign.url) ?: return null
        val ext = MediaKind.extension(uri)
        if (ext.isEmpty()) {
            if (!settings.allowNoExtension) return null
        } else if (!settings.allowedExtensions.contains(ext)) {
            return null
        }
        return View(sign, sign.url, MediaKind.of(ext), ext)
    }

    fun html(view: View): String = html(view, settings.thumbnailSize)

    /** size は画像のサムネイルの最大の大きさ (ピクセル) */
    fun html(view: View, size: Int): String {
        val url = escape(view.url)
        val link = "<a href=\"" + url + "\" target=\"_blank\" rel=\"noopener noreferrer nofollow\""
        if (view.kind == MediaKind.IMAGE && MediaKind.isBrowserImage(view.extension)) {
            return ("<div style=\"background:#fff;padding:2px;border-radius:4px;box-shadow:0 1px 4px rgba(0,0,0,.6);line-height:0\">"
                    + link + " title=\"" + escape(shorten(view.url, 80)) + "\">"
                    + "<img src=\"" + url + "\" alt=\"\" referrerpolicy=\"no-referrer\" loading=\"lazy\""
                    + " style=\"max-width:" + size + "px;max-height:" + size + "px;display:block\">"
                    + "</a></div>")
        }
        return ("<div style=\"background:rgba(30,30,30,.85);color:#fff;padding:3px 7px;border-radius:4px;"
                + "font:12px/1.3 sans-serif;white-space:nowrap;box-shadow:0 1px 4px rgba(0,0,0,.6)\">"
                + link + " style=\"color:#fff;text-decoration:none\">"
                + escape(view.kind.icon) + " " + escape(view.kind.label)
                + "<br><span style=\"font-size:10px;opacity:.8\">" + escape(shorten(view.url, 36)) + "</span>"
                + "</a></div>")
    }

    /** マーカー一覧に出す名前 (HTML ではなく文字) */
    fun label(view: View): String = view.kind.label + " " + shorten(view.url, 48)

    companion object {
        /** URL を短く見せる (スキームを除き、長ければ真ん中を省く) */
        fun shorten(url: String, max: Int): String {
            var s = url
            val i = s.indexOf("://")
            if (i >= 0) s = s.substring(i + 3)
            if (s.length <= max) return s
            val head = max * 2 / 3
            val tail = max - head - 1
            return s.substring(0, head) + "…" + s.substring(s.length - tail)
        }

        fun escape(s: String): String {
            val sb = StringBuilder(s.length + 16)
            for (c in s) {
                when (c) {
                    '&' -> sb.append("&amp;")
                    '<' -> sb.append("&lt;")
                    '>' -> sb.append("&gt;")
                    '"' -> sb.append("&quot;")
                    '\'' -> sb.append("&#39;")
                    '`' -> sb.append("&#96;")
                    else -> sb.append(c)
                }
            }
            return sb.toString()
        }
    }
}
