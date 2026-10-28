package net.torosaba.signmap;

import java.net.URI;
import java.util.Locale;
import java.util.Set;

/** 看板の URL が表すものの種類 (拡張子で判別) */
public enum MediaKind {
    IMAGE("image", "画像", "🖼"),
    VIDEO("video", "動画", "🎬"),
    AUDIO("audio", "音声", "🎵"),
    MODEL("model", "3Dモデル", "🧊"),
    SPLAT("splat", "ガウシアンスプラッティング", "✨"),
    PDF("pdf", "PDF", "📄"),
    STRUCTURE("structure", "構造物", "🏗"),
    OTHER("other", "リンク", "🔗");

    public final String id;
    public final String label;
    public final String icon;

    MediaKind(String id, String label, String icon) {
        this.id = id;
        this.label = label;
        this.icon = icon;
    }

    // <img> でそのまま表示できる形式
    private static final Set<String> BROWSER_IMAGES = Set.of("png", "apng", "gif", "jpg", "jpeg", "webp", "bmp", "svg", "avif", "ico");

    private static final Set<String> VIDEO_EXT = Set.of("mp4", "webm", "mkv", "mov", "avi", "m3u8", "mpd");
    private static final Set<String> AUDIO_EXT = Set.of("mp3", "ogg", "wav", "flac", "m4a", "aac", "opus");
    private static final Set<String> MODEL_EXT = Set.of("glb", "gltf");
    private static final Set<String> SPLAT_EXT = Set.of("ply", "spz", "sog", "splat", "ksplat");
    private static final Set<String> STRUCTURE_EXT = Set.of("schem", "litematic", "nbt", "mcstructure", "schematic");
    private static final Set<String> IMAGE_EXT = Set.of(
            "png", "apng", "gif", "jpg", "jpeg", "bmp", "webp", "tif", "tiff", "svg", "svgz", "ico", "cur", "ani", "icns", "psd",
            "psb", "tga", "qoi", "dds", "ktx2", "pcx", "pbm", "pgm", "ppm", "pnm", "pam", "pfm", "sgi", "rgb", "ras", "xbm",
            "xpm", "wbmp", "ff", "hdr", "fits", "avif", "heic", "heif", "jxl", "jp2", "exr", "dpx", "iff", "xwd", "pgx");

    /** 許可する拡張子の初期値 (MarumaSign が対応している形式) */
    public static Set<String> defaultExtensions() {
        final java.util.TreeSet<String> all = new java.util.TreeSet<>();
        all.addAll(IMAGE_EXT);
        all.addAll(VIDEO_EXT);
        all.addAll(AUDIO_EXT);
        all.addAll(MODEL_EXT);
        all.addAll(SPLAT_EXT);
        all.addAll(STRUCTURE_EXT);
        all.add("pdf");
        return all;
    }

    /** URL のパスの拡張子 (小文字。なければ空文字) */
    public static String extension(URI uri) {
        final String path = uri.getPath();
        if (path == null) return "";
        final int slash = path.lastIndexOf('/');
        final String name = slash >= 0 ? path.substring(slash + 1) : path;
        final int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) return "";
        return name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    public static MediaKind of(String extension) {
        if (VIDEO_EXT.contains(extension)) return VIDEO;
        if (AUDIO_EXT.contains(extension)) return AUDIO;
        if (MODEL_EXT.contains(extension)) return MODEL;
        if (SPLAT_EXT.contains(extension)) return SPLAT;
        if (extension.equals("pdf")) return PDF;
        if (STRUCTURE_EXT.contains(extension)) return STRUCTURE;
        if (IMAGE_EXT.contains(extension)) return IMAGE;
        return OTHER;
    }

    public static boolean isBrowserImage(String extension) {
        return BROWSER_IMAGES.contains(extension);
    }
}
