package net.torosaba.signmap;

import com.google.gson.stream.JsonWriter;

import java.io.IOException;
import java.io.Writer;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Locale;

/**
 * BlueMap の Web アプリの 3D 表示用 (marumasign3d.js) の看板データを書き出す。
 * 看板ごとの配列: [x, y, z, 向き, 種類, 画像の URL (なければ ""), リンクの URL, 数値 8 つ]。
 * URL は {@link UrlFilter} を通ったものだけ。JS 側でも http/https 以外は使わない。
 */
final class WebExporter {

    static final int FORMAT = 1;
    private static final java.util.Set<String> TEXTURE_EXTENSIONS = java.util.Set.of("png", "jpg", "jpeg", "gif", "webp", "bmp", "avif");

    private WebExporter() {
    }

    /** 画像として読み込む URL。読めない・描けない種類は null */
    static String imageUrl(MarkerHtml.View view) {
        if (!Orient.valid(view.sign().orient())) return null;
        // テクスチャにするのは、ラスター画像だけ (svg はスクリプトを含みうるので使わない)
        final boolean tryImage = view.kind() == MediaKind.IMAGE && TEXTURE_EXTENSIONS.contains(view.extension())
                || view.kind() == MediaKind.OTHER && view.extension().isEmpty();
        if (!tryImage) return null;
        final String url = rewriteImageHost(view.url());
        // https の画像だけ (http は https のページに混ぜて読めず、途中で書き換えられるおそれもある)
        return url.regionMatches(true, 0, "https://", 0, 8) ? url : null;
    }

    // imgur のページの URL (imgur.com/xxxx.png) は画像のサーバーへ転送され、CORS が通らないので、画像のホストにする
    static String rewriteImageHost(String url) {
        final URI uri;
        try {
            uri = new URI(url);
        } catch (java.net.URISyntaxException e) {
            return url;
        }
        final String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        final String path = uri.getRawPath() == null ? "" : uri.getRawPath();
        if ((host.equals("imgur.com") || host.equals("www.imgur.com")) && path.matches("/[A-Za-z0-9]{5,10}\\.(png|jpe?g|gif|webp)")) {
            return "https://i.imgur.com" + path;
        }
        return url;
    }

    static void write(Path file, List<MarkerHtml.View> views, Settings settings) throws IOException {
        Files.createDirectories(file.getParent());
        final Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        try (Writer out = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8);
             JsonWriter w = new JsonWriter(out)) {
            w.setHtmlSafe(true);
            w.beginObject();
            w.name("format").value(FORMAT);
            w.name("maxSize").value(settings.web3dMaxSize);
            w.name("maxOffset").value(settings.web3dMaxOffset);
            w.name("drawDistance").value(settings.web3dDrawDistance);
            w.name("maxActive").value(settings.web3dMaxActive);
            w.name("textureSize").value(settings.web3dTextureSize);
            w.name("kinds").beginArray();
            for (MediaKind k : MediaKind.values()) {
                w.beginArray().value(k.icon).value(k.label).endArray();
            }
            w.endArray();
            w.name("signs").beginArray();
            for (MarkerHtml.View v : views) {
                final SignRecord s = v.sign();
                w.beginArray();
                w.value(s.x()).value(s.y()).value(s.z());
                w.value(s.orient());
                w.value(v.kind().ordinal());
                final String image = imageUrl(v);
                w.value(image == null ? "" : image);
                w.value(v.url());
                for (float f : s.values()) w.value(Float.valueOf(f));
                w.endArray();
            }
            w.endArray();
            w.endObject();
        }
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
    }
}
