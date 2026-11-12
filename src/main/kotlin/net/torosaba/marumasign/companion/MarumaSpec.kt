package net.torosaba.marumasign.companion

/**
 * 看板の文字列 (MarumaSign の形式)。
 * フォーマット: URL|X|Y|Z|横幅|高さ|Z軸回転|Y軸回転|X軸回転
 * MarumaSign 本体の SignSpec と同じ規則で判定する。
 */
data class MarumaSpec(val url: String, val values: FloatArray) {

    companion object {
        const val PARAMETER_COUNT = 9
        const val MAX_VALUE = 1000f

        private val SEPARATOR = "\\|".toPattern()

        /** MarumaSign の形式なら内容、そうでなければ null */
        fun parse(text: String?): MarumaSpec? {
            if (text == null || !looksLikeSign(text)) return null
            val p = SEPARATOR.split(text)
            if (p.size < PARAMETER_COUNT) return null
            val values = FloatArray(PARAMETER_COUNT - 1)
            for (i in values.indices) {
                val value: Float
                try {
                    value = java.lang.Float.parseFloat(p[i + 1])
                } catch (e: NumberFormatException) {
                    return null
                }
                if (value.isNaN() || value.isInfinite()) return null
                values[i] = Math.max(-MAX_VALUE, Math.min(MAX_VALUE, value))
            }
            return MarumaSpec(normalizeUrl(p[0]), values)
        }

        /**
         * URL 文字列に含まれる全角英数記号 (U+FF01–U+FF5E) を半角 ASCII に正規化する。
         * 日本語ファイル名等の非 ASCII 文字はそのまま保持する。
         */
        fun normalizeUrl(rawUrl: String): String {
            if (rawUrl.isEmpty()) return rawUrl
            var hasFw = false
            for (i in 0 until rawUrl.length) {
                val c = rawUrl[i]
                if (c in '\uFF01'..'\uFF5E') {
                    hasFw = true
                    break
                }
            }
            if (!hasFw) return rawUrl
            val sb = java.lang.StringBuilder(rawUrl.length)
            for (i in 0 until rawUrl.length) {
                val c = rawUrl[i]
                if (c in '\uFF01'..'\uFF5E') {
                    sb.append((c.code - 0xFEE0).toChar())
                } else {
                    sb.append(c)
                }
            }
            return sb.toString()
        }

        private fun looksLikeSign(text: String): Boolean {
            if (text.length < 2 * PARAMETER_COUNT - 1) return false
            var count = 0
            for (i in 0 until text.length) {
                if (text[i] == '|' && ++count >= PARAMETER_COUNT - 1) return true
            }
            return false
        }
    }
}
