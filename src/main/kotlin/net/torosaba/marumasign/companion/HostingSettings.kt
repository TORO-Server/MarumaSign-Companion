package net.torosaba.marumasign.companion

import org.bukkit.configuration.ConfigurationSection
import java.net.URI
import java.util.Locale

/** config.yml の hosting (看板の URL の中身をサーバーでホストして、看板の URL を書き換える) */
class HostingSettings {
    var enabled = false
    /** 看板に書く URL の頭 (例 https://signs.example.net)。末尾の / は除いてある。空ならホストしない */
    var publicBaseUrl = ""
    /** ファイルを置くフォルダ (空ならプラグインのフォルダの hosted)。外の Web サーバーに配らせるときはそのフォルダにする */
    var directory = ""
    var bind = "0.0.0.0"
    /** 内蔵の Web サーバーのポート。0 なら内蔵の Web サーバーを使わない (外の Web サーバーが directory を配る) */
    var port = 8110
    var verifyAfterHours = 24
    var unhostAfterDays = 14
    var maxFileMb = 32
    var maxTotalMb = 4096
    /** ホストするファイルの数の上限 (全体) */
    var maxTotalFiles = 20_000
    /** 1 人のプレイヤーの分のファイルの数と容量 (MB) の上限。0 なら上限なし。作者が分からないファイルは、だれの分にも数えない */
    var perPlayerFiles = 100
    var perPlayerMb = 256
    var downloadTimeoutSeconds = 120
    /** 同時に読み込む数 */
    var downloadConcurrency = 2
    var perHourLimit = 30
    var excludeHosts: List<String> = listOf()
    /** 書いてあれば、そのホスト (と下のサブドメイン) の URL だけホストする */
    var allowHosts: List<String> = listOf()
    /** allow-hosts に何か書いてあるか (読める行がひとつもなくても、制限なしには戻さない) */
    var allowHostsSet = false
    /** true なら、管理者が承認した URL だけホストする */
    var requireApproval = false
    /** files: フォルダと hosting.json。database: データベース (TiDB / MySQL) に入れ、内蔵の Web サーバーは使わない */
    var storage = FILES
    var dbUrl = "jdbc:mysql://tidb:4000/marumasign"
    /** 既定はなし。storage: database では、設定されていなければホストを始めない ([databaseCredentialProblem]) */
    var dbUser = ""
    var dbPassword = ""
    /** 表の名前の頭 (英数字と _ だけ) */
    var tablePrefix = "msc_"
    /** いま遊んでいるプレイヤーの IP (の HMAC) をデータベースに記録するか (storage: database のときだけ) */
    var clientCheckEnabled = true
    /** assets サーバーと同じ秘密の文字列。空なら記録しない */
    var clientSecret = ""
    /** ログアウトしてからも読めるようにしておく時間 (分)。assets サーバーが使う */
    var clientGraceMinutes = 10

    val maxFileBytes: Long get() = maxFileMb * 1024L * 1024L
    val maxTotalBytes: Long get() = maxTotalMb * 1024L * 1024L
    val perPlayerBytes: Long get() = perPlayerMb * 1024L * 1024L
    val usesDatabase: Boolean get() = storage == DATABASE

    /** そのホストの URL をホストしてよいか (exclude-hosts と allow-hosts。そのホストと、その下のサブドメイン)。転送の先にも使う */
    fun hostPermitted(host: String?): Boolean {
        val h = host?.trim()?.lowercase(Locale.ROOT)?.trimEnd('.')
        if (h.isNullOrEmpty()) return false
        if (excludeHosts.any { h == it || h.endsWith(".$it") }) return false
        return !allowHostsSet || allowHosts.any { h == it || h.endsWith(".$it") }
    }

    /** storage: database で、データベースのユーザーかパスワードが設定されていないときの説明 (ホストを始めない)。問題なければ null */
    fun databaseCredentialProblem(): String? {
        if (!usesDatabase) return null
        val missing = listOfNotNull(
            "hosting.database.user".takeIf { dbUser.isBlank() },
            "hosting.database.password".takeIf { dbPassword.isBlank() },
        )
        return if (missing.isEmpty()) null else missing.joinToString(" and ") + " not set"
    }

    /** root で接続する設定か (動くが、このプラグイン専用のユーザーをすすめる) */
    val usesRootDatabaseUser: Boolean get() = usesDatabase && dbUser.trim().equals("root", ignoreCase = true)

    companion object {
        // 看板は表に 320 文字。数値の分を残して、URL の頭はこれまで
        const val MAX_BASE_URL_LENGTH = 100
        const val FILES = "files"
        const val DATABASE = "database"
        private val PREFIX = Regex("[A-Za-z0-9_]{0,32}")
        private val HOST = Regex("[a-z0-9.-]{1,253}")

        /** 設定のホストの 1 行を、比べられる形にする。ホスト名の形でなければ null */
        private fun hostEntry(raw: String): String? =
            raw.trim().lowercase(Locale.ROOT).removePrefix("*.").trim('.').takeIf { it.matches(HOST) }

        fun read(c: ConfigurationSection, warn: (String) -> Unit): HostingSettings {
            val h = HostingSettings()
            h.enabled = c.getBoolean("hosting.enabled", h.enabled)
            val base = c.getString("hosting.public-base-url", "")?.trim()?.trimEnd('/') ?: ""
            h.publicBaseUrl = if (base.isEmpty() || validBaseUrl(base)) base else {
                warn("hosting.public-base-url must be an http(s) URL without query, fragment or user info (up to $MAX_BASE_URL_LENGTH characters); hosting is off")
                ""
            }
            h.directory = c.getString("hosting.directory", "")?.trim() ?: ""
            h.bind = c.getString("hosting.bind", h.bind)?.trim()?.takeIf { it.isNotEmpty() } ?: h.bind
            h.port = c.getInt("hosting.port", h.port).coerceIn(0, 65535)
            h.verifyAfterHours = c.getInt("hosting.verify-after-hours", h.verifyAfterHours).coerceIn(0, 24 * 90)
            h.unhostAfterDays = c.getInt("hosting.unhost-after-days", h.unhostAfterDays).coerceIn(1, 3650)
            h.maxFileMb = c.getInt("hosting.max-file-mb", h.maxFileMb).coerceIn(1, 2048)
            h.maxTotalMb = c.getInt("hosting.max-total-mb", h.maxTotalMb).coerceIn(1, 1_048_576)
            h.maxTotalFiles = c.getInt("hosting.max-total-files", h.maxTotalFiles).coerceIn(1, 1_000_000)
            h.perPlayerFiles = c.getInt("hosting.quota.per-player-files", h.perPlayerFiles).coerceIn(0, 1_000_000)
            h.perPlayerMb = c.getInt("hosting.quota.per-player-mb", h.perPlayerMb).coerceIn(0, 1_048_576)
            h.downloadTimeoutSeconds = c.getInt("hosting.download-timeout-seconds", h.downloadTimeoutSeconds).coerceIn(10, 3600)
            h.downloadConcurrency = c.getInt("hosting.download-concurrency", h.downloadConcurrency).coerceIn(1, 8)
            h.perHourLimit = c.getInt("hosting.per-hour-limit", h.perHourLimit).coerceIn(1, 10_000)
            h.excludeHosts = c.getStringList("hosting.exclude-hosts").mapNotNull { hostEntry(it) }
            val allow = c.getStringList("hosting.allow-hosts").filter { it.isNotBlank() }
            h.allowHostsSet = allow.isNotEmpty()
            h.allowHosts = allow.mapNotNull { hostEntry(it) }
            for (bad in allow.filter { hostEntry(it) == null }) warn("hosting.allow-hosts: ignoring an entry that is not a host name: ${SafeText.clean(bad, 60)}")
            if (h.allowHostsSet && h.allowHosts.isEmpty()) warn("hosting.allow-hosts has no usable entry; no new URLs are hosted")
            h.requireApproval = c.getBoolean("hosting.require-approval", h.requireApproval)
            val storage =c.getString("hosting.storage", h.storage)?.trim()?.lowercase(Locale.ROOT) ?: h.storage
            if (storage == FILES || storage == DATABASE) h.storage = storage
            else warn("Unknown hosting.storage '${SafeText.clean(storage, 40)}'; using ${h.storage}")
            h.dbUrl = c.getString("hosting.database.url", h.dbUrl)?.trim() ?: h.dbUrl
            if (h.usesDatabase && !h.dbUrl.startsWith("jdbc:")) warn("hosting.database.url must be a JDBC URL (jdbc:mysql://host:port/database)")
            h.dbUser = c.getString("hosting.database.user", h.dbUser) ?: h.dbUser
            h.dbPassword = c.getString("hosting.database.password", h.dbPassword) ?: h.dbPassword
            val prefix = c.getString("hosting.database.table-prefix", h.tablePrefix)?.trim() ?: h.tablePrefix
            if (prefix.matches(PREFIX)) h.tablePrefix = prefix
            else warn("hosting.database.table-prefix may only use A-Z, a-z, 0-9 and _ (up to 32 characters); using ${h.tablePrefix}")
            h.clientCheckEnabled = c.getBoolean("hosting.client-check.enabled", h.clientCheckEnabled)
            h.clientSecret = c.getString("hosting.client-check.secret", "") ?: ""
            h.clientGraceMinutes = c.getInt("hosting.client-check.grace-minutes", h.clientGraceMinutes).coerceIn(0, 1440)
            return h
        }

        fun validBaseUrl(base: String): Boolean {
            if (base.length > MAX_BASE_URL_LENGTH || base.any { it <= ' ' || it.code >= 0x7F || it == '|' }) return false
            return try {
                val u = URI(base)
                (u.scheme == "https" || u.scheme == "http") && !u.host.isNullOrEmpty() && u.rawUserInfo == null &&
                    u.rawQuery == null && u.rawFragment == null
            } catch (e: Exception) {
                false
            }
        }
    }
}
