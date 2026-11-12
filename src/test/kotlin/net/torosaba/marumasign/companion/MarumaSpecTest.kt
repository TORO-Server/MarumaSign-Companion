package net.torosaba.marumasign.companion

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test

class MarumaSpecTest {

    @Test
    fun fullwidthAsciiUrlIsNormalizedToHalfwidth() {
        val fullwidthUrl = "ｈｔｔｐｓ：／／ａｓｓｅｔｓ．ｔｏｒｏｓａｂａ．ｎｅｔ／２２ｍｎｇｖｏｚｓｚｄｔ．ｐｎｇ"
        val signText = "$fullwidthUrl|0|0|0|1|1|0|0|0"

        val spec = MarumaSpec.parse(signText)
        assertNotNull(spec)
        assertEquals("https://assets.torosaba.net/22mngvozszdt.png", spec!!.url)
        assertEquals(0f, spec.values[0])
        assertEquals(1f, spec.values[3])
    }

    @Test
    fun mixedAndPunctuationFullwidthNormalized() {
        val raw = "ｈｔｔｐ：／／ｅｘａｍｐｌｅ．ｃｏｍ／ｐａｔｈ？ｑ＝１＆ｂ＝２～３！"
        val expected = "http://example.com/path?q=1&b=2~3!"
        assertEquals(expected, MarumaSpec.normalizeUrl(raw))
    }

    @Test
    fun nonAsciiJapaneseCharactersArePreserved() {
        val raw = "https://example.com/画像/看板テスト.png?tag=日本語"
        assertEquals(raw, MarumaSpec.normalizeUrl(raw))

        // 全角英数記号と日本語が混在する場合
        val mixed = "ｈｔｔｐｓ：／／ｅｘａｍｐｌｅ．ｃｏｍ／画像／テスト１２３．ｐｎｇ"
        val expected = "https://example.com/画像/テスト123.png"
        assertEquals(expected, MarumaSpec.normalizeUrl(mixed))
    }

    @Test
    fun halfwidthUrlUnchanged() {
        val raw = "https://assets.torosaba.net/test.png"
        assertEquals(raw, MarumaSpec.normalizeUrl(raw))
    }
}
