package net.torosaba.signmap

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.event.HoverEvent
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.command.CommandSender
import java.util.Locale

/**
 * リンク切れの管理コマンド (/marumasignmap broken | linkstatus | recheck)。
 * - 権限 marumasignmap.admin (既定は OP)。呼び出し側 ([SignMapPlugin.onCommand]) が確かめてから呼ぶ。
 * - チャットの文字は Adventure の Component.text だけで作る (URL や看板の文字を MiniMessage などで解釈しない)。
 * - クリックは SUGGEST_COMMAND (/tp ...) だけ。RUN_COMMAND と OPEN_URL は使わない。
 * - URL は、危険な文字を除いた、ホストとパスだけを出す。引数は長さと文字を検証する。
 */
internal class AdminCommands(private val plugin: SignMapPlugin?) {

    fun handle(sender: CommandSender, sub: String, args: Array<String>) {
        val checker = plugin!!.linkChecker()
        if (checker == null) {
            sender.sendMessage(Component.text("リンク切れの検査は無効です (config.yml の link-check.enabled)。", NamedTextColor.YELLOW))
            return
        }
        when (sub) {
            "broken" -> broken(sender, checker, args)
            "linkstatus" -> linkStatus(sender, checker)
            "recheck" -> recheck(sender, checker, args)
            else -> {
            }
        }
    }

    // ---- broken [ページ] ----

    /** リンク切れの 1 件 (一覧の元) */
    data class Row(val world: String, val sign: SignRecord, val state: LinkState)

    fun brokenRows(checker: LinkChecker): List<Row> {
        val states = checker.snapshot()
        val rows = ArrayList<Row>()
        for ((name, w) in plugin!!.worldStates()) {
            for (s in w.store.all()) {
                val st = states[s.url]
                if (st != null && st.status() == LinkStatus.BROKEN) rows.add(Row(name, s, st))
            }
        }
        // リンク切れの期間が長い順 (最初に失敗した時刻が早い順)
        rows.sortWith(Comparator.comparingLong<Row> { r -> if (r.state.firstFailedAt() > 0) r.state.firstFailedAt() else Long.MAX_VALUE }
            .thenComparing(Comparator.comparing<Row, String> { it.world })
            .thenComparingInt { it.sign.x }.thenComparingInt { it.sign.z })
        return rows
    }

    private fun broken(sender: CommandSender, checker: LinkChecker, args: Array<String>) {
        var page = 1
        if (args.size >= 2) {
            val p = parsePage(args[1])
            if (p == null) {
                sender.sendMessage(Component.text("ページ番号は 1 から 9999 の数字です。", NamedTextColor.RED))
                return
            }
            page = p
        }
        val rows = brokenRows(checker)
        val pages = Math.max(1, (rows.size + PAGE_SIZE - 1) / PAGE_SIZE)
        page = Math.min(page, pages)
        sender.sendMessage(Component.text("リンク切れの看板: " + rows.size + " 件 (ページ " + page + "/" + pages + ")", NamedTextColor.GOLD))
        val now = System.currentTimeMillis()
        for (i in (page - 1) * PAGE_SIZE until Math.min(rows.size, page * PAGE_SIZE)) {
            sender.sendMessage(line(rows[i], now))
        }
        if (page < pages) sender.sendMessage(Component.text("次: /marumasignmap broken " + (page + 1), NamedTextColor.GRAY))
    }

    // ---- linkstatus ----

    private fun linkStatus(sender: CommandSender, checker: LinkChecker) {
        val c = checker.counts()
        val total = checker.tracked()
        val done = total - c.pending()
        sender.sendMessage(Component.text("リンク検査: 追跡 " + total + " 件 / 検査済み " + done + " (" + (if (total == 0) 100 else done * 100 / total) + "%)", NamedTextColor.GOLD))
        sender.sendMessage(Component.text("OK " + c.ok() + " / BROKEN " + c.broken() + " / UNKNOWN " + c.unknown() + " / PENDING " + c.pending(), NamedTextColor.WHITE))
        sender.sendMessage(Component.text("検査中 " + c.inFlight() + " / 直近 1 時間の検査 " + c.hourUsed() + " (上限 " + plugin!!.settings().link.perHourLimit + ")", NamedTextColor.GRAY))
    }

    // ---- recheck <URL|all-broken> ----

    private fun recheck(sender: CommandSender, checker: LinkChecker, args: Array<String>) {
        if (args.size != 2) {
            sender.sendMessage(Component.text("使い方: /marumasignmap recheck <URL|all-broken>", NamedTextColor.RED))
            return
        }
        val arg = args[1]
        if (arg.equals("all-broken", ignoreCase = true)) {
            sender.sendMessage(Component.text("リンク切れ " + checker.recheckAllBroken() + " 件を、すぐ再検査します。", NamedTextColor.GREEN))
            return
        }
        if (!validUrlArgument(arg)) {
            sender.sendMessage(Component.text("URL の形が正しくありません (2000 文字まで、空白や制御文字は使えません)。", NamedTextColor.RED))
            return
        }
        val n = checker.recheck(arg)
        sender.sendMessage(if (n > 0) Component.text("再検査します: " + SafeText.displayUrl(arg, 60), NamedTextColor.GREEN)
        else Component.text("その URL は追跡していません。", NamedTextColor.YELLOW))
    }

    // ---- タブ補完 ----

    fun complete(args: Array<String>): List<String> {
        if (args.size == 1) return filter(SUBCOMMANDS, args[0])
        if (args.size == 2 && args[0].equals("recheck", ignoreCase = true)) return filter(listOf("all-broken"), args[1])
        return listOf()
    }

    companion object {
        const val PERMISSION = "marumasignmap.admin"
        const val PAGE_SIZE = 8
        private val SUBCOMMANDS = listOf("status", "rescan", "reload", "broken", "linkstatus", "recheck")

        /** 1 行。座標の部分をクリックすると、/tp を入力欄に入れる (実行はしない) */
        fun line(r: Row, now: Long): Component {
            val s = r.sign
            val cmd = DynmapBackend.tpCommand(s.x, s.y, s.z)
            val days = if (r.state.firstFailedAt() > 0) Math.max(0L, (now - r.state.firstFailedAt()) / 86_400_000L) else 0L
            return Component.text("[" + DynmapBackend.coords(r.world, s.x, s.y, s.z) + "]", NamedTextColor.AQUA)
                .clickEvent(ClickEvent.suggestCommand(cmd))
                .hoverEvent(HoverEvent.showText(Component.text("クリックで入力欄に $cmd を入れる")))
                .append(Component.text(" " + days + "日 " + r.state.reason().name + " " + SafeText.displayUrl(s.url, 50), NamedTextColor.GRAY))
        }

        fun parsePage(s: String?): Int? {
            if (s == null || s.isEmpty() || s.length > 4) return null
            for (i in 0 until s.length) if (s[i] < '0' || s[i] > '9') return null
            val p = Integer.parseInt(s)
            return if (p >= 1) p else null
        }

        /** コマンドの引数としての URL: 長さの上限、空白・制御文字なし */
        fun validUrlArgument(s: String?): Boolean {
            if (s == null || s.isEmpty() || s.length > UrlFilter.MAX_URL_LENGTH) return false
            for (i in 0 until s.length) {
                val c = s[i]
                if (c <= ' ' || c.code == 0x7F || Character.isISOControl(c) || Character.isWhitespace(c)) return false
            }
            return true
        }

        private fun filter(options: List<String>, prefix: String?): List<String> {
            val p = prefix?.lowercase(Locale.ROOT) ?: ""
            val out = ArrayList<String>()
            for (o in options) if (o.startsWith(p)) out.add(o)
            return out
        }
    }
}
