package net.torosaba.signmap

import java.io.IOException
import java.io.InputStream
import java.net.ConnectException
import java.net.IDN
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.NoRouteToHostException
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.URI
import java.net.UnknownHostException
import java.nio.charset.StandardCharsets
import java.security.NoSuchAlgorithmException
import java.security.cert.CertificateException
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.function.Supplier
import java.util.regex.Pattern
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLException
import javax.net.ssl.SSLSocket

/**
 * 看板の URL の先へつなぐための、最小の HTTP/1.1 クライアント (GET / HEAD だけ)。
 *
 * プレイヤーが好きな URL を書けるので、サーバーを踏み台にされないよう、次のことを守る。
 * - 名前を引いた**すべてのアドレス**が公開アドレスでなければつながない。つなぐのは、その検証したアドレスにだけ
 *   (検証と接続のあいだで名前を引き直さないので、DNS rebinding で内部アドレスに向けられない)。
 * - TLS は既定のトラストストアで、ホスト名の検証 (endpoint identification) と SNI を必ず付ける。TLS 1.2 以上。検証は緩めない。
 * - 全体の期限 (デッドライン) で、小出しに送ってくる応答を切る。ヘッダーの行の長さ・行数・合計の大きさと、本文の大きさに上限。
 * - Accept-Encoding は送らず、圧縮された本文は展開しない (読んで捨てるだけ)。Cookie・認証・Referer は送らない。転送は追わない。
 * - 失敗は固定の分類 ([Kind]) だけで返す。例外の文や応答の中身は外へ出さない。
 */
internal class SafeHttp(private val o: Options) {

    enum class Kind {
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
    class HttpFailure(@JvmField val kind: Kind) : IOException(kind.name)

    fun interface Resolver {
        @Throws(UnknownHostException::class)
        fun resolve(host: String): Array<InetAddress>?
    }

    fun interface Connector {
        @Throws(IOException::class)
        fun connect(address: InetAddress, port: Int, timeoutMillis: Int): Socket?
    }

    /** 応答 (ヘッダーのうち、使うものだけ。長さは制限済み) */
    data class Response(val status: Int, val location: String?, val contentType: String, val retryAfter: String?, val bodyBytes: Long)

    class Options {
        @JvmField var connectTimeoutMillis = 5_000
        @JvmField var totalTimeoutMillis = 20_000
        @JvmField var maxHeaderBytes = 32 * 1024
        @JvmField var maxHeaderLines = 100
        @JvmField var maxLineBytes = 8 * 1024
        @JvmField var maxBodyBytes = 64 * 1024
        @JvmField var userAgent = "BlueMapMarumaSign-LinkCheck/1.0"
        // ---- 次の 4 つはテスト用 (設定ファイルからは変えられない) ----
        @JvmField var allowNonPublicForTests = false
        /** 接続先が自分自身のアドレスでも閉じない (ループバックのサーバーへ向け直すテスト用) */
        @JvmField var allowSelfConnectForTests = false
        @JvmField var sslContext: SSLContext? = null
        @JvmField var resolver: Resolver? = null
        @JvmField var connector: Connector? = null
        @JvmField var localAddresses: Supplier<Collection<InetAddress>>? = null
        /** 設定の「接続しないアドレス」(サーバー自身の公開アドレスなど)。null なら何もしない */
        @JvmField var denyList: AddressDenyList? = null
    }

    private val resolver: Resolver = o.resolver ?: systemResolver(5_000)
    private val connector: Connector = o.connector ?: Connector(::plainConnect)

    // ---- 名前を引く ----

    private class DnsTimeout : UnknownHostException("timeout")

    private class DnsBusy : UnknownHostException("busy")

    fun interface Lookup {
        @Throws(UnknownHostException::class)
        fun lookup(host: String): Array<InetAddress>?
    }

    /**
     * システムの名前の引き方 (引き終わらないときのために、時間を切る)。
     * 同時に引ける数に上限を設ける。引き終わらない問い合わせ (応答しない DNS サーバーなど) は、
     * OS の時間切れまで枠を占めるので、枠が埋まったときは DNS_BUSY (こちらの失敗ではない) にして、ほかの URL の判定を誤らせない。
     * スレッドはこの引き方ごとに持ち、close() で止める。
     */
    class SystemResolver(private val timeoutMillis: Int, private val lookup: Lookup, slots: Int) : Resolver, AutoCloseable {
        private val permits = Semaphore(slots)
        private val pool: ExecutorService = Executors.newCachedThreadPool { r ->
            val t = Thread(r, "BlueMapMarumaSign-http-dns")
            t.isDaemon = true
            t.priority = Thread.MIN_PRIORITY
            t
        }

        @Throws(UnknownHostException::class)
        override fun resolve(host: String): Array<InetAddress>? {
            if (!permits.tryAcquire()) throw DnsBusy()
            val f: Future<Array<InetAddress>?>
            try {
                f = pool.submit(Callable {
                    try {
                        lookup.lookup(host)
                    } finally {
                        permits.release() // 引き終わるまで枠を返さない (時間切れで諦めても、裏で続いているため)
                    }
                })
            } catch (e: RuntimeException) {
                permits.release()
                throw DnsBusy()
            }
            try {
                return f.get(timeoutMillis.toLong(), TimeUnit.MILLISECONDS)
            } catch (e: TimeoutException) {
                f.cancel(true)
                throw DnsTimeout()
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw DnsTimeout()
            } catch (e: ExecutionException) {
                val cause = e.cause
                if (cause is UnknownHostException) throw cause
                throw UnknownHostException("failed")
            }
        }

        override fun close() {
            pool.shutdownNow()
        }
    }

    // ---- 期限の見張り ----

    // 全体の期限に、ソケットを閉じる。SO_TIMEOUT は「1 回の read」の時間なので、TLS のように 1 つのレコードを
    // 何回かの read で集める処理を、小出しに送られると、期限を過ぎても続いてしまう。閉じれば、止まっている read が必ず終わる
    private val watchdogPool: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r ->
        val t = Thread(r, "BlueMapMarumaSign-http-watchdog")
        t.isDaemon = true
        t
    }

    private val openSockets: MutableSet<Socket> = ConcurrentHashMap.newKeySet()

    /** 開いているソケットをすべて閉じる (止まっている read が終わる) */
    fun closeAll() {
        for (s in openSockets) closeQuietly(s)
    }

    /** 止める (プラグインを止めるとき): ソケットを閉じて、見張りと名前を引くスレッドを止める */
    fun close() {
        closeAll()
        watchdogPool.shutdownNow()
        (resolver as? SystemResolver)?.close()
    }

    // ---- 自分自身のアドレス ----

    @Volatile
    private var cachedLocal: List<InetAddress> = listOf()
    @Volatile
    private var cachedLocalAt: Long = 0

    private fun isLocalAddress(a: InetAddress): Boolean {
        val locals: Collection<InetAddress>
        val supplier = o.localAddresses
        if (supplier != null) {
            locals = supplier.get()
        } else {
            val now = System.nanoTime()
            if (now - cachedLocalAt > 30_000_000_000L || cachedLocal.isEmpty()) {
                val list = ArrayList<InetAddress>()
                try {
                    val nis = NetworkInterface.getNetworkInterfaces()
                    while (nis != null && nis.hasMoreElements()) {
                        val addrs = nis.nextElement().inetAddresses
                        while (addrs.hasMoreElements()) list.add(addrs.nextElement())
                    }
                } catch (e: SocketException) {
                    // 取れなければ、空 (ほかの検査は有効)
                }
                cachedLocal = list
                cachedLocalAt = now
            }
            locals = cachedLocal
        }
        for (l in locals) if (l == a) return true
        return false
    }

    // ---- リクエスト ----

    /**
     * 1 回のリクエスト (転送は追わない)。
     *
     * @param method "HEAD" か "GET"
     * @param range  null でなければ Range ヘッダーの値 (例 "bytes=0-1023")
     */
    @Throws(HttpFailure::class)
    fun request(method: String, uri: URI, range: String?): Response {
        if (method != "HEAD" && method != "GET") throw HttpFailure(Kind.BAD_REQUEST)
        val deadline = System.nanoTime() + o.totalTimeoutMillis * 1_000_000L
        val scheme = if (uri.scheme == null) "" else uri.scheme.lowercase()
        val tls = scheme == "https"
        if (!tls && scheme != "http") throw HttpFailure(Kind.BAD_REQUEST)
        val host: String? = uri.host
        if (host == null || host.isEmpty()) throw HttpFailure(Kind.BAD_REQUEST)
        val port = if (uri.port >= 0) uri.port else if (tls) 443 else 80
        if (port < 1 || port > 65535) throw HttpFailure(Kind.BAD_REQUEST)

        val literal = host.startsWith("[")
        val asciiHost: String
        val addresses: Array<InetAddress>?
        try {
            if (literal) {
                asciiHost = host
                addresses = arrayOf(InetAddress.getByName(host.substring(1, host.length - 1)))
            } else {
                asciiHost = IDN.toASCII(host.lowercase())
                addresses = resolver.resolve(asciiHost)
            }
        } catch (e: DnsBusy) {
            throw HttpFailure(Kind.DNS_BUSY)
        } catch (e: DnsTimeout) {
            throw HttpFailure(Kind.DNS_TIMEOUT)
        } catch (e: UnknownHostException) {
            throw HttpFailure(Kind.DNS)
        } catch (e: IllegalArgumentException) {
            throw HttpFailure(Kind.DNS)
        }
        if (addresses == null || addresses.isEmpty()) throw HttpFailure(Kind.DNS)
        // 1 つでも公開でないアドレスがあれば、そのホストにはつながない。
        // 公開のアドレスでも、設定で除いたもの (サーバー自身の公開アドレスなど) と、このサーバー自身のアドレスは除く
        // (自分の公開アドレスへの接続は、同じ機械の中を通って、外から守られているはずのサービスに届いてしまうため)
        if (!o.allowNonPublicForTests) {
            for (a in addresses) {
                if (!AddressPolicy.isPublic(a) || isLocalAddress(a) || (o.denyList?.matches(a) == true)) {
                    throw HttpFailure(Kind.UNSAFE_ADDRESS)
                }
            }
        }

        var socket: Socket? = null
        var lastConnectFailure = HttpFailure(Kind.CONNECT_FAILED)
        var i = 0
        while (i < Math.min(addresses.size, MAX_ADDRESSES) && socket == null) {
            val remaining = remainingMillis(deadline)
            if (remaining <= 0) throw HttpFailure(Kind.CONNECT_TIMEOUT)
            try {
                socket = connector.connect(addresses[i], port, Math.min(o.connectTimeoutMillis, remaining))
            } catch (e: SocketTimeoutException) {
                lastConnectFailure = HttpFailure(Kind.CONNECT_TIMEOUT)
            } catch (e: ConnectException) {
                lastConnectFailure = HttpFailure(if (e.message != null && e.message!!.lowercase().contains("timed out"))
                    Kind.CONNECT_TIMEOUT else Kind.CONNECT_REFUSED)
            } catch (e: NoRouteToHostException) {
                lastConnectFailure = HttpFailure(Kind.CONNECT_FAILED)
            } catch (e: IOException) {
                lastConnectFailure = HttpFailure(Kind.CONNECT_FAILED)
            } catch (e: RuntimeException) {
                lastConnectFailure = HttpFailure(Kind.CONNECT_FAILED)
            }
            i++
        }
        if (socket == null) throw lastConnectFailure

        // つないだ先が自分自身のアドレスなら、閉じる (名前の引き方に関係なく、機械の中へ向かう接続を防ぐ)
        if (!o.allowNonPublicForTests && !o.allowSelfConnectForTests && socket.inetAddress != null && socket.localAddress != null
            && socket.inetAddress == socket.localAddress
        ) {
            closeQuietly(socket)
            throw HttpFailure(Kind.UNSAFE_ADDRESS)
        }

        val plain: Socket = socket
        openSockets.add(plain)
        val timedOut = AtomicBoolean()
        val watchdog = watchdogPool.schedule(Runnable {
            timedOut.set(true)
            closeQuietly(plain)
        }, Math.max(1, remainingMillis(deadline)).toLong(), TimeUnit.MILLISECONDS)
        try {
            socket.use { s ->
                var io: Socket = s
                if (tls) io = handshake(s, asciiHost, port, deadline, literal)
                val target = pathAndQuery(uri)
                val req = StringBuilder(256)
                req.append(method).append(' ').append(target).append(" HTTP/1.1\r\n")
                req.append("Host: ").append(hostHeader(asciiHost, port, tls)).append("\r\n")
                req.append("User-Agent: ").append(o.userAgent).append("\r\n")
                req.append("Accept: */*\r\n")
                if (range != null) req.append("Range: ").append(range).append("\r\n")
                req.append("Connection: close\r\n\r\n")
                setTimeout(io, deadline)
                val out = io.getOutputStream()
                out.write(req.toString().toByteArray(StandardCharsets.ISO_8859_1))
                out.flush()
                return readResponse(io, method == "GET", deadline)
            }
        } catch (f: HttpFailure) {
            throw if (timedOut.get()) HttpFailure(Kind.TIMEOUT) else f
        } catch (e: IOException) {
            throw HttpFailure(if (timedOut.get()) Kind.TIMEOUT else classify(e))
        } catch (e: RuntimeException) {
            throw HttpFailure(if (timedOut.get()) Kind.TIMEOUT else classify(e))
        } finally {
            watchdog.cancel(false)
            openSockets.remove(plain)
        }
    }

    @Throws(IOException::class)
    private fun handshake(plain: Socket, host: String, port: Int, deadline: Long, literal: Boolean): Socket {
        val ctx: SSLContext = try {
            o.sslContext ?: SSLContext.getDefault()
        } catch (e: NoSuchAlgorithmException) {
            throw HttpFailure(Kind.TLS)
        }
        val ssl = ctx.socketFactory.createSocket(plain, host, port, true) as SSLSocket
        val p = ssl.sslParameters
        // これを設定しないと、証明書のホスト名が検証されない
        p.setEndpointIdentificationAlgorithm("HTTPS")
        p.setProtocols(arrayOf("TLSv1.3", "TLSv1.2"))
        if (!literal) p.setServerNames(listOf(SNIHostName(host)))
        ssl.sslParameters = p
        setTimeout(ssl, deadline)
        ssl.startHandshake()
        return ssl
    }

    // ---- 応答 ----

    @Throws(IOException::class)
    private fun readResponse(s: Socket, readBody: Boolean, deadline: Long): Response {
        val input = s.getInputStream()
        val r = Reader(s, input, deadline)
        var total = 0
        for (informational in 0 until 5) {
            val line = r.readLine(o.maxLineBytes)
            total += line.length + 2
            if (total > o.maxHeaderBytes) throw HttpFailure(Kind.BAD_RESPONSE)
            val m = STATUS.matcher(line)
            if (!m.matches()) throw HttpFailure(Kind.BAD_RESPONSE)
            val status = Integer.parseInt(m.group(1))
            if (status < 100 || status > 599) throw HttpFailure(Kind.BAD_RESPONSE)
            var location: String? = null
            var contentType: String? = null
            var retryAfter: String? = null
            var lines = 0
            while (true) {
                val h = r.readLine(o.maxLineBytes)
                total += h.length + 2
                if (total > o.maxHeaderBytes || ++lines > o.maxHeaderLines) throw HttpFailure(Kind.BAD_RESPONSE)
                if (h.isEmpty()) break
                val colon = h.indexOf(':')
                if (colon <= 0 || h[0] == ' ' || h[0] == '\t') throw HttpFailure(Kind.BAD_RESPONSE)
                val name = h.substring(0, colon)
                if (!HEADER_NAME.matcher(name).matches()) throw HttpFailure(Kind.BAD_RESPONSE)
                val value = h.substring(colon + 1).trim { it <= ' ' }
                when (name.lowercase()) {
                    "location" -> {
                        if (location == null) location = value
                    }
                    "content-type" -> {
                        if (contentType == null) contentType = value
                    }
                    "retry-after" -> {
                        if (retryAfter == null) retryAfter = value
                    }
                    else -> {
                    }
                }
            }
            if (status >= 200 || status == 101) {
                if (status == 101) throw HttpFailure(Kind.BAD_RESPONSE)
                val body = if (readBody && status != 204 && status != 304) r.discard(o.maxBodyBytes) else 0L
                return Response(status, limit(location, UrlFilter.MAX_URL_LENGTH + 100), mediaType(contentType),
                    limit(retryAfter, 64), body)
            }
            // 1xx (100 Continue など) は読み飛ばして、続く本当の応答を読む
        }
        throw HttpFailure(Kind.BAD_RESPONSE)
    }

    /** 期限つきで 1 バイトずつ読む入力 (小出しに送られても、全体の期限で切る) */
    private class Reader(private val socket: Socket, private val input: InputStream, private val deadline: Long) {
        private val buf = ByteArray(4096)
        private var pos = 0
        private var len = 0

        @Throws(IOException::class)
        private fun fill(): Boolean {
            if (pos < len) return true
            setTimeout(socket, deadline)
            val n = input.read(buf)
            if (n < 0) return false
            pos = 0
            len = n
            return n > 0 || fill()
        }

        @Throws(IOException::class)
        fun readLine(maxBytes: Int): String {
            val sb = StringBuilder()
            while (true) {
                if (!fill()) throw HttpFailure(Kind.BAD_RESPONSE)
                val c = buf[pos++].toInt() and 0xFF
                if (c == '\n'.code) {
                    val n = sb.length
                    if (n > 0 && sb[n - 1] == '\r') sb.setLength(n - 1)
                    return sb.toString()
                }
                if (c == 0 || (c < 0x20 && c != '\t'.code && c != '\r'.code)) throw HttpFailure(Kind.BAD_RESPONSE)
                if (sb.length >= maxBytes) throw HttpFailure(Kind.BAD_RESPONSE)
                sb.append(c.toChar())
            }
        }

        /** 本文を最大 max バイトまで読んで捨てる (圧縮されていても展開しない)。読めたバイト数 */
        fun discard(max: Int): Long {
            var total = 0L
            try {
                while (total < max) {
                    if (!fill()) break
                    val n = Math.min(len - pos, (max - total).toInt())
                    pos += n
                    total += n
                }
            } catch (e: IOException) {
                // ステータスはもう分かっている。本文が途中で切れても、結果には関係ない
            }
            return total
        }
    }

    companion object {
        private val STATUS: Pattern = Pattern.compile("^HTTP/1\\.[01] (\\d{3})(?: .*)?\$")
        private val HEADER_NAME: Pattern = Pattern.compile("^[!#\$%&'*+.^_`|~0-9A-Za-z-]+\$")
        private const val MAX_ADDRESSES = 4

        @Throws(IOException::class)
        private fun plainConnect(address: InetAddress, port: Int, timeoutMillis: Int): Socket {
            val s = Socket()
            try {
                s.tcpNoDelay = true
                s.connect(InetSocketAddress(address, port), timeoutMillis)
                return s
            } catch (e: IOException) {
                closeQuietly(s)
                throw e
            } catch (e: RuntimeException) {
                closeQuietly(s)
                throw e
            }
        }

        fun systemResolver(timeoutMillis: Int): Resolver {
            return SystemResolver(timeoutMillis, Lookup(InetAddress::getAllByName), 4)
        }

        @JvmStatic
        fun systemResolver(timeoutMillis: Int, lookup: Lookup, slots: Int): Resolver {
            return SystemResolver(timeoutMillis, lookup, slots)
        }

        private fun closeQuietly(s: Socket) {
            try {
                s.close()
            } catch (ignored: IOException) {
                // 閉じられなくてもよい
            }
        }

        private fun hostHeader(host: String, port: Int, tls: Boolean): String {
            val def = if (tls) 443 else 80
            return if (port == def) host else "$host:$port"
        }

        private fun pathAndQuery(uri: URI): String {
            var path: String? = uri.rawPath
            if (path == null || path.isEmpty()) path = "/"
            val q: String? = uri.rawQuery
            val target = if (q == null) path else "$path?$q"
            for (c in target) {
                if (c <= ' ' || c.code >= 0x7F) throw IllegalArgumentException("bad request target")
            }
            return target
        }

        private fun remainingMillis(deadline: Long): Int {
            val ms = (deadline - System.nanoTime()) / 1_000_000L
            return if (ms > Int.MAX_VALUE) Int.MAX_VALUE else ms.toInt()
        }

        @Throws(IOException::class)
        private fun setTimeout(s: Socket, deadline: Long) {
            val remaining = remainingMillis(deadline)
            if (remaining <= 0) throw HttpFailure(Kind.TIMEOUT)
            s.soTimeout = remaining
        }

        private fun limit(s: String?, max: Int): String? {
            return if (s == null || s.length > max) null else s
        }

        /** Content-Type の "type/subtype" だけ (小文字。パラメーターは捨てる) */
        fun mediaType(contentType: String?): String {
            if (contentType == null) return ""
            val semi = contentType.indexOf(';')
            val t = (if (semi >= 0) contentType.substring(0, semi) else contentType).trim { it <= ' ' }.lowercase()
            return if (t.length > 100) "" else t
        }

        // ---- 失敗の分類 ----

        fun classify(e: Throwable): Kind {
            if (e is HttpFailure) return e.kind
            if (e is SocketTimeoutException) return Kind.TIMEOUT
            if (e is SSLException) {
                var t: Throwable? = e
                while (t != null) {
                    if (t is CertificateException) return Kind.TLS
                    t = t.cause
                }
                val m = if (e.message == null) "" else e.message!!.lowercase()
                if (m.contains("terminated") || m.contains("reset") || m.contains("eof") || m.contains("closed") || m.contains("broken pipe")) {
                    return Kind.RESET
                }
                return Kind.TLS
            }
            if (e is SocketException) return Kind.RESET
            if (e is IOException) return Kind.RESET
            return Kind.BAD_REQUEST
        }

        /** テスト用: 複数のアドレスを返す名前の引き方を作る */
        @JvmStatic
        fun fixedResolver(table: Map<String, Array<InetAddress>>): Resolver {
            return Resolver { host ->
                val a = table[host] ?: throw UnknownHostException(host)
                ArrayList(listOf(*a)).toTypedArray()
            }
        }
    }
}
