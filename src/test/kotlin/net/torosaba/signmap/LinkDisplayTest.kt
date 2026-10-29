package net.torosaba.signmap

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.event.HoverEvent
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.configuration.MemoryConfiguration
import org.bukkit.configuration.file.YamlConfiguration
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.InputStreamReader
import java.net.InetAddress
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

/** 地図・チャット・ログに出す文字の安全、コマンド、レイヤー、設定 */
internal class LinkDisplayTest {

    // ---- アドレスの表 (接続してよいか) ----

    @Test
    fun addressTable() {
        val rejected = listOf(
            "0.0.0.0", "0.1.2.3", "127.0.0.1", "127.255.255.254", "10.0.0.1", "10.255.255.255", "172.16.0.1", "172.17.0.1", "172.31.255.255",
            "192.168.0.1", "192.168.255.255", "169.254.169.254", "169.254.0.1", "100.64.0.1", "100.100.100.200", "100.127.255.255",
            "192.0.0.1", "192.0.2.1", "192.88.99.1", "198.18.0.1", "198.19.255.255", "198.51.100.1", "203.0.113.1",
            "224.0.0.1", "239.255.255.255", "240.0.0.1", "255.255.255.255",
            "::", "::1", "fe80::1", "febf::1", "fc00::1", "fd12:3456:789a::1", "ff00::1", "ff02::1", "fec0::1",
            "::ffff:127.0.0.1", "::ffff:7f00:1", "::ffff:10.0.0.1", "::ffff:a00:1", "::ffff:192.168.1.1", "::ffff:c0a8:101", "::ffff:169.254.169.254",
            "64:ff9b::7f00:1", "64:ff9b::c0a8:101", "64:ff9b:1::1", "2002:7f00:1::", "2002:c0a8:101::1", "2002:a00:1::1",
            "2001:0:4136:e378:8000:63bf:3fff:fdd2", "2001::1", "2001:db8::1", "3fff::1", "100::1", "5f00::1", "::192.168.0.1")
        for (s in rejected) assertFalse(AddressPolicy.isPublic(InetAddress.getByName(s)), s)
        val accepted = listOf(
            "1.1.1.1", "8.8.8.8", "93.184.216.34", "172.15.255.255", "172.32.0.1", "100.63.255.255", "100.128.0.1", "192.0.1.1", "192.169.0.1",
            "198.17.255.255", "198.20.0.1", "223.255.255.255", "2606:4700:4700::1111", "2a00:1450:4001:81b::200e", "2001:4860:4860::8888",
            "2620:fe::fe")
        for (s in accepted) assertTrue(AddressPolicy.isPublic(InetAddress.getByName(s)), s)
        assertFalse(AddressPolicy.isPublic(null))
    }

    // ---- 表示用の文字 ----

    @Test
    fun cleanRemovesControlBidiAndZeroWidthCharacters() {
        val evil = "a‮b​c\u0000d\u001Be\r\nf⁦g⁩h﻿i­j k\u0085l"
        assertEquals("abcdefghijkl", SafeText.clean(evil, 100))
        assertEquals("abc", SafeText.clean("abcdef", 3))
        assertEquals("日本語 ok", SafeText.clean("日本語 ok", 100))
        assertEquals("", SafeText.clean(null, 10))
        for (cp in intArrayOf(0x202A, 0x202B, 0x202C, 0x202D, 0x202E, 0x2066, 0x2067, 0x2068, 0x2069, 0x200B, 0x200C, 0x200D, 0x200E, 0x200F, 0x2060, 0xFEFF)) {
            assertEquals("", SafeText.clean(String(Character.toChars(cp)), 10), Integer.toHexString(cp))
        }
    }

    @Test
    fun displayUrlShowsOnlyHostAndPathWithoutQueryOrUserinfoAndNoControls() {
        assertEquals("i.imgur.com/abc.png", SafeText.displayUrl("https://i.imgur.com/abc.png?token=SECRET&sig=1#frag", 100))
        assertFalse(SafeText.displayUrl("https://user:pass@example.com/a.png?x=1", 100).contains("pass"))
        assertFalse(SafeText.displayUrl("https://user:pass@example.com/a.png?x=1", 100).contains("user"))
        assertEquals("example.com/a%E2%80%AEb", SafeText.displayUrl("https://example.com/a%E2%80%AEb", 100), "% 表記のものは、そのまま (文字としては出ない)")
        val longUrl = SafeText.displayUrl("https://example.com/" + "x".repeat(300), 40)
        assertEquals(40, longUrl.length)
        assertTrue(longUrl.contains("…"))
        // 国際化ドメインは punycode も併記する
        val idn = SafeText.displayUrl("https://例え.テスト/a.png", 100)
        assertTrue(idn.contains("xn--"), idn)
        assertTrue(idn.contains("例え"), idn)
        val puny = SafeText.displayUrl("https://xn--r8jz45g.xn--zckzah/a.png", 100)
        assertTrue(puny.contains("xn--r8jz45g"), puny)
        // 読めない URL でも、制御文字は通さない
        assertFalse(SafeText.displayUrl("not‮ a\u0000 url\r\n", 100).matches(Regex(".*[\\u202E\\u0000\\r\\n].*")))
        assertEquals("", SafeText.displayUrl(null, 10))
    }

    @Test
    fun logUrlStripsQueryUserinfoAndEscapesControls() {
        assertEquals("https://example.com/a.png", SafeText.logUrl("https://example.com/a.png?token=SECRET#x"))
        assertEquals("https://example.com/a", SafeText.logUrl("https://user:pw@example.com/a?q"))
        val s = SafeText.logUrl("https://example.com/a\r\n[INFO] fake line\u001B[31m‮")
        assertFalse(s.contains("\n") || s.contains("\r") || s.contains("\u001B") || s.contains("‮"))
        assertTrue(s.contains("\\u000d") && s.contains("\\u000a") && s.contains("\\u001b"))
        assertTrue(SafeText.logUrl("https://example.com/" + "a".repeat(1000)).length <= 200)
    }

    // ---- 地図のポップアップとラベル ----

    @Test
    fun popupEscapesEverythingAndNeverLinksTheUrl() {
        val nasty = "https://ex\"><script>alert(1)</script>.example/a\"onerror=\"x'<img src=x onerror=alert(1)>.png?x=<b>"
        val popup = DynmapBackend.problemPopup(broken(LinkReason.HTTP_404), view(nasty), "wo\"rld<b>", SignRecord(1, 2, 3, nasty),
            listOf(DynmapBackend.SignRef("wo\"rld<b>", 1, 2, 3), DynmapBackend.SignRef("<i>x</i>", 4, 5, 6)), true, NOW)
        for (bad in listOf("<script", "<img", "<b>", "<i>", "onerror=\"")) assertFalse(popup.contains(bad), "$bad in $popup")
        assertFalse(popup.contains("<a "), "リンク切れの URL は、リンクにしない")
        assertFalse(popup.lowercase().contains("href"))
        assertTrue(popup.contains("&lt;") || !popup.contains("<script"))
    }

    @Test
    fun popupShowsTheRequiredInformation() {
        val v = view("https://i.imgur.com/abc.png?sig=SECRET")
        val s = SignRecord(10, 64, -20, v.url)
        val popup = DynmapBackend.problemPopup(broken(LinkReason.HTTP_404), v, "main", s,
            listOf(DynmapBackend.SignRef("main", 10, 64, -20), DynmapBackend.SignRef("main", 1, 2, 3), DynmapBackend.SignRef("main", 4, 5, 6),
                DynmapBackend.SignRef("main", 7, 8, 9), DynmapBackend.SignRef("main", 11, 12, 13), DynmapBackend.SignRef("main", 14, 15, 16),
                DynmapBackend.SignRef("main", 17, 18, 19), DynmapBackend.SignRef("nether", 20, 21, 22)), true, NOW)
        assertTrue(popup.contains("リンク切れ"))
        assertTrue(popup.contains("404 見つからない"))
        assertTrue(popup.contains("i.imgur.com/abc.png"))
        assertFalse(popup.contains("SECRET"), "クエリ (署名つき URL のトークン) は出さない")
        assertTrue(popup.contains("12 日"), "リンク切れになってからの日数")
        assertTrue(popup.contains("最終確認") && popup.contains("最後に正常"))
        assertTrue(popup.contains("main 10 64 -20"))
        assertTrue(popup.contains("/tp 10 64 -20"))
        assertTrue(popup.contains("同じ URL の看板が他に 7 件"))
        assertEquals(5, countOccurrences(popup, "<br>main ") + countOccurrences(popup, "<br>nether "), "他の看板の座標は 5 件まで")
        assertFalse(popup.contains("<img"), "サムネイルは出さない")
    }

    @Test
    fun popupUsesOnlyCoarseReasons() {
        for (r in LinkReason.values()) {
            val label = r.publicLabel
            for (leaked in listOf("refused", "timeout", "REFUSED", "TIMEOUT", "reset", "DNS", "Exception", "java.", "http://", "https://")) {
                assertFalse(label.contains(leaked), "$r $label")
            }
        }
        // 接続の失敗の種類は、公開の地図では区別しない (ポートの探索に使われないように)
        assertEquals(LinkReason.DNS_FAILED.publicLabel, LinkReason.CONNECT_REFUSED.publicLabel)
        assertEquals(LinkReason.TIMEOUT.publicLabel, LinkReason.CONNECT_REFUSED.publicLabel)
        assertEquals(LinkReason.CONNECTION_RESET.publicLabel, LinkReason.CONNECT_REFUSED.publicLabel)
        assertEquals("証明書エラー", LinkReason.TLS_ERROR.publicLabel)
        assertEquals("画像でなくHTMLが返る", LinkReason.HTML_INSTEAD_OF_MEDIA.publicLabel)
    }

    @Test
    fun labelIsPlainText() {
        val label = DynmapBackend.problemLabel(broken(LinkReason.HTTP_404), view("https://i.imgur.com/abc123.png?token=SECRET"), true)
        assertEquals("リンク切れ(404 見つからない) 画像 i.imgur.com/abc123.png", label)
        val weird = DynmapBackend.problemLabel(broken(LinkReason.TLS_ERROR), view("https://e.example/<b>‮.png"), false)
        assertTrue(weird.startsWith("要確認(証明書エラー)"))
        assertFalse(weird.contains("‮"))
    }

    @Test
    fun layersAreChosenByState() {
        val cfg = LinkSettings()
        val pending = LinkState.pending(NOW)
        val ok = pending.apply(LinkProbe.Result.ok(200), NOW, cfg, 0.0)
        val brokenState = pending.apply(LinkProbe.Result.broken(LinkReason.HTTP_404, 404), NOW, cfg, 0.0)
        val once = pending.apply(LinkProbe.Result.unknown(LinkReason.TIMEOUT, 0, true), NOW, cfg, 0.0)
        val twice = once.apply(LinkProbe.Result.unknown(LinkReason.TIMEOUT, 0, true), NOW + 6 * 3_600_000L, cfg, 0.0)
        val denied = pending.apply(LinkProbe.Result.unknown(LinkReason.ACCESS_DENIED, 403, false), NOW, cfg, 0.0)
        assertNull(DynmapBackend.layerName(pending, true))
        assertNull(DynmapBackend.layerName(ok, false), "正常は、show-ok でなければ出さない")
        assertEquals("OK", DynmapBackend.layerName(ok, true))
        assertEquals("BROKEN", DynmapBackend.layerName(brokenState, false))
        assertNull(DynmapBackend.layerName(once, true), "1 回きりの失敗は、出さない")
        assertEquals("UNKNOWN", DynmapBackend.layerName(twice, false))
        assertEquals("UNKNOWN", DynmapBackend.layerName(denied, false))
    }

    // ---- コマンド ----

    @Test
    fun chatLineSuggestsTpOnlyAndNeverRunsOrOpens() {
        val row = AdminCommands.Row("ma‮in\nworld", SignRecord(10, 64, -20, "https://e.example/a.png?token=SECRET"), broken(LinkReason.HTTP_404))
        val line = AdminCommands.line(row, NOW)
        assertEquals(ClickEvent.Action.SUGGEST_COMMAND, line.clickEvent()!!.action())
        assertEquals("/tp 10 64 -20", (line.clickEvent()!!.payload() as ClickEvent.Payload.Text).value())
        assertNotNull(line.hoverEvent())
        assertEquals(HoverEvent.Action.SHOW_TEXT, line.hoverEvent()!!.action())
        // 子要素にもクリックを付けない
        for (child in line.children()) assertNull(child.clickEvent())
        val plain = PlainTextComponentSerializer.plainText().serialize(line)
        assertFalse(plain.contains("SECRET"))
        for (c in plain.toCharArray()) assertFalse(c.code < 0x20 || c.code == 0x202E, "制御文字がチャットに出ない: " + c.code)
        assertTrue(plain.contains("12日"))
        assertTrue(plain.contains("HTTP_404"))
    }

    @Test
    fun textIsNotInterpretedAsMiniMessage() {
        val sneaky = "<click:run_command:/op me><red>x</red>"
        val c: Component = Component.text(sneaky)
        assertEquals(sneaky, PlainTextComponentSerializer.plainText().serialize(c))
        assertNull(c.clickEvent())
    }

    @Test
    fun argumentsAreValidated() {
        assertEquals(1, AdminCommands.parsePage("1"))
        assertEquals(9999, AdminCommands.parsePage("9999"))
        for (bad in arrayOf("0", "-1", "10000", "abc", "1e3", " 1", "1 ", "", null, "1\n", "٣", "+1")) assertNull(AdminCommands.parsePage(bad), bad.toString())
        assertTrue(AdminCommands.validUrlArgument("https://example.com/a.png"))
        for (bad in arrayOf("", null, "https://e.example/a b", "https://e.example/\n", "https://e.example/\u0000", "https://e.example/‮" + "\t",
            "x".repeat(2001))) {
            assertFalse(AdminCommands.validUrlArgument(bad), bad.toString())
        }
    }

    @Test
    fun tabCompletion() {
        val cmds = AdminCommands(null)
        assertEquals(listOf("broken"), cmds.complete(arrayOf("br")))
        assertEquals(listOf("linkstatus"), cmds.complete(arrayOf("li")))
        assertEquals(listOf("recheck", "reload", "rescan"), cmds.complete(arrayOf("re")).sorted())
        assertEquals(listOf("all-broken"), cmds.complete(arrayOf("recheck", "al")))
        assertEquals(listOf<String>(), cmds.complete(arrayOf("broken", "1")))
        assertEquals(6, cmds.complete(arrayOf("")).size)
    }

    @Test
    fun commandRequiresTheAdminPermissionWhichDefaultsToOp() {
        LinkDisplayTest::class.java.getResourceAsStream("/plugin.yml").use { input ->
            assertNotNull(input)
            val yaml = YamlConfiguration.loadConfiguration(InputStreamReader(input!!, StandardCharsets.UTF_8))
            assertEquals("marumasignmap.admin", yaml.getString("commands.marumasignmap.permission"))
            assertEquals("op", yaml.getString("permissions.marumasignmap.admin.default"))
            assertEquals("op", yaml.getString("permissions.bluemapmarumasign.admin.default"))
            assertTrue(yaml.getBoolean("permissions.bluemapmarumasign.admin.children.marumasignmap.admin"), "古い権限の名前も使える")
        }
        assertEquals("marumasignmap.admin", AdminCommands.PERMISSION)
        // コード側でも確かめている (権限がなければ、サブコマンドに入る前に返す)
        val plugin = Files.readString(Path.of("src/main/kotlin/net/torosaba/signmap/SignMapPlugin.kt"), StandardCharsets.UTF_8)
        val check = plugin.indexOf("hasPermission(AdminCommands.PERMISSION)")
        val dispatch = plugin.indexOf("admin.handle(")
        assertTrue(check > 0 && dispatch > check)
        assertTrue(plugin.contains("onTabComplete") && plugin.indexOf("hasPermission(AdminCommands.PERMISSION)", plugin.indexOf("onTabComplete")) > 0)
    }

    @Test
    fun sourcesNeverUseDangerousClicksOrParsers() {
        for (root in listOf(Path.of("src/main/java"), Path.of("src/main/kotlin"))) {
            if (!Files.isDirectory(root)) continue
            Files.walk(root).use { files ->
                for (f in files.filter { p -> p.toString().endsWith(".java") || p.toString().endsWith(".kt") }.toList()) {
                    val code = Files.readString(f, StandardCharsets.UTF_8).replace(Regex("(?s)/\\*.*?\\*/"), "").replace(Regex("(?m)//.*$"), "")
                    for (forbidden in listOf("ClickEvent.runCommand", "ClickEvent.openUrl", "RUN_COMMAND", "OPEN_URL", "MiniMessage", "LegacyComponentSerializer.legacy",
                        "dispatchCommand", "Bukkit.broadcast", "TrustManager", "HostnameVerifier", "setDefaultSSLSocketFactory", "Runtime.getRuntime().exec", "ProcessBuilder")) {
                        assertFalse(code.contains(forbidden), f.fileName.toString() + " uses " + forbidden)
                    }
                }
            }
        }
    }

    // ---- 設定 ----

    @Test
    fun linkSettingsAreClampedAndSanitized() {
        val c = MemoryConfiguration()
        c.set("link-check.concurrency", 500)
        c.set("link-check.per-hour-limit", -5)
        c.set("link-check.host-per-hour-limit", 10_000_000)
        c.set("link-check.timeouts.total-seconds", 99999)
        c.set("link-check.timeouts.connect-seconds", 0)
        c.set("link-check.max-body-kb", 99999)
        c.set("link-check.max-redirects", 1000)
        c.set("link-check.unknown-backoff-hours", listOf(0, 5000))
        c.set("link-check.user-agent", "Evil\r\nX-Injected: yes\u0000")
        c.set("link-check.host-rules", listOf(
            java.util.Map.of("host", "imgur.com", "min-interval-seconds", 99999, "removed-paths", listOf("/removed.png", "no-slash", "/a b", "/<x>")),
            java.util.Map.of("host", "bad host!", "min-interval-seconds", 1),
            java.util.Map.of("min-interval-seconds", 1)))
        val l = SignMapPlugin.readLinkSettings(c)
        assertEquals(4, l.concurrency)
        assertEquals(1, l.perHourLimit)
        assertEquals(10_000, l.hostPerHourLimit)
        assertEquals(120, l.totalTimeoutSeconds)
        assertEquals(1, l.connectTimeoutSeconds)
        assertEquals(256, l.maxBodyKb)
        assertEquals(10, l.maxRedirects)
        assertEquals(listOf(1, 720), l.unknownBackoffHours)
        assertEquals("EvilX-Injected: yes", l.userAgent)
        assertFalse(l.userAgent.contains("\r") || l.userAgent.contains("\n"))
        assertEquals(1, l.hostRules.size)
        assertEquals(3600, l.hostRules[0].minIntervalSeconds)
        assertEquals(listOf("/removed.png"), l.hostRules[0].removedPaths)
    }

    @Test
    fun defaultsMatchTheSpecification() {
        val l = LinkSettings()
        assertTrue(l.enabled)
        assertEquals(2, l.concurrency)
        assertEquals(5, l.startupDelayMinutes)
        assertEquals(7, l.recheckOkDays)
        assertEquals(24, l.recheckBrokenHours)
        assertEquals(listOf(6, 12, 24), l.unknownBackoffHours)
        assertEquals(3, l.brokenAfterFailures)
        assertEquals(24, l.brokenAfterHours)
        assertFalse(l.treat403AsBroken)
        assertEquals(5, l.maxRedirects)
        assertEquals(5, l.connectTimeoutSeconds)
        assertEquals(20, l.totalTimeoutSeconds)
        assertEquals(64, l.maxBodyKb)
        assertEquals("/removed.png", l.ruleFor("i.imgur.com")!!.removedPaths[0])
        val s = Settings()
        assertTrue(s.dynmapDefaultHidden)
        assertFalse(s.dynmapShowOk)
    }

    @Test
    fun shippedConfigParsesToTheDefaults() {
        LinkDisplayTest::class.java.getResourceAsStream("/config.yml").use { input ->
            assertNotNull(input)
            val yaml = YamlConfiguration.loadConfiguration(InputStreamReader(input!!, StandardCharsets.UTF_8))
            val l = SignMapPlugin.readLinkSettings(yaml)
            val d = LinkSettings()
            assertEquals(d.enabled, l.enabled)
            assertEquals(d.concurrency, l.concurrency)
            assertEquals(d.perHourLimit, l.perHourLimit)
            assertEquals(d.hostPerHourLimit, l.hostPerHourLimit)
            assertEquals(d.unknownBackoffHours, l.unknownBackoffHours)
            assertEquals(d.userAgent, l.userAgent)
            assertEquals(1, l.hostRules.size)
            assertFalse(yaml.getBoolean("dynmap.show-ok"))
            assertTrue(yaml.getBoolean("dynmap.default-hidden"))
        }
    }

    companion object {
        private const val DAY = 86_400_000L
        private const val NOW = 1_700_000_000_000L + 100 * DAY

        private fun view(url: String): MarkerHtml.View = MarkerHtml.View(SignRecord(10, 64, -20, url), url, MediaKind.IMAGE, "png")

        private fun broken(reason: LinkReason): LinkState =
            LinkState(LinkStatus.BROKEN, reason, 404, NOW - 12 * DAY, NOW - DAY, NOW - 30 * DAY, NOW - DAY, 4, NOW + DAY, NOW)

        private fun countOccurrences(s: String, sub: String): Int {
            var n = 0
            var i = s.indexOf(sub)
            while (i >= 0) {
                n++
                i = s.indexOf(sub, i + sub.length)
            }
            return n
        }
    }
}
