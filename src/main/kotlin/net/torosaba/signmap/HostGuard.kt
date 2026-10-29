package net.torosaba.signmap

import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.logging.Logger

/**
 * 看板の URL のホスト名が、公開のアドレスを指しているかを DNS で確かめる。
 *
 * 地図を見る人のブラウザーが画像を読みに行くので、ホスト名が家庭内や社内のアドレス (192.168.x など) を指すように
 * 仕込まれていると、その人のルーターなどにリクエストを送らせることができてしまう。[UrlFilter] は文字だけで判定するので、
 * 名前を引いた結果がそういうアドレスになるホストは、ここで除く。
 *
 * 確かめられるまでは「公開ではない」として扱う (安全側)。確かめた結果は一定時間覚え、古くなったら引き直す。
 */
class HostGuard internal constructor(
    private val log: Logger,
    private val enabled: Boolean,
    private val onProgress: Runnable,
    private val lookup: Lookup,
) {

    private data class Entry(val isPublic: Boolean, val time: Long)

    /** 名前を引く処理 (テストで差し替える) */
    internal fun interface Lookup {
        @Throws(UnknownHostException::class)
        fun lookup(host: String): Array<InetAddress>?
    }

    private val cache = ConcurrentHashMap<String, Entry>()
    private val queue = LinkedHashSet<String>()
    private val inFlight: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private var workers: Array<Thread>? = null
    @Volatile
    private var stopped = false
    private val changes = AtomicInteger()

    constructor(log: Logger, enabled: Boolean, onProgress: Runnable) :
        this(log, enabled, onProgress, Lookup(InetAddress::getAllByName))

    /** そのホストが公開のアドレスだと確かめられていれば true。まだなら確かめるよう頼んで false */
    fun isPublic(host: String?): Boolean {
        if (!enabled) return true
        if (host == null || host.isEmpty()) return false
        val h = host.lowercase()
        val e = cache[h]
        val now = System.currentTimeMillis()
        if (e != null) {
            val age = now - e.time
            if (e.isPublic) {
                if (age > RECHECK_MILLIS) enqueue(h)
                if (age < PUBLIC_TTL_MILLIS) return true
                return false
            }
            if (age > REJECT_TTL_MILLIS) enqueue(h)
            return false
        }
        // 数字だけのホスト (IP アドレス) は UrlFilter が判定済みで、名前は引けない
        enqueue(h)
        return false
    }

    private fun enqueue(host: String) {
        synchronized(queue) {
            if (queue.size >= MAX_QUEUE || inFlight.contains(host)) return
            if (queue.add(host)) (queue as java.lang.Object).notify()
        }
    }

    fun pending(): Int {
        synchronized(queue) {
            return queue.size + inFlight.size
        }
    }

    @Synchronized
    fun start() {
        if (!enabled || workers != null) return
        stopped = false
        val ws = Array(THREADS) { i ->
            val t = Thread(this::run, "BlueMapMarumaSign-dns-$i")
            t.isDaemon = true
            t.priority = Thread.MIN_PRIORITY
            t
        }
        workers = ws
        for (t in ws) t.start()
    }

    @Synchronized
    fun stop() {
        stopped = true
        synchronized(queue) {
            (queue as java.lang.Object).notifyAll()
        }
        workers?.let { for (t in it) t.interrupt() }
        workers = null
    }

    private fun run() {
        try {
            while (!stopped) {
                val host: String? = synchronized(queue) {
                    while (queue.isEmpty() && !stopped) {
                        if (changes.get() > 0 && inFlight.isEmpty()) {
                            changes.set(0)
                            break
                        }
                        (queue as java.lang.Object).wait(5_000)
                    }
                    if (stopped) return
                    if (queue.isEmpty()) {
                        null
                    } else {
                        val it = queue.iterator()
                        val next = it.next()
                        it.remove()
                        inFlight.add(next)
                        next
                    }
                }
                if (host == null) {
                    try {
                        onProgress.run()
                    } catch (e: RuntimeException) {
                        log.warning("Host check callback failed: $e")
                    }
                    continue
                }
                val pub = try {
                    check(host)
                } finally {
                    inFlight.remove(host)
                }
                val before = cache.put(host, Entry(pub, System.currentTimeMillis()))
                if (before == null || before.isPublic != pub) changes.incrementAndGet()
                synchronized(queue) {
                    (queue as java.lang.Object).notifyAll()
                }
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    private fun check(host: String): Boolean {
        try {
            val addresses = lookup.lookup(host)
            if (addresses == null || addresses.isEmpty()) return false
            for (a in addresses) {
                if (!isPublicAddress(a)) {
                    log.fine("Host $host resolves to a non-public address; ignoring its URLs")
                    return false
                }
            }
            return true
        } catch (e: UnknownHostException) {
            return false
        } catch (e: RuntimeException) {
            return false
        }
    }

    companion object {
        private const val PUBLIC_TTL_MILLIS = 6L * 60 * 60 * 1000
        private const val RECHECK_MILLIS = 30L * 60 * 1000
        private const val REJECT_TTL_MILLIS = 10L * 60 * 1000
        private const val MAX_QUEUE = 20_000
        private const val THREADS = 4

        /** インターネット上の公開アドレスなら true ([AddressPolicy]) */
        fun isPublicAddress(a: InetAddress?): Boolean {
            return AddressPolicy.isPublic(a)
        }
    }
}
