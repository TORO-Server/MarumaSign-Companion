package net.torosaba.signmap;

import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.IDN;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NoRouteToHostException;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertificateException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;

/**
 * 看板の URL の先へつなぐための、最小の HTTP/1.1 クライアント (GET / HEAD だけ)。
 * <p>
 * プレイヤーが好きな URL を書けるので、サーバーを踏み台にされないよう、次のことを守る。
 * <ul>
 *   <li>名前を引いた<b>すべてのアドレス</b>が公開アドレスでなければつながない。つなぐのは、その検証したアドレスにだけ
 *       (検証と接続のあいだで名前を引き直さないので、DNS rebinding で内部アドレスに向けられない)。</li>
 *   <li>TLS は既定のトラストストアで、ホスト名の検証 (endpoint identification) と SNI を必ず付ける。TLS 1.2 以上。検証は緩めない。</li>
 *   <li>全体の期限 (デッドライン) で、小出しに送ってくる応答を切る。ヘッダーの行の長さ・行数・合計の大きさと、本文の大きさに上限。</li>
 *   <li>Accept-Encoding は送らず、圧縮された本文は展開しない (読んで捨てるだけ)。Cookie・認証・Referer は送らない。転送は追わない。</li>
 *   <li>失敗は固定の分類 ({@link Kind}) だけで返す。例外の文や応答の中身は外へ出さない。</li>
 * </ul>
 */
final class SafeHttp {

    enum Kind {
        /** 名前が引けない (存在しない) */
        DNS,
        /** 名前を引く処理がタイムアウトした (一時的かもしれない) */
        DNS_TIMEOUT,
        /** 名前を引く処理の枠が、ほかの (引き終わらない) 問い合わせで埋まっている。こちらの失敗ではないので、あとでやり直す */
        DNS_BUSY,
        /** 公開でないアドレスを含む */
        UNSAFE_ADDRESS,
        CONNECT_REFUSED,
        CONNECT_TIMEOUT,
        CONNECT_FAILED,
        /** 証明書・ホスト名の検証に失敗した、または TLS が成立しない */
        TLS,
        TIMEOUT,
        RESET,
        /** HTTP として読めない、または上限を超える応答 */
        BAD_RESPONSE,
        /** 呼び出しが正しくない (URL の形など) */
        BAD_REQUEST
    }

    /** 失敗。メッセージは固定の分類名だけ (詳細は持たない) */
    static final class HttpFailure extends IOException {
        final Kind kind;

        HttpFailure(Kind kind) {
            super(kind.name());
            this.kind = kind;
        }
    }

    interface Resolver {
        InetAddress[] resolve(String host) throws UnknownHostException;
    }

    interface Connector {
        Socket connect(InetAddress address, int port, int timeoutMillis) throws IOException;
    }

    /** 応答 (ヘッダーのうち、使うものだけ。長さは制限済み) */
    record Response(int status, String location, String contentType, String retryAfter, long bodyBytes) {
    }

    static final class Options {
        int connectTimeoutMillis = 5_000;
        int totalTimeoutMillis = 20_000;
        int maxHeaderBytes = 32 * 1024;
        int maxHeaderLines = 100;
        int maxLineBytes = 8 * 1024;
        int maxBodyBytes = 64 * 1024;
        String userAgent = "BlueMapMarumaSign-LinkCheck/1.0";
        // ---- 次の 4 つはテスト用 (設定ファイルからは変えられない) ----
        boolean allowNonPublicForTests;
        /** 接続先が自分自身のアドレスでも閉じない (ループバックのサーバーへ向け直すテスト用) */
        boolean allowSelfConnectForTests;
        SSLContext sslContext;
        Resolver resolver;
        Connector connector;
        java.util.function.Supplier<java.util.Collection<InetAddress>> localAddresses;
        /** 設定の「接続しないアドレス」(サーバー自身の公開アドレスなど)。null なら何もしない */
        AddressDenyList denyList;
    }

    private static final Pattern STATUS = Pattern.compile("^HTTP/1\\.[01] (\\d{3})(?: .*)?$");
    private static final Pattern HEADER_NAME = Pattern.compile("^[!#$%&'*+.^_`|~0-9A-Za-z-]+$");
    private static final int MAX_ADDRESSES = 4;

    private final Options o;
    private final Resolver resolver;
    private final Connector connector;

    SafeHttp(Options options) {
        this.o = options;
        this.resolver = options.resolver != null ? options.resolver : systemResolver(5_000);
        this.connector = options.connector != null ? options.connector : SafeHttp::plainConnect;
    }

    private static Socket plainConnect(InetAddress address, int port, int timeoutMillis) throws IOException {
        final Socket s = new Socket();
        try {
            s.setTcpNoDelay(true);
            s.connect(new InetSocketAddress(address, port), timeoutMillis);
            return s;
        } catch (IOException | RuntimeException e) {
            try {
                s.close();
            } catch (IOException ignored) {
                // 閉じられなくてもよい
            }
            throw e;
        }
    }

    // ---- 名前を引く ----

    private static final class DnsTimeout extends UnknownHostException {
        DnsTimeout() {
            super("timeout");
        }
    }

    private static final class DnsBusy extends UnknownHostException {
        DnsBusy() {
            super("busy");
        }
    }

    interface Lookup {
        InetAddress[] lookup(String host) throws UnknownHostException;
    }

    /**
     * システムの名前の引き方 (引き終わらないときのために、時間を切る)。
     * 同時に引ける数に上限を設ける。引き終わらない問い合わせ (応答しない DNS サーバーなど) は、
     * OS の時間切れまで枠を占めるので、枠が埋まったときは DNS_BUSY (こちらの失敗ではない) にして、ほかの URL の判定を誤らせない。
     * スレッドはこの引き方ごとに持ち、close() で止める。
     */
    static final class SystemResolver implements Resolver, AutoCloseable {
        private final int timeoutMillis;
        private final Lookup lookup;
        private final Semaphore permits;
        private final ExecutorService pool = Executors.newCachedThreadPool(r -> {
            final Thread t = new Thread(r, "BlueMapMarumaSign-http-dns");
            t.setDaemon(true);
            t.setPriority(Thread.MIN_PRIORITY);
            return t;
        });

        SystemResolver(int timeoutMillis, Lookup lookup, int slots) {
            this.timeoutMillis = timeoutMillis;
            this.lookup = lookup;
            this.permits = new Semaphore(slots);
        }

        @Override
        public InetAddress[] resolve(String host) throws UnknownHostException {
            if (!permits.tryAcquire()) throw new DnsBusy();
            final Future<InetAddress[]> f;
            try {
                f = pool.submit(() -> {
                    try {
                        return lookup.lookup(host);
                    } finally {
                        permits.release(); // 引き終わるまで枠を返さない (時間切れで諦めても、裏で続いているため)
                    }
                });
            } catch (RuntimeException e) {
                permits.release();
                throw new DnsBusy();
            }
            try {
                return f.get(timeoutMillis, TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                f.cancel(true);
                throw new DnsTimeout();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new DnsTimeout();
            } catch (ExecutionException e) {
                if (e.getCause() instanceof UnknownHostException u) throw u;
                throw new UnknownHostException("failed");
            }
        }

        @Override
        public void close() {
            pool.shutdownNow();
        }
    }

    static Resolver systemResolver(int timeoutMillis) {
        return new SystemResolver(timeoutMillis, InetAddress::getAllByName, 4);
    }

    static Resolver systemResolver(int timeoutMillis, Lookup lookup, int slots) {
        return new SystemResolver(timeoutMillis, lookup, slots);
    }
    // ---- 期限の見張り ----

    // 全体の期限に、ソケットを閉じる。SO_TIMEOUT は「1 回の read」の時間なので、TLS のように 1 つのレコードを
    // 何回かの read で集める処理を、小出しに送られると、期限を過ぎても続いてしまう。閉じれば、止まっている read が必ず終わる
    private final ScheduledExecutorService watchdogPool = Executors.newSingleThreadScheduledExecutor(r -> {
        final Thread t = new Thread(r, "BlueMapMarumaSign-http-watchdog");
        t.setDaemon(true);
        return t;
    });

    private final java.util.Set<Socket> openSockets = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** 開いているソケットをすべて閉じる (止まっている read が終わる) */
    void closeAll() {
        for (Socket s : openSockets) closeQuietly(s);
    }

    /** 止める (プラグインを止めるとき): ソケットを閉じて、見張りと名前を引くスレッドを止める */
    void close() {
        closeAll();
        watchdogPool.shutdownNow();
        if (resolver instanceof SystemResolver r) r.close();
    }

    private static void closeQuietly(Socket s) {
        try {
            s.close();
        } catch (IOException ignored) {
            // 閉じられなくてもよい
        }
    }

    // ---- 自分自身のアドレス ----

    private volatile java.util.List<InetAddress> cachedLocal = java.util.List.of();
    private volatile long cachedLocalAt;

    private boolean isLocalAddress(InetAddress a) {
        java.util.Collection<InetAddress> locals;
        if (o.localAddresses != null) {
            locals = o.localAddresses.get();
        } else {
            final long now = System.nanoTime();
            if (now - cachedLocalAt > 30_000_000_000L || cachedLocal.isEmpty()) {
                final java.util.List<InetAddress> list = new ArrayList<>();
                try {
                    final var nis = java.net.NetworkInterface.getNetworkInterfaces();
                    while (nis != null && nis.hasMoreElements()) {
                        final var addrs = nis.nextElement().getInetAddresses();
                        while (addrs.hasMoreElements()) list.add(addrs.nextElement());
                    }
                } catch (SocketException e) {
                    // 取れなければ、空 (ほかの検査は有効)
                }
                cachedLocal = list;
                cachedLocalAt = now;
            }
            locals = cachedLocal;
        }
        for (InetAddress l : locals) if (l.equals(a)) return true;
        return false;
    }
    // ---- リクエスト ----

    /**
     * 1 回のリクエスト (転送は追わない)。
     *
     * @param method "HEAD" か "GET"
     * @param range  null でなければ Range ヘッダーの値 (例 "bytes=0-1023")
     */
    Response request(String method, URI uri, String range) throws HttpFailure {
        if (!method.equals("HEAD") && !method.equals("GET")) throw new HttpFailure(Kind.BAD_REQUEST);
        final long deadline = System.nanoTime() + o.totalTimeoutMillis * 1_000_000L;
        final String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        final boolean tls = scheme.equals("https");
        if (!tls && !scheme.equals("http")) throw new HttpFailure(Kind.BAD_REQUEST);
        String host = uri.getHost();
        if (host == null || host.isEmpty()) throw new HttpFailure(Kind.BAD_REQUEST);
        final int port = uri.getPort() >= 0 ? uri.getPort() : tls ? 443 : 80;
        if (port < 1 || port > 65535) throw new HttpFailure(Kind.BAD_REQUEST);

        final boolean literal = host.startsWith("[");
        final String asciiHost;
        final InetAddress[] addresses;
        try {
            if (literal) {
                asciiHost = host;
                addresses = new InetAddress[]{InetAddress.getByName(host.substring(1, host.length() - 1))};
            } else {
                asciiHost = IDN.toASCII(host.toLowerCase(Locale.ROOT));
                addresses = resolver.resolve(asciiHost);
            }
        } catch (DnsBusy e) {
            throw new HttpFailure(Kind.DNS_BUSY);
        } catch (DnsTimeout e) {
            throw new HttpFailure(Kind.DNS_TIMEOUT);
        } catch (UnknownHostException | IllegalArgumentException e) {
            throw new HttpFailure(Kind.DNS);
        }
        if (addresses == null || addresses.length == 0) throw new HttpFailure(Kind.DNS);
        // 1 つでも公開でないアドレスがあれば、そのホストにはつながない。
        // 公開のアドレスでも、設定で除いたもの (サーバー自身の公開アドレスなど) と、このサーバー自身のアドレスは除く
        // (自分の公開アドレスへの接続は、同じ機械の中を通って、外から守られているはずのサービスに届いてしまうため)
        if (!o.allowNonPublicForTests) {
            for (InetAddress a : addresses) {
                if (!AddressPolicy.isPublic(a) || isLocalAddress(a) || (o.denyList != null && o.denyList.matches(a))) {
                    throw new HttpFailure(Kind.UNSAFE_ADDRESS);
                }
            }
        }

        Socket socket = null;
        HttpFailure lastConnectFailure = new HttpFailure(Kind.CONNECT_FAILED);
        for (int i = 0; i < Math.min(addresses.length, MAX_ADDRESSES) && socket == null; i++) {
            final int remaining = remainingMillis(deadline);
            if (remaining <= 0) throw new HttpFailure(Kind.CONNECT_TIMEOUT);
            try {
                socket = connector.connect(addresses[i], port, Math.min(o.connectTimeoutMillis, remaining));
            } catch (SocketTimeoutException e) {
                lastConnectFailure = new HttpFailure(Kind.CONNECT_TIMEOUT);
            } catch (ConnectException e) {
                lastConnectFailure = new HttpFailure(e.getMessage() != null && e.getMessage().toLowerCase(Locale.ROOT).contains("timed out")
                        ? Kind.CONNECT_TIMEOUT : Kind.CONNECT_REFUSED);
            } catch (NoRouteToHostException e) {
                lastConnectFailure = new HttpFailure(Kind.CONNECT_FAILED);
            } catch (IOException | RuntimeException e) {
                lastConnectFailure = new HttpFailure(Kind.CONNECT_FAILED);
            }
        }
        if (socket == null) throw lastConnectFailure;

        // つないだ先が自分自身のアドレスなら、閉じる (名前の引き方に関係なく、機械の中へ向かう接続を防ぐ)
        if (!o.allowNonPublicForTests && !o.allowSelfConnectForTests && socket.getInetAddress() != null && socket.getLocalAddress() != null
                && socket.getInetAddress().equals(socket.getLocalAddress())) {
            closeQuietly(socket);
            throw new HttpFailure(Kind.UNSAFE_ADDRESS);
        }

        final Socket plain = socket;
        openSockets.add(plain);
        final AtomicBoolean timedOut = new AtomicBoolean();
        final ScheduledFuture<?> watchdog = watchdogPool.schedule(() -> {
            timedOut.set(true);
            closeQuietly(plain);
        }, Math.max(1, remainingMillis(deadline)), TimeUnit.MILLISECONDS);
        try (Socket s = socket) {
            Socket io = s;
            if (tls) io = handshake(s, asciiHost, port, deadline, literal);
            final String target = pathAndQuery(uri);
            final StringBuilder req = new StringBuilder(256);
            req.append(method).append(' ').append(target).append(" HTTP/1.1\r\n");
            req.append("Host: ").append(hostHeader(asciiHost, port, tls)).append("\r\n");
            req.append("User-Agent: ").append(o.userAgent).append("\r\n");
            req.append("Accept: */*\r\n");
            if (range != null) req.append("Range: ").append(range).append("\r\n");
            req.append("Connection: close\r\n\r\n");
            setTimeout(io, deadline);
            final OutputStream out = io.getOutputStream();
            out.write(req.toString().getBytes(StandardCharsets.ISO_8859_1));
            out.flush();
            return readResponse(io, method.equals("GET"), deadline);
        } catch (HttpFailure f) {
            throw timedOut.get() ? new HttpFailure(Kind.TIMEOUT) : f;
        } catch (IOException | RuntimeException e) {
            throw new HttpFailure(timedOut.get() ? Kind.TIMEOUT : classify(e));
        } finally {
            watchdog.cancel(false);
            openSockets.remove(plain);
        }
    }

    private Socket handshake(Socket plain, String host, int port, long deadline, boolean literal) throws IOException {
        final SSLContext ctx;
        try {
            ctx = o.sslContext != null ? o.sslContext : SSLContext.getDefault();
        } catch (NoSuchAlgorithmException e) {
            throw new HttpFailure(Kind.TLS);
        }
        final SSLSocket ssl = (SSLSocket) ctx.getSocketFactory().createSocket(plain, host, port, true);
        final SSLParameters p = ssl.getSSLParameters();
        // これを設定しないと、証明書のホスト名が検証されない
        p.setEndpointIdentificationAlgorithm("HTTPS");
        p.setProtocols(new String[]{"TLSv1.3", "TLSv1.2"});
        if (!literal) p.setServerNames(List.of(new SNIHostName(host)));
        ssl.setSSLParameters(p);
        setTimeout(ssl, deadline);
        ssl.startHandshake();
        return ssl;
    }

    private static String hostHeader(String host, int port, boolean tls) {
        final int def = tls ? 443 : 80;
        return port == def ? host : host + ":" + port;
    }

    private static String pathAndQuery(URI uri) {
        String path = uri.getRawPath();
        if (path == null || path.isEmpty()) path = "/";
        final String q = uri.getRawQuery();
        final String target = q == null ? path : path + "?" + q;
        for (int i = 0; i < target.length(); i++) {
            final char c = target.charAt(i);
            if (c <= ' ' || c >= 0x7F) throw new IllegalArgumentException("bad request target");
        }
        return target;
    }

    private static int remainingMillis(long deadline) {
        final long ms = (deadline - System.nanoTime()) / 1_000_000L;
        return ms > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) ms;
    }

    private static void setTimeout(Socket s, long deadline) throws IOException {
        final int remaining = remainingMillis(deadline);
        if (remaining <= 0) throw new HttpFailure(Kind.TIMEOUT);
        s.setSoTimeout(remaining);
    }

    // ---- 応答 ----

    private Response readResponse(Socket s, boolean readBody, long deadline) throws IOException {
        final InputStream in = s.getInputStream();
        final Reader r = new Reader(s, in, deadline);
        int total = 0;
        int status = -1;
        for (int informational = 0; informational < 5; informational++) {
            final String line = r.readLine(o.maxLineBytes);
            total += line.length() + 2;
            if (total > o.maxHeaderBytes) throw new HttpFailure(Kind.BAD_RESPONSE);
            final var m = STATUS.matcher(line);
            if (!m.matches()) throw new HttpFailure(Kind.BAD_RESPONSE);
            status = Integer.parseInt(m.group(1));
            if (status < 100 || status > 599) throw new HttpFailure(Kind.BAD_RESPONSE);
            String location = null, contentType = null, retryAfter = null;
            int lines = 0;
            while (true) {
                final String h = r.readLine(o.maxLineBytes);
                total += h.length() + 2;
                if (total > o.maxHeaderBytes || ++lines > o.maxHeaderLines) throw new HttpFailure(Kind.BAD_RESPONSE);
                if (h.isEmpty()) break;
                final int colon = h.indexOf(':');
                if (colon <= 0 || h.charAt(0) == ' ' || h.charAt(0) == '\t') throw new HttpFailure(Kind.BAD_RESPONSE);
                final String name = h.substring(0, colon);
                if (!HEADER_NAME.matcher(name).matches()) throw new HttpFailure(Kind.BAD_RESPONSE);
                final String value = h.substring(colon + 1).trim();
                switch (name.toLowerCase(Locale.ROOT)) {
                    case "location" -> {
                        if (location == null) location = value;
                    }
                    case "content-type" -> {
                        if (contentType == null) contentType = value;
                    }
                    case "retry-after" -> {
                        if (retryAfter == null) retryAfter = value;
                    }
                    default -> {
                    }
                }
            }
            if (status >= 200 || status == 101) {
                if (status == 101) throw new HttpFailure(Kind.BAD_RESPONSE);
                final long body = readBody && status != 204 && status != 304 ? r.discard(o.maxBodyBytes) : 0;
                return new Response(status, limit(location, UrlFilter.MAX_URL_LENGTH + 100), mediaType(contentType),
                        limit(retryAfter, 64), body);
            }
            // 1xx (100 Continue など) は読み飛ばして、続く本当の応答を読む
        }
        throw new HttpFailure(Kind.BAD_RESPONSE);
    }

    private static String limit(String s, int max) {
        return s == null || s.length() > max ? null : s;
    }

    /** Content-Type の "type/subtype" だけ (小文字。パラメーターは捨てる) */
    static String mediaType(String contentType) {
        if (contentType == null) return "";
        final int semi = contentType.indexOf(';');
        final String t = (semi >= 0 ? contentType.substring(0, semi) : contentType).trim().toLowerCase(Locale.ROOT);
        return t.length() > 100 ? "" : t;
    }

    /** 期限つきで 1 バイトずつ読む入力 (小出しに送られても、全体の期限で切る) */
    private final class Reader {
        private final Socket socket;
        private final InputStream in;
        private final long deadline;
        private final byte[] buf = new byte[4096];
        private int pos, len;

        Reader(Socket socket, InputStream in, long deadline) {
            this.socket = socket;
            this.in = in;
            this.deadline = deadline;
        }

        private boolean fill() throws IOException {
            if (pos < len) return true;
            setTimeout(socket, deadline);
            final int n = in.read(buf);
            if (n < 0) return false;
            pos = 0;
            len = n;
            return n > 0 || fill();
        }

        String readLine(int maxBytes) throws IOException {
            final StringBuilder sb = new StringBuilder();
            while (true) {
                if (!fill()) throw new HttpFailure(Kind.BAD_RESPONSE);
                final int c = buf[pos++] & 0xFF;
                if (c == '\n') {
                    final int n = sb.length();
                    if (n > 0 && sb.charAt(n - 1) == '\r') sb.setLength(n - 1);
                    return sb.toString();
                }
                if (c == 0 || (c < 0x20 && c != '\t' && c != '\r')) throw new HttpFailure(Kind.BAD_RESPONSE);
                if (sb.length() >= maxBytes) throw new HttpFailure(Kind.BAD_RESPONSE);
                sb.append((char) c);
            }
        }

        /** 本文を最大 max バイトまで読んで捨てる (圧縮されていても展開しない)。読めたバイト数 */
        long discard(int max) {
            long total = 0;
            try {
                while (total < max) {
                    if (!fill()) break;
                    final int n = Math.min(len - pos, (int) (max - total));
                    pos += n;
                    total += n;
                }
            } catch (IOException e) {
                // ステータスはもう分かっている。本文が途中で切れても、結果には関係ない
            }
            return total;
        }
    }

    // ---- 失敗の分類 ----

    static Kind classify(Throwable e) {
        if (e instanceof HttpFailure f) return f.kind;
        if (e instanceof SocketTimeoutException) return Kind.TIMEOUT;
        if (e instanceof SSLException ssl) {
            for (Throwable t = ssl; t != null; t = t.getCause()) {
                if (t instanceof CertificateException) return Kind.TLS;
            }
            final String m = ssl.getMessage() == null ? "" : ssl.getMessage().toLowerCase(Locale.ROOT);
            if (m.contains("terminated") || m.contains("reset") || m.contains("eof") || m.contains("closed") || m.contains("broken pipe")) {
                return Kind.RESET;
            }
            return Kind.TLS;
        }
        if (e instanceof SocketException) return Kind.RESET;
        if (e instanceof IOException) return Kind.RESET;
        return Kind.BAD_REQUEST;
    }

    /** テスト用: 複数のアドレスを返す名前の引き方を作る */
    static Resolver fixedResolver(java.util.Map<String, InetAddress[]> table) {
        return host -> {
            final InetAddress[] a = table.get(host);
            if (a == null) throw new UnknownHostException(host);
            return new ArrayList<>(List.of(a)).toArray(new InetAddress[0]);
        };
    }
}
