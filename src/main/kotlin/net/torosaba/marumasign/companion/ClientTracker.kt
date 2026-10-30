package net.torosaba.marumasign.companion

import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.scheduler.BukkitTask
import java.nio.charset.StandardCharsets
import java.sql.Connection
import java.sql.SQLException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.logging.Logger
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * いま遊んでいるプレイヤーの IP を、データベースの client の表に記録する (hosting.storage: database で client-check が有効なとき)。
 * assets サーバーは、ここにある IP (オンラインか、ログアウトしてから grace-minutes 以内) からの読み込みだけ許す
 * (MarumaSign の Mod は、プレイヤーの PC から看板の画像を読むため)。
 *
 * IP はそのまま保存しない。assets サーバーと同じ秘密の文字列で HMAC-SHA256 にしてから保存する ([ipHash])。IP はログにも出さない。
 * 書き込みはこのクラスの 1 本のスレッドで行う (ホストの読み込みで待たされないよう、ホストとは別の接続とスレッド)。
 */
internal class ClientTracker(
    private val plugin: JavaPlugin,
    private val logger: Logger,
    private val db: Db,
    prefix: String,
    secret: String,
    private val clock: () -> Long = System::currentTimeMillis,
) : Listener {

    private val key = secret.toByteArray(StandardCharsets.UTF_8)
    private val table = ClientTable(db, prefix)
    private var executor: ExecutorService? = null
    private var heartbeat: BukkitTask? = null
    private var beats = 0

    @Volatile
    private var failing = false

    /** 最後に書けた時刻 (0 ならまだ) */
    @Volatile
    private var lastWriteAt = 0L

    /** メインスレッドで呼ぶ */
    fun start() {
        executor = Executors.newSingleThreadExecutor { r ->
            Thread(r, "MarumaSignCompanion-clients").apply { isDaemon = true }
        }
        Bukkit.getPluginManager().registerEvents(this, plugin)
        // /reload のあとなど: 前の記録をすべてオフラインにしてから、いまのプレイヤーを入れる
        val now = clock()
        val online = onlineHashes(null)
        submit { c ->
            table.resetOnline(c)
            table.heartbeat(c, online, now)
            table.cleanup(c, now)
        }
        heartbeat = Bukkit.getScheduler().runTaskTimer(plugin, Runnable { beat() }, HEARTBEAT_TICKS, HEARTBEAT_TICKS)
    }

    /** メインスレッドで呼ぶ。すべてオフラインにして止める (待つのは数秒まで) */
    fun stop() {
        heartbeat?.cancel()
        heartbeat = null
        val e = executor ?: return
        executor = null
        val now = clock()
        try {
            e.execute { write { c -> table.allOffline(c, now) } }
        } catch (ex: RejectedExecutionException) {
            // 止まっている途中
        }
        e.shutdown()
        try {
            e.awaitTermination(5, TimeUnit.SECONDS)
        } catch (ex: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        e.shutdownNow()
        db.close()
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onJoin(event: PlayerJoinEvent) {
        val hash = hashOf(event.player) ?: return
        val now = clock()
        submit { c -> table.seen(c, hash, now, online = true) }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onQuit(event: PlayerQuitEvent) {
        val player = event.player
        val hash = hashOf(player) ?: return
        // 同じ IP のプレイヤーがまだいれば、オンラインのまま (同じ家から 2 人など)
        val others = onlineHashes(player).any { it.contentEquals(hash) }
        val now = clock()
        submit { c -> table.seen(c, hash, now, online = others) }
    }

    private fun beat() {
        val now = clock()
        val online = onlineHashes(null)
        val cleanup = ++beats % CLEANUP_EVERY_BEATS == 0
        submit { c ->
            table.heartbeat(c, online, now)
            if (cleanup) table.cleanup(c, now)
        }
    }

    private fun hashOf(p: Player): ByteArray? {
        val address = p.address?.address?.address ?: return null
        return ipHash(key, address)
    }

    private fun onlineHashes(except: Player?): List<ByteArray> =
        Bukkit.getOnlinePlayers().filter { it !== except }.mapNotNull { hashOf(it) }

    private fun submit(task: (Connection) -> Unit) {
        val e = executor ?: return
        try {
            e.execute { write(task) }
        } catch (ex: RejectedExecutionException) {
            // 止まっている途中
        }
    }

    private fun write(task: (Connection) -> Unit) {
        try {
            db.use(task)
            lastWriteAt = clock()
            if (failing) logger.info("Hosting: the client list in the database is updated again")
            failing = false
        } catch (e: SQLException) {
            // 1 分ごとの記録で追いつくので、続けて失敗しているあいだは 1 回だけ知らせる
            if (!failing) logger.warning("Hosting: could not update the client list in the database: " + Db.describe(e) + "; will retry")
            failing = true
        }
    }

    /** /marumasign hosting status に出す行 */
    fun describe(): String {
        val t = lastWriteAt
        return when {
            failing -> "接続しているクライアントの記録: 失敗中 (" + (db.lastError ?: "") + ")"
            t > 0 -> "接続しているクライアントの記録: 有効 (最後の更新 " + (clock() - t) / 1000 + " 秒前)"
            else -> "接続しているクライアントの記録: 有効 (まだ書いていない)"
        }
    }

    /** client の表の読み書き (assets サーバーが読むので、形を変えない) */
    internal class ClientTable(private val db: Db, prefix: String) {
        private val t = "${prefix}client"

        fun seen(c: Connection, hash: ByteArray, now: Long, online: Boolean) {
            db.update(c, "INSERT INTO $t (ip_hash, last_seen, online) VALUES (?, ?, ?) ON DUPLICATE KEY UPDATE last_seen = VALUES(last_seen), online = VALUES(online)",
                hash, now, if (online) 1 else 0)
        }

        /** 1 分ごと: いまのプレイヤーを記録し、しばらく記録されていないオンラインの行 (ログアウトを取りこぼしたもの) をオフラインにする */
        fun heartbeat(c: Connection, online: List<ByteArray>, now: Long) {
            db.batch(c, "INSERT INTO $t (ip_hash, last_seen, online) VALUES (?, ?, 1) ON DUPLICATE KEY UPDATE last_seen = VALUES(last_seen), online = 1",
                online.map { arrayOf<Any?>(it, now) })
            db.update(c, "UPDATE $t SET online = 0 WHERE online = 1 AND last_seen < ?", now - STALE_MILLIS)
        }

        /** 起動したとき: 前の記録をすべてオフラインに (最後に見た時刻は残すので、猶予はそこから) */
        fun resetOnline(c: Connection) {
            db.update(c, "UPDATE $t SET online = 0 WHERE online = 1")
        }

        /** 止めるとき: すべてオフラインに。いたプレイヤーは、いまログアウトしたことにする */
        fun allOffline(c: Connection, now: Long) {
            db.update(c, "UPDATE $t SET online = 0, last_seen = ? WHERE online = 1", now)
        }

        /** 1 日より前に最後に見た行を消す */
        fun cleanup(c: Connection, now: Long) {
            db.update(c, "DELETE FROM $t WHERE last_seen < ?", now - DAY)
        }
    }

    companion object {
        private const val HEARTBEAT_TICKS = 20L * 60
        private const val CLEANUP_EVERY_BEATS = 60
        private const val STALE_MILLIS = 3 * 60_000L
        private const val DAY = 24 * 3_600_000L

        /**
         * IP の記録の鍵: HMAC-SHA256 (鍵は秘密の文字列の UTF-8、メッセージはアドレスのバイト列)。
         * IPv4 は 4 バイト、IPv6 は 16 バイト。IPv4 射影の IPv6 (::ffff:a.b.c.d) は IPv4 の 4 バイトにしてから。
         */
        fun ipHash(secret: ByteArray, address: ByteArray): ByteArray {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(secret, "HmacSHA256"))
            return mac.doFinal(normalize(address))
        }

        /** IPv4 射影の IPv6 (::ffff:a.b.c.d) を IPv4 の 4 バイトにする。ほかはそのまま */
        fun normalize(address: ByteArray): ByteArray {
            if (address.size != 16) return address
            for (i in 0 until 10) if (address[i].toInt() != 0) return address
            if (address[10].toInt() != -1 || address[11].toInt() != -1) return address
            return address.copyOfRange(12, 16)
        }
    }
}
