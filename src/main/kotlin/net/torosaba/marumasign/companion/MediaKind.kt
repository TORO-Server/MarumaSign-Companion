package net.torosaba.marumasign.companion

import java.net.URI
import java.util.TreeSet

/** 看板の URL が表すものの種類 (拡張子で判別) */
enum class MediaKind(val id: String, val label: String, val icon: String) {
    IMAGE("image", "画像", "🖼"),
    VIDEO("video", "動画", "🎬"),
    AUDIO("audio", "音声", "🎵"),
    MODEL("model", "3Dモデル", "🧊"),
    SPLAT("splat", "ガウシアンスプラッティング", "✨"),
    PDF("pdf", "PDF", "📄"),
    STRUCTURE("structure", "構造物", "🏗"),
    OTHER("other", "リンク", "🔗");

    companion object {
        // <img> でそのまま表示できる形式
        private val BROWSER_IMAGES = setOf("png", "apng", "gif", "jpg", "jpeg", "webp", "bmp", "svg", "avif", "ico")

        private val VIDEO_EXT = setOf("mp4", "webm", "mkv", "mov", "avi", "m3u8", "mpd")
        private val AUDIO_EXT = setOf("mp3", "ogg", "wav", "flac", "m4a", "aac", "opus")
        private val MODEL_EXT = setOf("glb", "gltf")
        private val SPLAT_EXT = setOf("ply", "spz", "sog", "splat", "ksplat")
        private val STRUCTURE_EXT = setOf("schem", "litematic", "nbt", "mcstructure", "schematic")
        private val IMAGE_EXT = setOf(
            "png", "apng", "gif", "jpg", "jpeg", "bmp", "webp", "tif", "tiff", "svg", "svgz", "ico", "cur", "ani", "icns", "psd",
            "psb", "tga", "qoi", "dds", "ktx2", "pcx", "pbm", "pgm", "ppm", "pnm", "pam", "pfm", "sgi", "rgb", "ras", "xbm",
            "xpm", "wbmp", "ff", "hdr", "fits", "avif", "heic", "heif", "jxl", "jp2", "exr", "dpx", "iff", "xwd", "pgx"
        )

        /** 許可する拡張子の初期値 (MarumaSign が対応している形式) */
        fun defaultExtensions(): Set<String> {
            val all = TreeSet<String>()
            all.addAll(IMAGE_EXT)
            all.addAll(VIDEO_EXT)
            all.addAll(AUDIO_EXT)
            all.addAll(MODEL_EXT)
            all.addAll(SPLAT_EXT)
            all.addAll(STRUCTURE_EXT)
            all.add("pdf")
            return all
        }

        /** URL のパスの拡張子 (小文字。なければ空文字) */
        fun extension(uri: URI): String {
            val path = uri.path ?: return ""
            val slash = path.lastIndexOf('/')
            val name = if (slash >= 0) path.substring(slash + 1) else path
            val dot = name.lastIndexOf('.')
            if (dot < 0 || dot == name.length - 1) return ""
            return name.substring(dot + 1).lowercase()
        }

        fun of(extension: String): MediaKind {
            if (VIDEO_EXT.contains(extension)) return VIDEO
            if (AUDIO_EXT.contains(extension)) return AUDIO
            if (MODEL_EXT.contains(extension)) return MODEL
            if (SPLAT_EXT.contains(extension)) return SPLAT
            if (extension == "pdf") return PDF
            if (STRUCTURE_EXT.contains(extension)) return STRUCTURE
            if (IMAGE_EXT.contains(extension)) return IMAGE
            return OTHER
        }

        fun isBrowserImage(extension: String): Boolean {
            return BROWSER_IMAGES.contains(extension)
        }
    }
}
