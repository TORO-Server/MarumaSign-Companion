package net.torosaba.signmap;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.CommandSender;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * リンク切れの管理コマンド (/marumasignmap broken | linkstatus | recheck)。
 * <ul>
 *   <li>権限 marumasignmap.admin (既定は OP)。呼び出し側 ({@link SignMapPlugin#onCommand}) が確かめてから呼ぶ。</li>
 *   <li>チャットの文字は Adventure の Component.text だけで作る (URL や看板の文字を MiniMessage などで解釈しない)。</li>
 *   <li>クリックは SUGGEST_COMMAND (/tp ...) だけ。RUN_COMMAND と OPEN_URL は使わない。</li>
 *   <li>URL は、危険な文字を除いた、ホストとパスだけを出す。引数は長さと文字を検証する。</li>
 * </ul>
 */
final class AdminCommands {

    static final String PERMISSION = "marumasignmap.admin";
    static final int PAGE_SIZE = 8;
    private static final List<String> SUBCOMMANDS = List.of("status", "rescan", "reload", "broken", "linkstatus", "recheck");

    private final SignMapPlugin plugin;

    AdminCommands(SignMapPlugin plugin) {
        this.plugin = plugin;
    }

    void handle(CommandSender sender, String sub, String[] args) {
        final LinkChecker checker = plugin.linkChecker();
        if (checker == null) {
            sender.sendMessage(Component.text("リンク切れの検査は無効です (config.yml の link-check.enabled)。", NamedTextColor.YELLOW));
            return;
        }
        switch (sub) {
            case "broken" -> broken(sender, checker, args);
            case "linkstatus" -> linkStatus(sender, checker);
            case "recheck" -> recheck(sender, checker, args);
            default -> {
            }
        }
    }

    // ---- broken [ページ] ----

    /** リンク切れの 1 件 (一覧の元) */
    record Row(String world, SignRecord sign, LinkState state) {
    }

    List<Row> brokenRows(LinkChecker checker) {
        final Map<String, LinkState> states = checker.snapshot();
        final List<Row> rows = new ArrayList<>();
        for (Map.Entry<String, SignMapPlugin.WorldState> w : plugin.worldStates().entrySet()) {
            for (SignRecord s : w.getValue().store.all()) {
                final LinkState st = states.get(s.url());
                if (st != null && st.status() == LinkStatus.BROKEN) rows.add(new Row(w.getKey(), s, st));
            }
        }
        // リンク切れの期間が長い順 (最初に失敗した時刻が早い順)
        rows.sort(Comparator.comparingLong((Row r) -> r.state().firstFailedAt() > 0 ? r.state().firstFailedAt() : Long.MAX_VALUE)
                .thenComparing(Row::world).thenComparingInt(r -> r.sign().x()).thenComparingInt(r -> r.sign().z()));
        return rows;
    }

    private void broken(CommandSender sender, LinkChecker checker, String[] args) {
        int page = 1;
        if (args.length >= 2) {
            final Integer p = parsePage(args[1]);
            if (p == null) {
                sender.sendMessage(Component.text("ページ番号は 1 から 9999 の数字です。", NamedTextColor.RED));
                return;
            }
            page = p;
        }
        final List<Row> rows = brokenRows(checker);
        final int pages = Math.max(1, (rows.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        page = Math.min(page, pages);
        sender.sendMessage(Component.text("リンク切れの看板: " + rows.size() + " 件 (ページ " + page + "/" + pages + ")", NamedTextColor.GOLD));
        final long now = System.currentTimeMillis();
        for (int i = (page - 1) * PAGE_SIZE; i < Math.min(rows.size(), page * PAGE_SIZE); i++) {
            sender.sendMessage(line(rows.get(i), now));
        }
        if (page < pages) sender.sendMessage(Component.text("次: /marumasignmap broken " + (page + 1), NamedTextColor.GRAY));
    }

    /** 1 行。座標の部分をクリックすると、/tp を入力欄に入れる (実行はしない) */
    static Component line(Row r, long now) {
        final SignRecord s = r.sign();
        final String cmd = DynmapBackend.tpCommand(s.x(), s.y(), s.z());
        final long days = r.state().firstFailedAt() > 0 ? Math.max(0, (now - r.state().firstFailedAt()) / 86_400_000L) : 0;
        return Component.text("[" + DynmapBackend.coords(r.world(), s.x(), s.y(), s.z()) + "]", NamedTextColor.AQUA)
                .clickEvent(ClickEvent.suggestCommand(cmd))
                .hoverEvent(HoverEvent.showText(Component.text("クリックで入力欄に " + cmd + " を入れる")))
                .append(Component.text(" " + days + "日 " + r.state().reason().name() + " " + SafeText.displayUrl(s.url(), 50), NamedTextColor.GRAY));
    }

    static Integer parsePage(String s) {
        if (s == null || s.isEmpty() || s.length() > 4) return null;
        for (int i = 0; i < s.length(); i++) if (s.charAt(i) < '0' || s.charAt(i) > '9') return null;
        final int p = Integer.parseInt(s);
        return p >= 1 ? p : null;
    }

    // ---- linkstatus ----

    private void linkStatus(CommandSender sender, LinkChecker checker) {
        final LinkChecker.Counts c = checker.counts();
        final int total = checker.tracked();
        final int done = total - c.pending();
        sender.sendMessage(Component.text("リンク検査: 追跡 " + total + " 件 / 検査済み " + done + " (" + (total == 0 ? 100 : done * 100 / total) + "%)", NamedTextColor.GOLD));
        sender.sendMessage(Component.text("OK " + c.ok() + " / BROKEN " + c.broken() + " / UNKNOWN " + c.unknown() + " / PENDING " + c.pending(), NamedTextColor.WHITE));
        sender.sendMessage(Component.text("検査中 " + c.inFlight() + " / 直近 1 時間の検査 " + c.hourUsed() + " (上限 " + plugin.settings().link.perHourLimit + ")", NamedTextColor.GRAY));
    }

    // ---- recheck <URL|all-broken> ----

    private void recheck(CommandSender sender, LinkChecker checker, String[] args) {
        if (args.length != 2) {
            sender.sendMessage(Component.text("使い方: /marumasignmap recheck <URL|all-broken>", NamedTextColor.RED));
            return;
        }
        final String arg = args[1];
        if (arg.equalsIgnoreCase("all-broken")) {
            sender.sendMessage(Component.text("リンク切れ " + checker.recheckAllBroken() + " 件を、すぐ再検査します。", NamedTextColor.GREEN));
            return;
        }
        if (!validUrlArgument(arg)) {
            sender.sendMessage(Component.text("URL の形が正しくありません (2000 文字まで、空白や制御文字は使えません)。", NamedTextColor.RED));
            return;
        }
        final int n = checker.recheck(arg);
        sender.sendMessage(n > 0 ? Component.text("再検査します: " + SafeText.displayUrl(arg, 60), NamedTextColor.GREEN)
                : Component.text("その URL は追跡していません。", NamedTextColor.YELLOW));
    }

    /** コマンドの引数としての URL: 長さの上限、空白・制御文字なし */
    static boolean validUrlArgument(String s) {
        if (s == null || s.isEmpty() || s.length() > UrlFilter.MAX_URL_LENGTH) return false;
        for (int i = 0; i < s.length(); i++) {
            final char c = s.charAt(i);
            if (c <= ' ' || c == 0x7F || Character.isISOControl(c) || Character.isWhitespace(c)) return false;
        }
        return true;
    }

    // ---- タブ補完 ----

    List<String> complete(String[] args) {
        if (args.length == 1) return filter(SUBCOMMANDS, args[0]);
        if (args.length == 2 && args[0].equalsIgnoreCase("recheck")) return filter(List.of("all-broken"), args[1]);
        return List.of();
    }

    private static List<String> filter(List<String> options, String prefix) {
        final String p = prefix == null ? "" : prefix.toLowerCase(Locale.ROOT);
        final List<String> out = new ArrayList<>();
        for (String o : options) if (o.startsWith(p)) out.add(o);
        return out;
    }
}
