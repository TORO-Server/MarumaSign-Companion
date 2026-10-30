package net.torosaba.marumasign.companion

import java.sql.Connection
import java.sql.Driver
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.SQLNonTransientConnectionException
import java.sql.SQLRecoverableException
import java.sql.SQLTransientConnectionException
import java.util.Properties
import java.util.logging.Logger

/**
 * データベース (TiDB / MySQL) への 1 本の接続。JDBC だけで、ORM は使わない。
 * 切れていたら、つなぎ直して 1 回だけやり直す (なので、渡す処理は何度実行してもよい形にする)。
 * URL のデータベースがなければ、データベースを付けずにつないで CREATE DATABASE IF NOT EXISTS で作る。
 * ドライバー (MySQL Connector/J) は JAR に入れず、plugin.yml の libraries で Paper に読み込ませる。
 * メインスレッドからは呼ばない。
 */
internal class Db(
    private val url: String,
    private val user: String,
    private val password: String,
    private val logger: Logger,
    /** つないだ直後に毎回 (表を作るなど) */
    private val init: (Db, Connection) -> Unit = { _, _ -> },
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private var conn: Connection? = null
    private var lastUsedAt = 0L
    private var driver: Driver? = null

    /** 最後にうまくいった時刻 (0 ならまだ) */
    @Volatile
    var lastOkAt = 0L
        private set

    /** 最後の失敗 (うまくいったら null) */
    @Volatile
    var lastError: String? = null
        private set

    /** テスト用: 実行する SQL を順に受け取る */
    @Volatile
    internal var trace: ((String) -> Unit)? = null

    @Synchronized
    @Throws(SQLException::class)
    fun <T> use(block: (Connection) -> T): T {
        var retried = false
        while (true) {
            try {
                val r = block(connection())
                lastOkAt = clock()
                lastUsedAt = lastOkAt
                lastError = null
                return r
            } catch (e: SQLException) {
                lastError = describe(e)
                if (retried || !isConnectionError(e)) throw e
                retried = true
                closeQuietly()
            }
        }
    }

    @Synchronized
    fun close() = closeQuietly()

    // ---- 文の実行 ----

    /** 1 文を実行して、変わった行の数 */
    fun update(c: Connection, sql: String, vararg args: Any?): Int {
        trace?.invoke(sql)
        return c.prepareStatement(sql).use { ps ->
            bind(ps, args)
            ps.executeUpdate()
        }
    }

    fun <T> query(c: Connection, sql: String, vararg args: Any?, read: (ResultSet) -> T): T {
        trace?.invoke(sql)
        return c.prepareStatement(sql).use { ps ->
            bind(ps, args)
            ps.executeQuery().use(read)
        }
    }

    /** 同じ文を行ごとに。BATCH 行ずつ送る (自動コミットなので、行ごとの小さな書き込みになる) */
    fun batch(c: Connection, sql: String, rows: List<Array<out Any?>>) {
        if (rows.isEmpty()) return
        c.prepareStatement(sql).use { ps ->
            for (part in rows.chunked(BATCH)) {
                trace?.invoke(sql)
                for (row in part) {
                    bind(ps, row)
                    ps.addBatch()
                }
                ps.executeBatch()
            }
        }
    }

    private fun bind(ps: PreparedStatement, args: Array<out Any?>) {
        for ((i, a) in args.withIndex()) {
            when (a) {
                is String -> ps.setString(i + 1, a)
                is Long -> ps.setLong(i + 1, a)
                is Int -> ps.setInt(i + 1, a)
                is ByteArray -> ps.setBytes(i + 1, a)
                else -> ps.setObject(i + 1, a)
            }
        }
    }

    // ---- 接続 ----

    private fun connection(): Connection {
        val c = conn
        if (c != null) {
            // しばらく使っていなければ、生きているか確かめる (TiDB や途中の機械に切られていることがある)
            if (clock() - lastUsedAt < IDLE_CHECK_MILLIS || valid(c)) return c
            closeQuietly()
        }
        val n = connect()
        conn = n
        return n
    }

    private fun valid(c: Connection): Boolean = try {
        c.isValid(5)
    } catch (e: SQLException) {
        false
    }

    private fun connect(): Connection {
        val c = try {
            open(url)
        } catch (e: SQLException) {
            if (e.errorCode != ER_BAD_DB_ERROR || !createDatabase()) throw e
            open(url)
        }
        try {
            c.autoCommit = true
            init(this, c)
        } catch (e: SQLException) {
            c.close()
            throw e
        }
        return c
    }

    private fun createDatabase(): Boolean {
        val (server, name) = splitDatabase(url) ?: return false
        open(server).use { c -> update(c, "CREATE DATABASE IF NOT EXISTS `$name`") }
        logger.info("Database: created the database $name")
        return true
    }

    private fun open(u: String): Connection {
        val props = Properties()
        props.setProperty("user", user)
        props.setProperty("password", password)
        if (!u.startsWith("jdbc:mysql:")) return DriverManager.getConnection(u, props)
        if (!u.contains("connectTimeout=")) props.setProperty("connectTimeout", "10000")
        if (!u.contains("socketTimeout=")) props.setProperty("socketTimeout", "120000")
        return mysql().connect(u, props) ?: throw SQLException("The MySQL driver did not accept the URL")
    }

    // Paper がプラグインの libraries から読み込んだドライバーを、DriverManager を通さずに使う
    private fun mysql(): Driver {
        driver?.let { return it }
        val d = try {
            Class.forName("com.mysql.cj.jdbc.Driver", true, Db::class.java.classLoader).getDeclaredConstructor().newInstance() as Driver
        } catch (e: ReflectiveOperationException) {
            throw SQLException("MySQL Connector/J is not available (plugin.yml libraries)", e)
        } catch (e: LinkageError) {
            throw SQLException("MySQL Connector/J is not available (plugin.yml libraries)", e)
        }
        driver = d
        return d
    }

    private fun closeQuietly() {
        val c = conn ?: return
        conn = null
        try {
            c.close()
        } catch (e: SQLException) {
            // もう切れている
        }
    }

    companion object {
        private const val BATCH = 200
        private const val IDLE_CHECK_MILLIS = 30_000L
        // MySQL / TiDB: Unknown database
        private const val ER_BAD_DB_ERROR = 1049
        private val WITH_DATABASE = Regex("(jdbc:(?:mysql|mariadb)://[^/?#]+)/([A-Za-z0-9_$]{1,64})(\\?.*)?")

        /** URL を、データベースを付けない URL とデータベースの名前に分ける。形が違えば null */
        internal fun splitDatabase(url: String): Pair<String, String>? {
            val m = WITH_DATABASE.matchEntire(url) ?: return null
            return (m.groupValues[1] + "/" + m.groupValues[3]) to m.groupValues[2]
        }

        /** 表示用の URL (ユーザー情報と、パスワードが入りうる ? より後ろを除く) */
        fun displayUrl(url: String): String =
            SafeText.clean(url.substringBefore('?').replace(Regex("//[^/@]*@"), "//"), 120)

        fun isConnectionError(e: SQLException): Boolean =
            e is SQLRecoverableException || e is SQLNonTransientConnectionException || e is SQLTransientConnectionException ||
                e.sqlState?.startsWith("08") == true

        /** ログ・コマンドに出す失敗の説明 */
        fun describe(e: SQLException): String =
            e.javaClass.simpleName + " (SQLState " + e.sqlState + ", " + e.errorCode + "): " + SafeText.clean(e.message ?: "", 160)
    }
}
