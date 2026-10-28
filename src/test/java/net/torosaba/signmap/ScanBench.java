package net.torosaba.signmap;

import java.nio.file.Path;
import java.util.logging.Logger;

/** 計測用: ScanBench <regionディレクトリ> <threads> <pauseMillis> */
public final class ScanBench {
    public static void main(String[] args) throws Exception {
        final SignStore store = new SignStore();
        final Logger log = Logger.getLogger("bench");
        final WorldScanner s = new WorldScanner(log, "bench", Path.of(args[0]), store, Integer.parseInt(args[1]), Long.parseLong(args[2]));
        final Runtime rt = Runtime.getRuntime();
        final long[] peak = {0};
        final Thread mon = new Thread(() -> {
            while (true) {
                peak[0] = Math.max(peak[0], rt.totalMemory() - rt.freeMemory());
                try { Thread.sleep(50); } catch (InterruptedException e) { return; }
            }
        });
        mon.setDaemon(true);
        mon.start();
        final long cpu0 = java.lang.management.ManagementFactory.getThreadMXBean().isCurrentThreadCpuTimeSupported() ? 0 : 0;
        final WorldScanner.Summary sum = s.scan(100, null);
        System.out.println("regions=" + sum.regionsScanned + " chunks=" + sum.chunks + " signs=" + sum.rawSigns + " maruma=" + sum.marumaSigns
                + " ms=" + sum.millis + " ms/region=" + (sum.millis / Math.max(1, sum.regionsScanned)) + " peakHeapMB=" + peak[0] / 1048576);
        final com.sun.management.OperatingSystemMXBean os = (com.sun.management.OperatingSystemMXBean) java.lang.management.ManagementFactory.getOperatingSystemMXBean();
        System.out.println("process cpu seconds=" + os.getProcessCpuTime() / 1e9);
    }
}
