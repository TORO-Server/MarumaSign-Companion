package net.torosaba.signmap

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.EnumMap

/** 実際のワールドの region ファイルで試す (-Dsignmap.sampleRegions=フォルダ を指定したときだけ) */
internal class SampleRegionTest {

    @Test
    fun scanSampleRegions() {
        val dir = System.getProperty("signmap.sampleRegions", "")
        Assumptions.assumeFalse(dir.isBlank())
        var raw = 0
        var maruma = 0
        var allowed = 0
        var shown = 0
        val filter = UrlFilter(listOf())
        val settings = Settings()
        val html = MarkerHtml(filter, settings)
        val kinds: MutableMap<MediaKind, Int> = EnumMap(MediaKind::class.java)
        Files.newDirectoryStream(Path.of(dir), "r.*.mca").use { ds ->
            for (p in ds) {
                val t = System.nanoTime()
                val r = RegionReader.scan(p)
                println(p.fileName.toString() + ": chunks=" + r.chunks + " errors=" + r.errors + " signs=" + r.signs.size
                        + " in " + (System.nanoTime() - t) / 1_000_000 + " ms")
                r.firstError?.printStackTrace(System.out)
                for (s in r.signs) {
                    raw++
                    if (shown < 12 && !s.text.isBlank()) {
                        shown++
                        println("  sign " + s.x + "," + s.y + "," + s.z + " text=[" + s.text + "]")
                    }
                    val spec = MarumaSpec.parse(s.text) ?: continue
                    maruma++
                    val v = html.classify(SignRecord(s.x, s.y, s.z, spec.url))
                    if (v != null) {
                        allowed++
                        kinds.merge(v.kind, 1) { a, b -> a + b }
                        if (allowed <= 6) println("  MARUMA " + v.kind + " " + v.url + "\n    " + html.html(v))
                    } else {
                        println("  REJECTED " + spec.url)
                    }
                }
            }
        }
        println("raw=$raw maruma=$maruma allowed=$allowed kinds=$kinds")
        assertTrue(raw >= 0)
    }
}
