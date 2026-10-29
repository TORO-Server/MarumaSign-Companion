package net.torosaba.marumasign.companion

/**
 * MarumaSign の看板の文字列の URL だけを差し替えて、看板の行に分け直す。
 * 看板の文字列は表 4 行と裏 4 行をつなげたもの (MarumaSign 本体と同じ)。
 */
object SignLines {

    // 看板 1 行に書ける文字数 (MarumaSign 本体の maxSignLineLength と同じ)
    const val MAX_LINE_LENGTH = 80
    const val LINES_PER_SIDE = 4

    /** URL を newUrl に替えた文字列。MarumaSign の形式でなければ null。URL より後ろ (数値や余分な文字) はそのまま残す */
    fun replaceUrl(text: String, newUrl: String): String? {
        if (MarumaSpec.parse(text) == null) return null
        return newUrl + text.substring(text.indexOf('|'))
    }

    /**
     * 表と裏の 4 行ずつに分ける。表に入りきれば MarumaSign 本体が書くときと同じく表の 4 行に均等に分け、裏は空にする。
     * 入りきらなければ表を埋めて、残りを裏に均等に分ける。裏にも入らなければ null
     */
    fun split(text: String): Sides? {
        val side = MAX_LINE_LENGTH * LINES_PER_SIDE
        if (text.length > side * 2) return null
        if (text.length <= side) return Sides(even(text), List(LINES_PER_SIDE) { "" })
        return Sides(text.substring(0, side).chunked(MAX_LINE_LENGTH), even(text.substring(side)))
    }

    private fun even(text: String): List<String> {
        val base = text.length / LINES_PER_SIDE
        val remainder = text.length % LINES_PER_SIDE
        val out = ArrayList<String>(LINES_PER_SIDE)
        var start = 0
        for (i in 0 until LINES_PER_SIDE) {
            val size = base + if (i < remainder) 1 else 0
            out.add(text.substring(start, start + size))
            start += size
        }
        return out
    }

    data class Sides(val front: List<String>, val back: List<String>)
}
