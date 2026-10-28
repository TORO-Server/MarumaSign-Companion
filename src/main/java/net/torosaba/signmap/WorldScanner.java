package net.torosaba.signmap;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

/**
 * ワールドの region ファイルを読み取り専用で走査して、MarumaSign の形式の看板を {@link SignStore} に入れる。
 * 更新日時が保存したものと同じ region は読まない。スレッド数を絞り、region ごとに間を空けて負荷を抑える。
 */
public final class WorldScanner {

    public static final class Summary {
        public int regionsTotal, regionsScanned, chunks, chunkErrors, fileErrors;
        public long millis;
        public long rawSigns, marumaSigns;
    }

    private final Logger log;
    private final String worldName;
    private final Path regionDir;
    private final SignStore store;
    private final int threads;
    private final long pauseMillis;

    public WorldScanner(Logger log, String worldName, Path regionDir, SignStore store, int threads, long pauseMillis) {
        this.log = log;
        this.worldName = worldName;
        this.regionDir = regionDir;
        this.store = store;
        this.threads = Math.max(1, threads);
        this.pauseMillis = Math.max(0, pauseMillis);
    }

    /** 走査して終わるまで待つ (呼ぶのは非同期のスレッド)。progress は region を何個終えるごとに呼ばれる */
    public Summary scan(int progressEvery, Runnable progress) throws IOException, InterruptedException {
        final long started = System.currentTimeMillis();
        final Summary summary = new Summary();
        final List<Path> files = new ArrayList<>();
        final Set<Long> existing = new HashSet<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(regionDir, "r.*.*.mca")) {
            for (Path p : ds) {
                final int[] rc = RegionReader.parseRegionName(p.getFileName().toString());
                if (rc == null) continue;
                existing.add(SignStore.regionKey(rc[0], rc[1]));
                files.add(p);
            }
        }
        summary.regionsTotal = files.size();
        store.retainRegions(existing);

        final List<Path> todo = new ArrayList<>();
        final List<Long> mtimes = new ArrayList<>();
        for (Path p : files) {
            final int[] rc = RegionReader.parseRegionName(p.getFileName().toString());
            final long mtime = Files.getLastModifiedTime(p).toMillis();
            if (mtime != store.mtimeOf(rc[0], rc[1]) && Files.size(p) > 0) {
                todo.add(p);
                mtimes.add(mtime);
            }
        }
        if (todo.isEmpty()) {
            summary.millis = System.currentTimeMillis() - started;
            return summary;
        }
        log.info("[" + worldName + "] Scanning " + todo.size() + " of " + files.size() + " region files (threads=" + threads + ")");

        final ExecutorService pool = Executors.newFixedThreadPool(threads, r -> {
            final Thread t = new Thread(r, "BlueMapMarumaSign-scan");
            t.setDaemon(true);
            t.setPriority(Thread.MIN_PRIORITY);
            return t;
        });
        final AtomicInteger done = new AtomicInteger();
        final AtomicInteger chunks = new AtomicInteger(), chunkErrors = new AtomicInteger(), fileErrors = new AtomicInteger();
        final AtomicLong raw = new AtomicLong(), maruma = new AtomicLong();
        final List<Future<?>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < todo.size(); i++) {
                final Path p = todo.get(i);
                final long mtime = mtimes.get(i);
                futures.add(pool.submit(() -> {
                    final int[] rc = RegionReader.parseRegionName(p.getFileName().toString());
                    try {
                        final RegionReader.Result result = RegionReader.scan(p);
                        final List<SignRecord> found = new ArrayList<>();
                        for (SignTextExtractor.RawSign s : result.signs) {
                            final MarumaSpec spec = MarumaSpec.parse(s.text());
                            if (spec == null) continue;
                            if (spec.url().length() > UrlFilter.MAX_URL_LENGTH) continue;
                            found.add(new SignRecord(s.x(), s.y(), s.z(), spec.url()));
                        }
                        raw.addAndGet(result.signs.size());
                        maruma.addAndGet(found.size());
                        chunks.addAndGet(result.chunks);
                        chunkErrors.addAndGet(result.errors);
                        store.applyScan(rc[0], rc[1], mtime, found);
                    } catch (IOException | RuntimeException e) {
                        fileErrors.incrementAndGet();
                        log.warning("[" + worldName + "] Could not read " + p.getFileName() + ": " + e);
                    }
                    final int n = done.incrementAndGet();
                    if (progressEvery > 0 && n % progressEvery == 0) {
                        log.info("[" + worldName + "] Scanned " + n + "/" + todo.size() + " regions, " + store.count() + " MarumaSign signs so far");
                        if (progress != null) progress.run();
                    }
                    if (pauseMillis > 0) {
                        try {
                            Thread.sleep(pauseMillis);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                        }
                    }
                }));
            }
            for (Future<?> f : futures) {
                try {
                    f.get();
                } catch (java.util.concurrent.ExecutionException e) {
                    log.warning("[" + worldName + "] Scan task failed: " + e.getCause());
                }
            }
        } finally {
            pool.shutdownNow();
        }
        summary.regionsScanned = done.get();
        summary.chunks = chunks.get();
        summary.chunkErrors = chunkErrors.get();
        summary.fileErrors = fileErrors.get();
        summary.rawSigns = raw.get();
        summary.marumaSigns = maruma.get();
        summary.millis = System.currentTimeMillis() - started;
        return summary;
    }
}
