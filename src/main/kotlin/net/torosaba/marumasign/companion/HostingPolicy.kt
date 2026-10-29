package net.torosaba.marumasign.companion

import java.util.Locale

/**
 * どの URL・応答をサーバーでホストしてよいか。
 * ライブ配信や分割配信 (中身が別の URL にあって増え続けるもの)、外部のファイルを読む形式、HTML (エラーページなど) はホストしない。
 */
object HostingPolicy {

    enum class Reject(val label: String) {
        STREAM("ライブ配信・分割配信"),
        EXTERNAL_FILES("外部のファイルを読む形式"),
        HTML("画像などでなくHTML"),
        TYPE("対応していない種類"),
        TOO_LARGE("大きすぎる"),
        TIMEOUT("時間内に読めない"),
        HTTP("正常な応答でない"),
        UNSAFE("検査できない URL・転送"),
        FAILED("読み込みに失敗"),
    }

    // 再生リスト・配信の目録。中身は別の URL にあり、ライブでは増え続ける
    private val STREAM_EXT = setOf("m3u8", "m3u", "mpd", "pls", "asx", "xspf", "ism", "isml", "f4m")
    // 別のファイル (.bin や画像) を相対パスで読む形式。1 つだけ写しても表示できない
    private val EXTERNAL_EXT = setOf("gltf")

    private val STREAM_TYPES = setOf(
        "application/vnd.apple.mpegurl", "application/x-mpegurl", "audio/mpegurl", "audio/x-mpegurl",
        "application/dash+xml", "video/vnd.mpeg.dash.mpd", "application/vnd.ms-sstr+xml", "audio/x-scpls",
    )

    // 拡張子のない URL は、Content-Type からこの拡張子にする (MarumaSign が拡張子で種類を見分けるため)
    private val EXT_BY_TYPE = mapOf(
        "image/png" to "png", "image/apng" to "apng", "image/jpeg" to "jpg", "image/gif" to "gif", "image/webp" to "webp",
        "image/avif" to "avif", "image/bmp" to "bmp", "image/svg+xml" to "svg", "image/heic" to "heic", "image/jxl" to "jxl",
        "image/tiff" to "tiff", "image/x-icon" to "ico", "image/vnd.microsoft.icon" to "ico",
        "video/mp4" to "mp4", "video/webm" to "webm", "video/quicktime" to "mov", "video/x-matroska" to "mkv",
        "audio/mpeg" to "mp3", "audio/ogg" to "ogg", "audio/wav" to "wav", "audio/x-wav" to "wav", "audio/flac" to "flac",
        "audio/mp4" to "m4a", "audio/aac" to "aac", "audio/opus" to "opus",
        "model/gltf-binary" to "glb", "application/pdf" to "pdf",
    )

    private val TYPE_BY_EXT: Map<String, String> = buildMap {
        for ((type, ext) in EXT_BY_TYPE) putIfAbsent(ext, type)
        put("jpeg", "image/jpeg")
        put("ico", "image/x-icon")
        put("ogg", "audio/ogg")
        put("wav", "audio/wav")
        put("svgz", "image/svg+xml")
        put("tif", "image/tiff")
    }

    /** URL の拡張子だけで判断して、ホストしない理由。よさそうなら null (拡張子がなければ、応答を見るまで分からないので null) */
    fun hostableExtension(ext: String, allowed: Set<String>): Reject? = when {
        ext in STREAM_EXT -> Reject.STREAM
        ext in EXTERNAL_EXT -> Reject.EXTERNAL_FILES
        ext.isNotEmpty() && ext !in allowed -> Reject.TYPE
        else -> null
    }

    /**
     * 応答のヘッダーを見て、ホストしてよいか。
     * ライブ配信は、目録の Content-Type、Icecast の icy- ヘッダー、MJPEG (multipart) で見分ける
     * (長さの分からない配信は、本文の上限と期限でも止まる)
     */
    fun checkResponse(ext: String, contentType: String, icy: Boolean): Reject? {
        val type = contentType.lowercase(Locale.ROOT)
        if (icy || type in STREAM_TYPES || type.startsWith("multipart/")) return Reject.STREAM
        if (type == "text/html" || type == "application/xhtml+xml") return Reject.HTML
        if (ext.isEmpty()) return if (type in EXT_BY_TYPE) null else Reject.TYPE
        if (type.startsWith("text/") && !(ext == "svg" && type == "text/xml")) return Reject.HTML
        return null
    }

    /** 保存するファイルの拡張子 (URL にあればそれ、なければ Content-Type から) */
    fun extensionFor(ext: String, contentType: String): String? =
        ext.ifEmpty { EXT_BY_TYPE[contentType.lowercase(Locale.ROOT)] }

    /** 配るときの Content-Type。分からなければ application/octet-stream */
    fun contentTypeFor(ext: String): String = TYPE_BY_EXT[ext] ?: "application/octet-stream"
}
