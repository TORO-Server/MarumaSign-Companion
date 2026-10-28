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
    public String markerSetLabel = "MarumaSign";

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
