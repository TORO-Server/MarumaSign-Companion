package net.torosaba.signmap;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * 短縮 URL (is.gd など) の行き先を調べる。3D 表示では、ブラウザーが画像を直接読む必要があり、
 * 短縮サービスの転送は CORS ヘッダーがなく画像として使えないため、サーバーで行き先を調べてキャッシュする。
 * <p>
 * つなぐのは設定した短縮サービスのホスト (既定は is.gd など) だけで、HEAD で Location ヘッダーを読むだけ。
 * 転送は自動で追わず (最大 3 回まで、次も短縮サービスのときだけ)、行き先のサーバーにはつながない。
 * 行き先は {@link UrlFilter} で確認してから使う。
 */
public final class ShortLinkResolver {

    private static final int MAX_HOPS = 3;
    private static final long RETRY_AFTER_MILLIS = 6L * 60 * 60 * 1000;
    private static final long REQUEST_INTERVAL_MILLIS = 300;

    /** target が null なら調べたが行き先が分からなかった */
    private record Entry(String target, long time) {
    }

    /** 1 回の HEAD の結果 (転送先は Location ヘッダー。なければ null) */
    record Reply(int status, String location) {
    }

    /** HEAD リクエストを送る処理 (テストで差し替える) */
    interface Head {
        Reply head(URI uri) throws IOException, InterruptedException;
    }

    private final Logger log;
    private final UrlFilter filter;
    private volatile Set<String> hosts;
    private final Path file;
    private final Runnable onProgress;
    private final Head head;
    private final long intervalMillis;
    private final Map<String, Entry> cache = new ConcurrentHashMap<>();
    private final LinkedHashSet<String> queue = new LinkedHashSet<>();
    private volatile boolean dirty;
    private volatile boolean stopped;
    private Thread worker;

    /**
     * @param hosts      つないでよい短縮サービスのホスト (小文字)
     * @param file       行き先のキャッシュを保存するファイル
     * @param onProgress 新しい行き先が分かったときに (まとめて) 呼ぶ
     */
    public ShortLinkResolver(Logger log, UrlFilter filter, Set<String> hosts, Path file, Runnable onProgress) {
        this(log, filter, hosts, file, onProgress, null, REQUEST_INTERVAL_MILLIS);
    }

    ShortLinkResolver(Logger log, UrlFilter filter, Set<String> hosts, Path file, Runnable onProgress, Head head, long intervalMillis) {
        this.log = log;
        this.filter = filter;
        this.hosts = hosts;
        this.file = file;
        this.onProgress = onProgress;
        this.head = head != null ? head : new HttpHead();
        this.intervalMillis = intervalMillis;
    }

    public void setHosts(Set<String> hosts) {
        this.hosts = hosts;
    }

    /** 短縮サービスの URL なら true (https の 443 番だけ。サービスのホストは設定で決めたものに限る) */
    public boolean isShort(String url) {
        final URI uri = filter.parse(url);
        if (uri == null || !"https".equalsIgnoreCase(uri.getScheme())) return false;
        if (uri.getPort() != -1 && uri.getPort() != 443) return false;
        final String host = uri.getHost();
        return host != null && hosts.contains(host.toLowerCase(Locale.ROOT));
    }

    // 本物の HEAD。転送は追わず、本文は読まず、Cookie も使わない
    private static final class HttpHead implements Head {
        private final HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(8))
                .build();

        @Override
        public Reply head(URI uri) throws IOException, InterruptedException {
            final HttpRequest request = HttpRequest.newBuilder(uri)
                    .method("HEAD", HttpRequest.BodyPublishers.noBody())
                    .timeout(Duration.ofSeconds(10))
                    .header("User-Agent", "BlueMapMarumaSign/1.0 (short link resolver)")
                    .build();
            final HttpResponse<Void> response = client.send(request, HttpResponse.BodyHandlers.discarding());
            return new Reply(response.statusCode(), response.headers().firstValue("Location").orElse(null));
        }
    }

    /** 調べ済みの行き先。まだ、または調べられなかったら null */
    public String resolved(String url) {
        final Entry e = cache.get(url);
        return e == null ? null : e.target();
    }

    /** 行き先を調べるよう頼む (調べ済みや、最近失敗したものは何もしない) */
    public void request(String url) {
        final Entry e = cache.get(url);
        if (e != null && (e.target() != null || System.currentTimeMillis() - e.time() < RETRY_AFTER_MILLIS)) return;
        if (!isShort(url)) return;
        synchronized (queue) {
            queue.add(url);
            queue.notifyAll();
        }
    }

    public int pending() {
        synchronized (queue) {
            return queue.size();
        }
    }

    public int resolvedCount() {
        int n = 0;
        for (Entry e : cache.values()) if (e.target() != null) n++;
        return n;
    }

    public synchronized void start() {
        if (worker != null) return;
        stopped = false;
        worker = new Thread(this::run, "BlueMapMarumaSign-shortlinks");
        worker.setDaemon(true);
        worker.setPriority(Thread.MIN_PRIORITY);
        worker.start();
    }

    public synchronized void stop() {
        stopped = true;
        synchronized (queue) {
            queue.notifyAll();
        }
        if (worker != null) worker.interrupt();
        worker = null;
        save();
    }

    private void run() {
        int sinceNotify = 0;
        long lastSave = System.currentTimeMillis();
        try {
            while (!stopped) {
                String url;
                synchronized (queue) {
                    while (queue.isEmpty() && !stopped) {
                        if (sinceNotify > 0) break;
                        queue.wait(30_000);
                    }
                    if (stopped) return;
                    if (queue.isEmpty()) {
                        url = null;
                    } else {
                        final var it = queue.iterator();
                        url = it.next();
                        it.remove();
                    }
                }
                if (url == null) {
                    // 溜まった分をまとめて反映する
                    sinceNotify = 0;
                    save();
                    onProgress.run();
                    continue;
                }
                final Entry before = cache.get(url);
                if (before != null && (before.target() != null || System.currentTimeMillis() - before.time() < RETRY_AFTER_MILLIS)) continue;
                String target = null;
                boolean backoff = false;
                try {
                    target = resolve(url);
                } catch (InterruptedException e) {
                    throw e;
                } catch (Exception e) {
                    backoff = true;
                    log.fine("Could not resolve short link: " + e);
                }
                cache.put(url, new Entry(target, System.currentTimeMillis()));
                dirty = true;
                if (target != null) sinceNotify++;
                if (sinceNotify >= 200) {
                    sinceNotify = 0;
                    onProgress.run();
                }
                if (System.currentTimeMillis() - lastSave > 60_000) {
                    save();
                    lastSave = System.currentTimeMillis();
                }
                Thread.sleep(backoff ? Math.max(5_000, intervalMillis) : intervalMillis);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    String resolve(String start) throws IOException, InterruptedException {
        String current = start;
        for (int hop = 0; hop < MAX_HOPS; hop++) {
            final URI uri = filter.parse(current);
            // つなぐのは設定した短縮サービスのホストだけ。行き先がそれ以外なら、つながずにそこで止める
            if (uri == null || !isShort(current)) return hop == 0 ? null : current;
            final Reply reply = head.head(uri);
            final int code = reply.status();
            if (code == 429 || code >= 500) throw new IOException("HTTP " + code);
            if (code < 300 || code >= 400) return null;
            final String location = reply.location();
            if (location == null || location.length() > UrlFilter.MAX_URL_LENGTH) return null;
            final String next;
            try {
                next = uri.resolve(location).toString();
            } catch (IllegalArgumentException e) {
                return null;
            }
            if (filter.parse(next) == null) return null;
            current = next;
            if (!isShort(current)) return current;
        }
        return null;
    }

    // ---- 保存と読み込み ----

    public synchronized void save() {
        if (!dirty && Files.exists(file)) return;
        dirty = false;
        final JsonObject root = new JsonObject();
        root.addProperty("version", 1);
        final JsonObject map = new JsonObject();
        for (Map.Entry<String, Entry> e : cache.entrySet()) {
            final JsonArray a = new JsonArray();
            a.add(e.getValue().target() == null ? "" : e.getValue().target());
            a.add(e.getValue().time());
            map.add(e.getKey(), a);
        }
        root.add("links", map);
        try {
            Files.createDirectories(file.getParent());
            final Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            try (Writer w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
                new Gson().toJson(root, w);
            }
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            log.warning("Could not save short link cache: " + e);
        }
    }

    public void load() {
        if (!Files.isRegularFile(file)) return;
        try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            final JsonObject root = JsonParser.parseReader(r).getAsJsonObject();
            if (root.get("version").getAsInt() != 1) return;
            for (Map.Entry<String, JsonElement> e : root.getAsJsonObject("links").entrySet()) {
                final JsonArray a = e.getValue().getAsJsonArray();
                final String target = a.get(0).getAsString();
                // 読み込んだ行き先も、使う前にもう一度確認する
                cache.put(e.getKey(), new Entry(target.isEmpty() || filter.parse(target) == null ? null : target, a.get(1).getAsLong()));
            }
        } catch (IOException | RuntimeException e) {
            log.warning("Ignoring short link cache: " + e);
            cache.clear();
        }
    }
}
