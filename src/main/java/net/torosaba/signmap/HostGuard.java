package net.torosaba.signmap;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * 看板の URL のホスト名が、公開のアドレスを指しているかを DNS で確かめる。
 * <p>
 * 地図を見る人のブラウザーが画像を読みに行くので、ホスト名が家庭内や社内のアドレス (192.168.x など) を指すように
 * 仕込まれていると、その人のルーターなどにリクエストを送らせることができてしまう。{@link UrlFilter} は文字だけで判定するので、
 * 名前を引いた結果がそういうアドレスになるホストは、ここで除く。
 * <p>
 * 確かめられるまでは「公開ではない」として扱う (安全側)。確かめた結果は一定時間覚え、古くなったら引き直す。
 */
public final class HostGuard {

    private static final long PUBLIC_TTL_MILLIS = 6L * 60 * 60 * 1000;
    private static final long RECHECK_MILLIS = 30L * 60 * 1000;
    private static final long REJECT_TTL_MILLIS = 10L * 60 * 1000;
    private static final int MAX_QUEUE = 20_000;
    private static final int THREADS = 4;

    private record Entry(boolean isPublic, long time) {
    }

    /** 名前を引く処理 (テストで差し替える) */
    interface Lookup {
        InetAddress[] lookup(String host) throws UnknownHostException;
    }

    private final Logger log;
    private final boolean enabled;
    private final Runnable onProgress;
    private final Lookup lookup;
    private final Map<String, Entry> cache = new ConcurrentHashMap<>();
    private final LinkedHashSet<String> queue = new LinkedHashSet<>();
    private final Set<String> inFlight = ConcurrentHashMap.newKeySet();
    private Thread[] workers;
    private volatile boolean stopped;
    private final java.util.concurrent.atomic.AtomicInteger changes = new java.util.concurrent.atomic.AtomicInteger();

    public HostGuard(Logger log, boolean enabled, Runnable onProgress) {
        this(log, enabled, onProgress, InetAddress::getAllByName);
    }

    HostGuard(Logger log, boolean enabled, Runnable onProgress, Lookup lookup) {
        this.log = log;
        this.enabled = enabled;
        this.onProgress = onProgress;
        this.lookup = lookup;
    }

    /** そのホストが公開のアドレスだと確かめられていれば true。まだなら確かめるよう頼んで false */
    public boolean isPublic(String host) {
        if (!enabled) return true;
        if (host == null || host.isEmpty()) return false;
        final String h = host.toLowerCase(Locale.ROOT);
        final Entry e = cache.get(h);
        final long now = System.currentTimeMillis();
        if (e != null) {
            final long age = now - e.time();
            if (e.isPublic()) {
                if (age > RECHECK_MILLIS) enqueue(h);
                if (age < PUBLIC_TTL_MILLIS) return true;
                return false;
            }
            if (age > REJECT_TTL_MILLIS) enqueue(h);
            return false;
        }
        // 数字だけのホスト (IP アドレス) は UrlFilter が判定済みで、名前は引けない
        enqueue(h);
        return false;
    }

    private void enqueue(String host) {
        synchronized (queue) {
            if (queue.size() >= MAX_QUEUE || inFlight.contains(host)) return;
            if (queue.add(host)) queue.notify();
        }
    }

    public int pending() {
        synchronized (queue) {
            return queue.size() + inFlight.size();
        }
    }

    public synchronized void start() {
        if (!enabled || workers != null) return;
        stopped = false;
        workers = new Thread[THREADS];
        for (int i = 0; i < THREADS; i++) {
            workers[i] = new Thread(this::run, "BlueMapMarumaSign-dns-" + i);
            workers[i].setDaemon(true);
            workers[i].setPriority(Thread.MIN_PRIORITY);
            workers[i].start();
        }
    }

    public synchronized void stop() {
        stopped = true;
        synchronized (queue) {
            queue.notifyAll();
        }
        if (workers != null) for (Thread t : workers) t.interrupt();
        workers = null;
    }

    private void run() {
        try {
            while (!stopped) {
                String host;
                synchronized (queue) {
                    while (queue.isEmpty() && !stopped) {
                        if (changes.get() > 0 && inFlight.isEmpty()) {
                            changes.set(0);
                            break;
                        }
                        queue.wait(5_000);
                    }
                    if (stopped) return;
                    if (queue.isEmpty()) {
                        host = null;
                    } else {
                        final var it = queue.iterator();
                        host = it.next();
                        it.remove();
                        inFlight.add(host);
                    }
                }
                if (host == null) {
                    try {
                        onProgress.run();
                    } catch (RuntimeException e) {
                        log.warning("Host check callback failed: " + e);
                    }
                    continue;
                }
                boolean pub;
                try {
                    pub = check(host);
                } finally {
                    inFlight.remove(host);
                }
                final Entry before = cache.put(host, new Entry(pub, System.currentTimeMillis()));
                if (before == null || before.isPublic() != pub) changes.incrementAndGet();
                synchronized (queue) {
                    queue.notifyAll();
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private boolean check(String host) {
        try {
            final InetAddress[] addresses = lookup.lookup(host);
            if (addresses == null || addresses.length == 0) return false;
            for (InetAddress a : addresses) {
                if (!isPublicAddress(a)) {
                    log.fine("Host " + host + " resolves to a non-public address; ignoring its URLs");
                    return false;
                }
            }
            return true;
        } catch (UnknownHostException e) {
            return false;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** インターネット上の公開アドレスなら true (ループバック、家庭内・社内、リンクローカル、予約済みなどは false) */
    public static boolean isPublicAddress(InetAddress a) {
        if (a.isAnyLocalAddress() || a.isLoopbackAddress() || a.isLinkLocalAddress() || a.isSiteLocalAddress() || a.isMulticastAddress()) {
            return false;
        }
        final byte[] b = a.getAddress();
        if (b.length == 4) return isPublicIpv4(b);
        if (b.length == 16) {
            final int b0 = b[0] & 0xFF, b1 = b[1] & 0xFF;
            if ((b0 & 0xFE) == 0xFC) return false; // fc00::/7 (ULA)
            if (b0 == 0xFE && (b1 & 0xC0) == 0x80) return false; // fe80::/10
            if (b0 == 0xFE && (b1 & 0xC0) == 0xC0) return false; // fec0::/10 (旧 site-local)
            if (b0 == 0x20 && b1 == 0x01 && b[2] == 0x0D && (b[3] & 0xFF) == 0xB8) return false; // 2001:db8::/32 (文書用)
            if (b0 == 0x20 && b1 == 0x02) return false; // 6to4 (中に IPv4 を埋め込める)
            if (b0 == 0x00 && b1 == 0x64 && (b[2] & 0xFF) == 0xFF && (b[3] & 0xFF) == 0x9B) return false; // 64:ff9b::/96 (NAT64)
            if (b0 == 0x01 && b1 == 0x00) return false; // 100::/64 (破棄用)
            boolean zeros = true;
            for (int i = 0; i < 10; i++) if (b[i] != 0) zeros = false;
            if (zeros) {
                // ::/80 の下 (IPv4 互換、IPv4 射影)
                return false;
            }
            return true;
        }
        return false;
    }

    private static boolean isPublicIpv4(byte[] b) {
        final int a = b[0] & 0xFF, c = b[1] & 0xFF, d = b[2] & 0xFF;
        if (a == 0 || a == 10 || a == 127) return false;
        if (a == 100 && (c & 0xC0) == 64) return false; // 100.64.0.0/10
        if (a == 169 && c == 254) return false;
        if (a == 172 && (c & 0xF0) == 16) return false;
        if (a == 192 && c == 168) return false;
        if (a == 192 && c == 0 && (d == 0 || d == 2)) return false; // 192.0.0.0/24, 192.0.2.0/24
        if (a == 198 && (c == 18 || c == 19)) return false; // 198.18.0.0/15
        if (a == 198 && c == 51 && d == 100) return false;
        if (a == 203 && c == 0 && d == 113) return false;
        return a < 224; // 224 以上は マルチキャストと予約済み
    }
}
