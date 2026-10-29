package net.torosaba.signmap;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** リンク切れ検査の設定 (config.yml の link-check。Bukkit に依存しない)。範囲は読み込み時に収める */
public final class LinkSettings {

    /** ホストごとの決まり (ホストとその下のサブドメインに当てる) */
    public record HostRule(String host, int minIntervalSeconds, List<String> removedPaths) {
    }

    public boolean enabled = true;
    public int concurrency = 2;
    public int startupDelayMinutes = 5;
    public int perHourLimit = 600;
    public int hostPerHourLimit = 60;
    public int hostMinIntervalSeconds = 2;
    public int connectTimeoutSeconds = 5;
    public int totalTimeoutSeconds = 20;
    public int maxBodyKb = 64;
    public int maxRedirects = 5;
    public int recheckOkDays = 7;
    public int recheckBrokenHours = 24;
    /** UNKNOWN の再検査の間隔 (時間)。連続して失敗するほど長く、最後の値で頭打ち */
    public List<Integer> unknownBackoffHours = List.of(6, 12, 24);
    public int brokenAfterFailures = 3;
    public int brokenAfterHours = 24;
    public boolean treat403AsBroken = false;
    public int maxTrackedUrls = 20_000;
    /** 公開のアドレスに見えても、つながないアドレス・範囲 (サーバー自身の公開アドレスなど。「203.0.113.7」「198.51.100.0/24」) */
    public List<String> extraBlockedAddresses = new ArrayList<>();
    public String userAgent = "BlueMapMarumaSign-LinkCheck/1.0";
    public List<HostRule> hostRules = new ArrayList<>(List.of(
            new HostRule("imgur.com", 5, List.of("/removed.png"))));

    static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    /** User-Agent は印字できる ASCII だけ、100 文字まで (ヘッダーの混入を防ぐ) */
    static String cleanUserAgent(String ua) {
        if (ua == null) return "BlueMapMarumaSign-LinkCheck/1.0";
        final StringBuilder sb = new StringBuilder();
        for (int i = 0; i < ua.length() && sb.length() < 100; i++) {
            final char c = ua.charAt(i);
            if (c >= 0x20 && c < 0x7F) sb.append(c);
        }
        final String s = sb.toString().trim();
        return s.isEmpty() ? "BlueMapMarumaSign-LinkCheck/1.0" : s;
    }

    /** そのホストに当てはまる決まり (いちばん長く一致したもの)。なければ null */
    public HostRule ruleFor(String host) {
        if (host == null) return null;
        final String h = host.toLowerCase(Locale.ROOT);
        HostRule best = null;
        for (HostRule r : hostRules) {
            if (h.equals(r.host()) || h.endsWith("." + r.host())) {
                if (best == null || r.host().length() > best.host().length()) best = r;
            }
        }
        return best;
    }

    public int minIntervalMillis(String host) {
        final HostRule r = ruleFor(host);
        return Math.max(hostMinIntervalSeconds, r == null ? 0 : r.minIntervalSeconds()) * 1000;
    }
}
