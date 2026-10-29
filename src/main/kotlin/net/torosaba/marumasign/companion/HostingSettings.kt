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
    var downloadTimeoutSeconds = 120
    var perHourLimit = 30
    var excludeHosts: List<String> = listOf()

    val maxFileBytes: Long get() = maxFileMb * 1024L * 1024L
    val maxTotalBytes: Long get() = maxTotalMb * 1024L * 1024L

    companion object {
        // 看板は表に 320 文字。数値の分を残して、URL の頭はこれまで
        const val MAX_BASE_URL_LENGTH = 100

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
            h.downloadTimeoutSeconds = c.getInt("hosting.download-timeout-seconds", h.downloadTimeoutSeconds).coerceIn(10, 3600)
            h.perHourLimit = c.getInt("hosting.per-hour-limit", h.perHourLimit).coerceIn(1, 10_000)
            h.excludeHosts = c.getStringList("hosting.exclude-hosts").map { it.trim().lowercase(Locale.ROOT) }
                .filter { it.matches(Regex("[a-z0-9.-]{1,253}")) }
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
