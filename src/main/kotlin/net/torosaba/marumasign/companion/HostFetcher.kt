package net.torosaba.marumasign.companion

import java.io.BufferedOutputStream
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.security.DigestOutputStream
import java.security.MessageDigest

/**
 * ホストするために、看板の URL の中身を読み込む。
 * つなぎ方はリンクの検査と同じ [SafeHttp] (検証したアドレスにだけつなぐ・内部へはつながない・期限と上限)。
 * 転送は自前で追い、ホップごとに URL の確認をやり直す。https から http への転送の先は読まない。
 */
class HostFetcher internal constructor(
    private val http: SafeHttp,
    private val parse: (String) -> URI?,
    private val throttle: HostThrottle,
    private val maxRedirects: Int,
    private val allowedExtensions: Set<String>,
) {
    sealed interface Result

    /** 読み込めた。file は一時ファイル (呼ぶ側が動かすか消す) */
    class Fetched(val file: Path, val ext: String, val size: Long, val id: String) : Result

    class Rejected(val reason: HostingPolicy.Reject) : Result

    /** ホストごとの上限で、いまは読めない。あとでやり直す (失敗に数えない) */
    data object Deferred : Result

    fun fetch(url: String, tmpDir: Path, maxBytes: Long, timeoutMillis: Int): Result {
        val start = parse(url) ?: return Rejected(HostingPolicy.Reject.UNSAFE)
        val urlExt = MediaKind.extension(start)
        HostingPolicy.hostableExtension(urlExt, allowedExtensions)?.let { return Rejected(it) }
        val visited = HashSet<String>()
        var cur = start
        for (hop in 0..maxRedirects) {
            if (!visited.add(cur.toString())) return Rejected(HostingPolicy.Reject.HTTP)
            val now = System.currentTimeMillis()
            val at = throttle.reserve(cur.host, now)
            if (at < 0 || at - now > MAX_WAIT_MILLIS) return Deferred
            if (at > now) Thread.sleep(at - now)

            Files.createDirectories(tmpDir)
            val tmp = Files.createTempFile(tmpDir, "dl-", ".part")
            var keep = false
            try {
                val digest = MessageDigest.getInstance("SHA-256")
                var reject: HostingPolicy.Reject? = null
                var ext = ""
                val resp = DigestOutputStream(BufferedOutputStream(Files.newOutputStream(tmp)), digest).use { out ->
                    val download = SafeHttp.Download(maxBytes, timeoutMillis, out) { head ->
                        val e = HostingPolicy.extensionFor(urlExt.ifEmpty { MediaKind.extension(cur) }, head.contentType) ?: ""
                        // ライブ配信と HTML を先に見分ける (拡張子が分からなくても、理由を正しく残す)
                        reject = HostingPolicy.checkResponse(urlExt, head.contentType, head.icy) ?: when {
                            head.encoded -> HostingPolicy.Reject.TYPE
                            head.contentLength > maxBytes -> HostingPolicy.Reject.TOO_LARGE
                            e.isEmpty() -> HostingPolicy.Reject.TYPE
                            else -> HostingPolicy.hostableExtension(e, allowedExtensions)
                        }
                        ext = e
                        reject == null
                    }
                    try {
                        http.request("GET", cur, null, download)
                    } catch (f: SafeHttp.HttpFailure) {
                        return Rejected(when (f.kind) {
                            SafeHttp.Kind.TIMEOUT -> HostingPolicy.Reject.TIMEOUT
                            SafeHttp.Kind.UNSAFE_ADDRESS, SafeHttp.Kind.BAD_REQUEST -> HostingPolicy.Reject.UNSAFE
                            SafeHttp.Kind.DNS_BUSY -> return Deferred
                            else -> HostingPolicy.Reject.FAILED
                        })
                    }
                }
                when (resp.status) {
                    200 -> {
                        reject?.let { return Rejected(it) }
                        if (resp.truncated) return Rejected(if (resp.bodyBytes >= maxBytes) HostingPolicy.Reject.TOO_LARGE else HostingPolicy.Reject.FAILED)
                        if (resp.bodyBytes == 0L) return Rejected(HostingPolicy.Reject.HTTP)
                        digest.update(".$ext".toByteArray(Charsets.US_ASCII))
                        keep = true
                        return Fetched(tmp, ext, resp.bodyBytes, idOf(digest.digest()))
                    }
                    301, 302, 303, 307, 308 -> {
                        val location = resp.location ?: return Rejected(HostingPolicy.Reject.HTTP)
                        val next = try {
                            cur.resolve(location)
                        } catch (e: IllegalArgumentException) {
                            return Rejected(HostingPolicy.Reject.UNSAFE)
                        }
                        val checked = parse(next.toString()) ?: return Rejected(HostingPolicy.Reject.UNSAFE)
                        if (cur.scheme.equals("https", true) && checked.scheme.equals("http", true)) return Rejected(HostingPolicy.Reject.UNSAFE)
                        cur = checked
                    }
                    else -> return Rejected(HostingPolicy.Reject.HTTP)
                }
            } finally {
                if (!keep) Files.deleteIfExists(tmp)
            }
        }
        return Rejected(HostingPolicy.Reject.HTTP)
    }

    companion object {
        private const val MAX_WAIT_MILLIS = 15_000L
        private const val BASE32 = "abcdefghijklmnopqrstuvwxyz234567"

        /** ハッシュの先頭 60 ビットを 12 文字の base32 にする */
        internal fun idOf(hash: ByteArray): String {
            var bits = 0L
            for (i in 0 until 8) bits = (bits shl 8) or (hash[i].toLong() and 0xFF)
            val sb = StringBuilder(12)
            for (i in 0 until 12) sb.append(BASE32[((bits ushr (59 - 5 * i)) and 31).toInt()])
            return sb.toString()
        }
    }
}
