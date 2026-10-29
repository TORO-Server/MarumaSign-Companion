package net.torosaba.marumasign.companion

import java.lang.management.ManagementFactory
import java.nio.file.Path
import java.util.logging.Logger

/** 計測用: ScanBench <regionディレクトリ> <threads> <pauseMillis> */
object ScanBench {
    @JvmStatic
    fun main(args: Array<String>) {
        val store = SignStore()
        val log = Logger.getLogger("bench")
        val s = WorldScanner(log, "bench", Path.of(args[0]), store, Integer.parseInt(args[1]), java.lang.Long.parseLong(args[2]))
        val rt = Runtime.getRuntime()
        val peak = longArrayOf(0)
        val mon = Thread {
            while (true) {
                peak[0] = Math.max(peak[0], rt.totalMemory() - rt.freeMemory())
                try { Thread.sleep(50) } catch (e: InterruptedException) { return@Thread }
            }
        }
        mon.isDaemon = true
        mon.start()
        val cpu0 = if (ManagementFactory.getThreadMXBean().isCurrentThreadCpuTimeSupported) 0L else 0L
        val sum = s.scan(100, null)
        println("regions=" + sum.regionsScanned + " chunks=" + sum.chunks + " signs=" + sum.rawSigns + " maruma=" + sum.marumaSigns
                + " ms=" + sum.millis + " ms/region=" + (sum.millis / Math.max(1, sum.regionsScanned)) + " peakHeapMB=" + peak[0] / 1048576)
        val os = ManagementFactory.getOperatingSystemMXBean() as com.sun.management.OperatingSystemMXBean
        println("process cpu seconds=" + os.processCpuTime / 1e9)
    }
}
