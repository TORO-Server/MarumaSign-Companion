package net.torosaba.marumasign.companion

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.logging.Logger

/**
 * ワールドの region ファイルを読み取り専用で走査して、MarumaSign の形式の看板を [SignStore] に入れる。
 * 更新日時が保存したものと同じ region は読まない。スレッド数を絞り、region ごとに間を空けて負荷を抑える。
 */
class WorldScanner(
    private val log: Logger,
    private val worldName: String,
    private val regionDir: Path,
    private val store: SignStore,
    threads: Int,
    pauseMillis: Long,
) {

    class Summary {
        var regionsTotal = 0
        var regionsScanned = 0
        var chunks = 0
        var chunkErrors = 0
        var fileErrors = 0
        var millis = 0L
        var rawSigns = 0L
        var marumaSigns = 0L
    }

    private val threads: Int = Math.max(1, threads)
    private val pauseMillis: Long = Math.max(0L, pauseMillis)

    /** 走査して終わるまで待つ (呼ぶのは非同期のスレッド)。progress は region を何個終えるごとに呼ばれる */
    fun scan(progressEvery: Int, progress: Runnable?): Summary {
        val started = System.currentTimeMillis()
        val summary = Summary()
        val files = ArrayList<Path>()
        val existing = HashSet<Long>()
        Files.newDirectoryStream(regionDir, "r.*.*.mca").use { ds ->
            for (p in ds) {
                val rc = RegionReader.parseRegionName(p.fileName.toString()) ?: continue
                existing.add(SignStore.regionKey(rc[0], rc[1]))
                files.add(p)
            }
        }
        summary.regionsTotal = files.size
        store.retainRegions(existing)

        val todo = ArrayList<Path>()
        val mtimes = ArrayList<Long>()
        for (p in files) {
            val rc = RegionReader.parseRegionName(p.fileName.toString())!!
            val mtime = Files.getLastModifiedTime(p).toMillis()
            if (mtime != store.mtimeOf(rc[0], rc[1]) && Files.size(p) > 0) {
                todo.add(p)
                mtimes.add(mtime)
            }
        }
        if (todo.isEmpty()) {
            summary.millis = System.currentTimeMillis() - started
            return summary
        }
        log.info("[" + worldName + "] Scanning " + todo.size + " of " + files.size + " region files (threads=" + threads + ")")

        val pool = Executors.newFixedThreadPool(threads) { r ->
            val t = Thread(r, "MarumaSignCompanion-scan")
            t.isDaemon = true
            t.priority = Thread.MIN_PRIORITY
            t
        }
        val done = AtomicInteger()
        val chunks = AtomicInteger()
        val chunkErrors = AtomicInteger()
        val fileErrors = AtomicInteger()
        val raw = AtomicLong()
        val maruma = AtomicLong()
        val futures = ArrayList<Future<*>>()
        try {
            for (i in todo.indices) {
                val p = todo[i]
                val mtime = mtimes[i]
                futures.add(pool.submit(Runnable {
                    val rc = RegionReader.parseRegionName(p.fileName.toString())
                    val failed = { e: Exception ->
                        fileErrors.incrementAndGet()
                        log.warning("[" + worldName + "] Could not read " + p.fileName + ": " + e)
                    }
                    try {
                        val result = RegionReader.scan(p)
                        val found = ArrayList<SignRecord>()
                        for (s in result.signs) {
                            val spec = MarumaSpec.parse(s.text) ?: continue
                            if (spec.url.length > UrlFilter.MAX_URL_LENGTH) continue
                            found.add(SignRecord(s.x, s.y, s.z, spec.url, spec.values, s.orient))
                        }
                        raw.addAndGet(result.signs.size.toLong())
                        maruma.addAndGet(found.size.toLong())
                        chunks.addAndGet(result.chunks)
                        chunkErrors.addAndGet(result.errors)
                        store.applyScan(rc!![0], rc[1], mtime, found)
                    } catch (e: IOException) {
                        failed(e)
                    } catch (e: RuntimeException) {
                        failed(e)
                    }
                    val n = done.incrementAndGet()
                    if (progressEvery > 0 && n % progressEvery == 0) {
                        log.info("[" + worldName + "] Scanned " + n + "/" + todo.size + " regions, " + store.count() + " MarumaSign signs so far")
                        progress?.run()
                    }
                    if (pauseMillis > 0) {
                        try {
                            Thread.sleep(pauseMillis)
                        } catch (ie: InterruptedException) {
                            Thread.currentThread().interrupt()
                        }
                    }
                }))
            }
            for (f in futures) {
                try {
                    f.get()
                } catch (e: ExecutionException) {
                    log.warning("[" + worldName + "] Scan task failed: " + e.cause)
                }
            }
        } finally {
            pool.shutdownNow()
        }
        summary.regionsScanned = done.get()
        summary.chunks = chunks.get()
        summary.chunkErrors = chunkErrors.get()
        summary.fileErrors = fileErrors.get()
        summary.rawSigns = raw.get()
        summary.marumaSigns = maruma.get()
        summary.millis = System.currentTimeMillis() - started
        return summary
    }
}
