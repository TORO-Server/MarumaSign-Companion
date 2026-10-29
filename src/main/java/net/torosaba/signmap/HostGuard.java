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

    /** インターネット上の公開アドレスなら true ({@link AddressPolicy}) */
    public static boolean isPublicAddress(InetAddress a) {
        return AddressPolicy.isPublic(a);
    }}
