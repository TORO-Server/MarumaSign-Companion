package net.torosaba.marumasign.companion

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Dynmap の更新を 1 回にまとめる処理 */
class CoalescerTest {

    @Test
    fun manyRequestsInOneWindowRunOnce() {
        val runs = AtomicInteger()
        val done = CountDownLatch(1)
        val c = Coalescer("test-coalescer", 500, Runnable {
            runs.incrementAndGet()
            done.countDown()
        })
        c.start()
        try {
            for (i in 0 until 10_000) c.request()
            assertTrue(done.await(10, TimeUnit.SECONDS))
            Thread.sleep(200)
            assertEquals(1, runs.get(), "待っているあいだの頼みは 1 回にまとまる")

            // 実行が終わったあとの頼みは、また実行する
            c.request()
            Thread.sleep(1500)
            assertEquals(2, runs.get())
        } finally {
            c.stop()
        }
    }

    @Test
    fun requestsDuringARunAreCollectedIntoOneMoreRun() {
        val runs = AtomicInteger()
        val running = CountDownLatch(1)
        val finish = CountDownLatch(1)
        val c = Coalescer("test-coalescer", 20, Runnable {
            if (runs.incrementAndGet() == 1) {
                running.countDown()
                finish.await(5, TimeUnit.SECONDS)
            }
        })
        c.start()
        try {
            c.request()
            assertTrue(running.await(5, TimeUnit.SECONDS))
            for (i in 0 until 1000) c.request()
            finish.countDown()
            Thread.sleep(300)
            assertEquals(2, runs.get(), "実行中の頼みは次の 1 回にまとまる")
        } finally {
            c.stop()
        }
    }

    @Test
    fun nothingRunsWhenStopped() {
        val runs = AtomicInteger()
        val c = Coalescer("test-coalescer", 10, Runnable { runs.incrementAndGet() })
        c.request()
        c.start()
        c.stop()
        c.request()
        Thread.sleep(100)
        assertEquals(0, runs.get())
    }
}
