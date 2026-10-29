package net.torosaba.signmap

import com.google.gson.stream.JsonWriter
import java.net.URI
import java.net.URISyntaxException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Locale

/**
 * BlueMap の Web アプリの 3D 表示用 (marumasign3d.js) の看板データを書き出す。
 * 看板ごとの配列: [x, y, z, 向き, 種類, 画像の URL (なければ ""), リンクの URL, 数値 8 つ]。
 * URL は [UrlFilter] を通ったものだけ。JS 側でも http/https 以外は使わない。
 */
internal object WebExporter {

    const val FORMAT = 1
    private val TEXTURE_EXTENSIONS = setOf("png", "jpg", "jpeg", "gif", "webp", "bmp", "avif")

    /** 画像として読み込む URL。読めない・描けない種類は null */
    fun imageUrl(view: MarkerHtml.View): String? {
        if (!Orient.valid(view.sign.orient)) return null
        // テクスチャにするのは、ラスター画像だけ (svg はスクリプトを含みうるので使わない)
        val tryImage = view.kind == MediaKind.IMAGE && TEXTURE_EXTENSIONS.contains(view.extension) ||
            view.kind == MediaKind.OTHER && view.extension.isEmpty()
        if (!tryImage) return null
        val url = rewriteImageHost(view.url)
        // https の画像だけ (http は https のページに混ぜて読めず、途中で書き換えられるおそれもある)
        return if (url.regionMatches(0, "https://", 0, 8, ignoreCase = true)) url else null
    }

    // imgur のページの URL (imgur.com/xxxx.png) は画像のサーバーへ転送され、CORS が通らないので、画像のホストにする
    fun rewriteImageHost(url: String): String {
        val uri: URI
        try {
            uri = URI(url)
        } catch (e: URISyntaxException) {
            return url
        }
        val host = if (uri.host == null) "" else uri.host.lowercase(Locale.ROOT)
        val path = if (uri.rawPath == null) "" else uri.rawPath
        if ((host == "imgur.com" || host == "www.imgur.com") && path.matches(IMGUR_PATH)) {
            return "https://i.imgur.com$path"
        }
        return url
    }

    private val IMGUR_PATH = Regex("/[A-Za-z0-9]{5,10}\\.(png|jpe?g|gif|webp)")

    fun write(file: Path, views: List<MarkerHtml.View>, settings: Settings) {
        Files.createDirectories(file.parent)
        val tmp = file.resolveSibling(file.fileName.toString() + ".tmp")
        Files.newBufferedWriter(tmp, StandardCharsets.UTF_8).use { out ->
            JsonWriter(out).use { w ->
                w.setHtmlSafe(true)
                w.beginObject()
                w.name("format").value(FORMAT.toLong())
                w.name("maxSize").value(settings.web3dMaxSize)
                w.name("maxOffset").value(settings.web3dMaxOffset)
                w.name("drawDistance").value(settings.web3dDrawDistance.toLong())
                w.name("maxActive").value(settings.web3dMaxActive.toLong())
                w.name("textureSize").value(settings.web3dTextureSize.toLong())
                w.name("kinds").beginArray()
                for (k in MediaKind.values()) {
                    w.beginArray().value(k.icon).value(k.label).endArray()
                }
                w.endArray()
                w.name("signs").beginArray()
                for (v in views) {
                    val s = v.sign
                    w.beginArray()
                    w.value(s.x.toLong()).value(s.y.toLong()).value(s.z.toLong())
                    w.value(s.orient.toLong())
                    w.value(v.kind.ordinal.toLong())
                    val image = imageUrl(v)
                    w.value(image ?: "")
                    w.value(v.url)
                    for (f in s.values) w.value(f as Number)
                    w.endArray()
                }
                w.endArray()
                w.endObject()
            }
        }
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING)
    }
}
