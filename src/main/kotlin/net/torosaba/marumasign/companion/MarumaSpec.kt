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
            return MarumaSpec(p[0], values)
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
