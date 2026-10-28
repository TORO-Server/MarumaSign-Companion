package net.torosaba.signmap;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 1 つのワールドの、MarumaSign の形式の看板の一覧。region ごとに持ち、region ファイルの更新日時といっしょに JSON に保存する。
 */
public final class SignStore {

    private static final int CACHE_VERSION = 1;

    static final class Region {
        volatile long mtime;
        volatile Map<Long, SignRecord> signs = Map.of();
    }

    // ゲーム中の変更。region ファイルにまだ書かれていないかもしれないので、走査の結果に重ねる
    private record Live(int x, int z, SignRecord record, long time) {
    }

    private final ConcurrentHashMap<Long, Region> regions = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, Live> live = new ConcurrentHashMap<>();
    private volatile boolean dirty;

    public static long regionKey(int rx, int rz) {
        return ((long) rx << 32) | (rz & 0xFFFFFFFFL);
    }

    public static int regionKeyX(long key) {
        return (int) (key >> 32);
    }

    public static int regionKeyZ(long key) {
        return (int) key;
    }

    /** region ファイルを走査した結果を入れる (mtime はファイルを読む前に取った更新日時) */
    public void applyScan(int rx, int rz, long mtime, List<SignRecord> found) {
        final Map<Long, SignRecord> map = new ConcurrentHashMap<>();
        for (SignRecord s : found) map.put(s.key(), s);
        final long rk = regionKey(rx, rz);
        // ファイルの更新より新しいゲーム中の変更は、ファイルに入っていないかもしれないので重ねる
        for (Map.Entry<Long, Live> e : live.entrySet()) {
            final Live l = e.getValue();
            if (regionKey(l.x() >> 9, l.z() >> 9) != rk) continue;
            if (l.time() > mtime) {
                if (l.record() != null) map.put(e.getKey(), l.record());
                else map.remove(e.getKey());
            } else {
                live.remove(e.getKey(), l);
            }
        }
        final Region region = regions.computeIfAbsent(rk, k -> new Region());
        region.signs = map;
        region.mtime = mtime;
        dirty = true;
    }

    /** 走査の結果、ファイルがなくなった region を消す */
    public void retainRegions(java.util.Set<Long> existing) {
        if (regions.keySet().retainAll(existing)) dirty = true;
    }

    public long mtimeOf(int rx, int rz) {
        final Region r = regions.get(regionKey(rx, rz));
        return r == null ? Long.MIN_VALUE : r.mtime;
    }

    public void liveSet(SignRecord record) {
        live.put(record.key(), new Live(record.x(), record.z(), record, System.currentTimeMillis()));
        final Region region = regions.computeIfAbsent(regionKey(record.regionX(), record.regionZ()), k -> {
            final Region r = new Region();
            r.mtime = Long.MIN_VALUE;
            return r;
        });
        final Map<Long, SignRecord> copy = new ConcurrentHashMap<>(region.signs);
        copy.put(record.key(), record);
        region.signs = copy;
        dirty = true;
    }

    public boolean liveRemove(int x, int y, int z) {
        final long key = SignRecord.pack(x, y, z);
        final Region region = regions.get(regionKey(x >> 9, z >> 9));
        if (region == null || !region.signs.containsKey(key)) return false;
        live.put(key, new Live(x, z, null, System.currentTimeMillis()));
        final Map<Long, SignRecord> copy = new ConcurrentHashMap<>(region.signs);
        copy.remove(key);
        region.signs = copy;
        dirty = true;
        return true;
    }

    public List<SignRecord> all() {
        final List<SignRecord> out = new ArrayList<>();
        for (Region r : regions.values()) out.addAll(r.signs.values());
        out.sort(Comparator.comparingInt(SignRecord::x).thenComparingInt(SignRecord::z).thenComparingInt(SignRecord::y));
        return out;
    }

    public int count() {
        int n = 0;
        for (Region r : regions.values()) n += r.signs.size();
        return n;
    }

    public boolean isDirty() {
        return dirty;
    }

    // ---- 保存と読み込み ----

    public synchronized void save(Path file) throws IOException {
        dirty = false;
        final JsonObject root = new JsonObject();
        root.addProperty("version", CACHE_VERSION);
        final JsonObject regionsJson = new JsonObject();
        for (Map.Entry<Long, Region> e : regions.entrySet()) {
            final Region r = e.getValue();
            if (r.mtime == Long.MIN_VALUE) continue; // まだ走査していない region
            final JsonObject o = new JsonObject();
            o.addProperty("mtime", r.mtime);
            final JsonArray signs = new JsonArray();
            for (SignRecord s : r.signs.values()) {
                final JsonArray a = new JsonArray();
                a.add(s.x());
                a.add(s.y());
                a.add(s.z());
                a.add(s.url());
                signs.add(a);
            }
            o.add("signs", signs);
            regionsJson.add(regionKeyX(e.getKey()) + "," + regionKeyZ(e.getKey()), o);
        }
        root.add("regions", regionsJson);
        Files.createDirectories(file.getParent());
        final Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        try (Writer w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
            new Gson().toJson(root, w);
        }
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
    }

    /** 保存したものを読む。ファイルがない、または読めなければ何も入れない */
    public void load(Path file) throws IOException {
        if (!Files.isRegularFile(file)) return;
        try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            final JsonObject root = JsonParser.parseReader(r).getAsJsonObject();
            if (root.get("version").getAsInt() != CACHE_VERSION) return;
            for (Map.Entry<String, JsonElement> e : root.getAsJsonObject("regions").entrySet()) {
                final String[] p = e.getKey().split(",");
                final int rx = Integer.parseInt(p[0]);
                final int rz = Integer.parseInt(p[1]);
                final JsonObject o = e.getValue().getAsJsonObject();
                final List<SignRecord> list = new ArrayList<>();
                for (JsonElement s : o.getAsJsonArray("signs")) {
                    final JsonArray a = s.getAsJsonArray();
                    list.add(new SignRecord(a.get(0).getAsInt(), a.get(1).getAsInt(), a.get(2).getAsInt(), a.get(3).getAsString()));
                }
                final Map<Long, SignRecord> map = new ConcurrentHashMap<>();
                for (SignRecord s : list) map.put(s.key(), s);
                final Region region = new Region();
                region.mtime = o.get("mtime").getAsLong();
                region.signs = map;
                regions.put(regionKey(rx, rz), region);
            }
        } catch (RuntimeException e) {
            regions.clear();
            throw new IOException("Broken cache: " + e, e);
        }
        dirty = false;
    }
}
