package net.torosaba.signmap;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

/** プラグインの設定 (config.yml の内容をまとめたもの。Bukkit に依存しない) */
public final class Settings {

    public int maxMarkers = 5000;
    public Set<String> allowedExtensions = MediaKind.defaultExtensions();
    public boolean allowNoExtension = true;
    public List<String> blockedHosts = new ArrayList<>();
    public int scanThreads = 2;
    public long pauseMillisPerRegion = 40;
    public int rescanIntervalMinutes = 30;
    public int thumbnailSize = 96;
    public double maxDistance = 600;
    public boolean defaultHidden = false;
    public boolean dynmapDefaultHidden = true;
    public String markerSetLabel = "MarumaSign";

    /** BlueMap での出し方: "3d" (画像を 3D の空間に置く)、"markers" (HTML マーカー)、"both" */
    public String bluemapMode = "3d";
    public boolean resolveShortLinks = true;
    public Set<String> shortLinkHosts = new TreeSet<>(List.of("is.gd", "v.gd", "x.gd"));
    /** 3D 表示 (Web 側) の設定 */
    /** 看板の URL のホスト名を DNS で引いて、公開のアドレスだけ出す (閲覧者のブラウザーに LAN を読ませないため) */
    public boolean dnsCheck = true;
    /** 3D 表示で、看板の画像の大きさ・位置のずれの上限 (ブロック)。看板には ±1000 まで書けるので、空を覆うような悪戯を防ぐ */
    public double web3dMaxSize = 64;
    public double web3dMaxOffset = 64;
    public int web3dMaxSigns = 20000;
    public int web3dDrawDistance = 192;
    public int web3dMaxActive = 200;
    public int web3dTextureSize = 1024;

    public boolean bluemap3d() {
        return !bluemapMode.equals("markers");
    }

    public boolean bluemapMarkers() {
        return !bluemapMode.equals("3d");
    }

    public static Set<String> normalizeExtensions(Collection<String> list) {
        final Set<String> out = new TreeSet<>();
        for (String s : list) {
            String e = s.trim().toLowerCase(Locale.ROOT);
            while (e.startsWith(".")) e = e.substring(1);
            if (!e.isEmpty()) out.add(e);
        }
        return out;
    }
}
