package net.torosaba.signmap;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** 実際のワールドの region ファイルで試す (-Dsignmap.sampleRegions=フォルダ を指定したときだけ) */
class SampleRegionTest {

    @Test
    void scanSampleRegions() throws Exception {
        final String dir = System.getProperty("signmap.sampleRegions", "");
        Assumptions.assumeFalse(dir.isBlank());
        int raw = 0, maruma = 0, allowed = 0, shown = 0;
        final UrlFilter filter = new UrlFilter(java.util.List.of());
        final Settings settings = new Settings();
        final MarkerHtml html = new MarkerHtml(filter, settings);
        final java.util.Map<MediaKind, Integer> kinds = new java.util.EnumMap<>(MediaKind.class);
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(Path.of(dir), "r.*.mca")) {
            for (Path p : ds) {
                final long t = System.nanoTime();
                final RegionReader.Result r = RegionReader.scan(p);
                System.out.println(p.getFileName() + ": chunks=" + r.chunks + " errors=" + r.errors + " signs=" + r.signs.size()
                        + " in " + (System.nanoTime() - t) / 1_000_000 + " ms");
                if (r.firstError != null) r.firstError.printStackTrace(System.out);
                for (SignTextExtractor.RawSign s : r.signs) {
                    raw++;
                    if (shown < 12 && !s.text().isBlank()) {
                        shown++;
                        System.out.println("  sign " + s.x() + "," + s.y() + "," + s.z() + " text=[" + s.text() + "]");
                    }
                    final MarumaSpec spec = MarumaSpec.parse(s.text());
                    if (spec == null) continue;
                    maruma++;
                    final MarkerHtml.View v = html.classify(new SignRecord(s.x(), s.y(), s.z(), spec.url()));
                    if (v != null) {
                        allowed++;
                        kinds.merge(v.kind(), 1, Integer::sum);
                        if (allowed <= 6) System.out.println("  MARUMA " + v.kind() + " " + v.url() + "\n    " + html.html(v));
                    } else {
                        System.out.println("  REJECTED " + spec.url());
                    }
                }
            }
        }
        System.out.println("raw=" + raw + " maruma=" + maruma + " allowed=" + allowed + " kinds=" + kinds);
        assertTrue(raw >= 0);
    }
}
