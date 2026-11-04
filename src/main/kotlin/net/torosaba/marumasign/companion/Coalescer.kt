package net.torosaba.marumasign.companion

import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 短いあいだの頼みを 1 回にまとめて、専用のスレッドで実行する ([BlueMapBackend] の scheduleExport と同じ形)。
 * 待っているのは常に 1 回分だけなので、頼みがどれだけ来ても、キューは増えない。実行中に来た頼みは、次の 1 回にまとめる。
 */
internal class Coalescer(private val threadName: String, private val windowMillis: Long, private val task: Runnable) {

    private val pending = AtomicBoolean()

    @Volatile
    private var executor: ScheduledExecutorService? = null

    fun start() {
        executor = Executors.newSingleThreadScheduledExecutor { r ->
            val t = Thread(r, threadName)
            t.isDaemon = true
            t.priority = Thread.MIN_PRIORITY
            t
        }
    }

    fun stop() {
        val e = executor
        executor = null
        e?.shutdownNow()
    }

    /** 実行を頼む。まだ始まっていない実行があれば、それにまとめる。止まっていれば何もしない */
    fun request() {
        val e = executor ?: return
        if (!pending.compareAndSet(false, true)) return
        try {
            e.schedule(Runnable {
                pending.set(false)
                task.run()
            }, windowMillis, TimeUnit.MILLISECONDS)
        } catch (ex: RuntimeException) {
            pending.set(false) // 止まっている途中
        }
    }
}
