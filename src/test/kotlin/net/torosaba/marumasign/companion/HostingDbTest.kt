package net.torosaba.marumasign.companion

import org.bukkit.configuration.file.YamlConfiguration
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.sql.SQLException
import java.util.HexFormat
import java.util.Random
import java.util.UUID
import java.util.logging.Logger

/** hosting.storage: database (H2 の MySQL 互換モードで、TiDB / MySQL の代わり) と、接続しているクライアントの記録 */
class HostingDbTest {

    private val logger = Logger.getLogger("HostingDbTest")
    private val prefix = "msc_"
    private val mib = DbHostingStorage.CHUNK_SIZE

    private fun db(name: String = UUID.randomUUID().toString()) =
        Db("jdbc:h2:mem:$name;MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "", logger,
            init = { db, c -> for (sql in DbHostingStorage.schema(prefix)) db.update(c, sql) })

    private fun storage(db: Db, oldState: Path? = null, oldDir: Path? = null) =
        DbHostingStorage(logger, db, prefix, "test", oldState, oldDir)

    /** 読み込んだのと同じ形の一時ファイルと、その id のファイル (id は中身と「.拡張子」の SHA-256 から) */
    private fun content(dir: Path, bytes: ByteArray, ext: String = "png"): Pair<HostedStore.HostedFile, Path> {
        val md = MessageDigest.getInstance("SHA-256")
        md.update(bytes)
        md.update(".$ext".toByteArray(StandardCharsets.US_ASCII))
        val id = HostFetcher.idOf(md.digest())
        val p = dir.resolve("dl-$id.part")
        Files.write(p, bytes)
        return HostedStore.HostedFile(id, ext, bytes.size.toLong(), 100, 200) to p
    }

    /** storage: files のフォルダに置いたファイル (<id>.<拡張子>) */
    private fun placed(dir: Path, bytes: ByteArray, ext: String): HostedStore.HostedFile {
        val (f, p) = content(dir, bytes, ext)
        Files.move(p, dir.resolve(f.fileName))
        return f
    }

    private fun random(n: Int, seed: Long = 1) = ByteArray(n).also { Random(seed).nextBytes(it) }

    private fun chunks(db: Db, id: String): List<ByteArray> = db.use { c ->
        db.query(c, "SELECT data FROM ${prefix}hosted_chunk WHERE id = ? ORDER BY seq", id) { rs ->
            val out = ArrayList<ByteArray>()
            while (rs.next()) out.add(rs.getBytes(1))
            out
        }
    }

    private fun count(db: Db, table: String): Int = db.use { c ->
        db.query(c, "SELECT COUNT(*) FROM $prefix$table") { rs -> rs.next(); rs.getInt(1) }
    }

    // ---- 中身の分け方 ----

    @Test
    fun filesAreSplitIntoOneMebibyteChunksWithAShortLastChunk(@TempDir dir: Path) {
        val db = db()
        val s = storage(db)
        s.load(HostedStore())
        val bytes = random(2 * mib + mib / 2)
        val (f, tmp) = content(dir, bytes)
        s.put(f, tmp)
        assertFalse(Files.exists(tmp), "一時ファイルは消す")
        val parts = chunks(db, f.id)
        assertEquals(listOf(mib, mib, mib / 2), parts.map { it.size })
        val joined = ByteArrayOutputStream().apply { parts.forEach { write(it) } }.toByteArray()
        assertArrayEquals(bytes, joined)
        db.use { c ->
            db.query(c, "SELECT ext, content_type, size, chunk_size, chunks, sha256, hosted_at, last_used_at FROM ${prefix}hosted_file WHERE id = ?", f.id) { rs ->
                assertTrue(rs.next())
                assertEquals("png", rs.getString(1))
                assertEquals("image/png", rs.getString(2))
                assertEquals(bytes.size.toLong(), rs.getLong(3))
                assertEquals(1048576, rs.getInt(4))
                assertEquals(3, rs.getInt(5))
                assertEquals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)), rs.getString(6))
                assertEquals(100L, rs.getLong(7))
                assertEquals(200L, rs.getLong(8))
            }
        }
        assertTrue(s.has(f))

        // ちょうど 2 MiB は 2 行、小さいものは 1 行
        val (f2, tmp2) = content(dir, random(2 * mib, 2))
        s.put(f2, tmp2)
        assertEquals(listOf(mib, mib), chunks(db, f2.id).map { it.size })
        val (f3, tmp3) = content(dir, "tiny".toByteArray(), "gif")
        s.put(f3, tmp3)
        assertEquals(listOf(4), chunks(db, f3.id).map { it.size })
    }

    @Test
    fun chunksAreWrittenBeforeTheFileRowEachInItsOwnStatement(@TempDir dir: Path) {
        val db = db()
        val s = storage(db)
        s.load(HostedStore())
        val (f, tmp) = content(dir, random(3 * mib + 10))
        val log = ArrayList<String>()
        db.trace = { log.add(it) }
        s.put(f, tmp)
        val chunkWrites = log.withIndex().filter { it.value.startsWith("INSERT INTO ${prefix}hosted_chunk") }.map { it.index }
        val fileWrite = log.indexOfFirst { it.startsWith("INSERT INTO ${prefix}hosted_file") }
        assertEquals(4, chunkWrites.size, "1 行ずつ別の文で (TiDB の 1 つの書き込みの上限より小さく)")
        assertTrue(fileWrite > chunkWrites.max(), "中身をすべて書いてから hosted_file の行: $log")
    }

    @Test
    fun aFailedWriteLeavesNoFileRowAndCanBeRetried(@TempDir dir: Path) {
        val db = db()
        val s = storage(db)
        s.load(HostedStore())
        val bytes = random(3 * mib)
        val (f, tmp) = content(dir, bytes)
        var seen = 0
        db.trace = { sql -> if (sql.startsWith("INSERT INTO ${prefix}hosted_chunk") && ++seen == 2) throw SQLException("disk full", "HY000") }
        assertThrows<IOException> { s.put(f, tmp) }
        db.trace = null
        assertEquals(0, count(db, "hosted_file"), "中身がそろっていない hosted_file の行は作らない")
        assertFalse(s.has(f))

        // 読み込み直して、もう一度 (途中まで書いた行は消してから書く)
        val (_, again) = content(dir, bytes)
        s.put(f, again)
        assertEquals(listOf(mib, mib, mib), chunks(db, f.id).map { it.size })
        assertEquals(1, count(db, "hosted_file"))

        // 名前と中身が合わなければ書かない
        val (g, bad) = content(dir, random(100, 3))
        Files.write(bad, random(100, 4))
        assertThrows<IOException> { s.put(g, bad) }
        assertEquals(1, count(db, "hosted_file"))
        assertTrue(chunks(db, g.id).isEmpty())
    }

    @Test
    fun removalDeletesTheFileRowFirst(@TempDir dir: Path) {
        val db = db()
        val s = storage(db)
        val store = HostedStore()
        s.load(store)
        val (f, tmp) = content(dir, random(mib + 1))
        f.sources.add("https://a.example/a.png")
        f.origins["world:1:2:3"] = "https://a.example/a.png"
        s.put(f, tmp)
        store.add(f)
        s.save(store)
        assertEquals(1, count(db, "hosted_source"))
        assertEquals(1, count(db, "sign_origin"))

        val log = ArrayList<String>()
        db.trace = { log.add(it) }
        store.remove(f.id)
        s.delete(f)
        assertEquals(listOf("hosted_file", "hosted_chunk", "hosted_source", "sign_origin"),
            log.map { it.removePrefix("DELETE FROM $prefix").substringBefore(' ') })
        for (t in listOf("hosted_file", "hosted_chunk", "hosted_source", "sign_origin")) assertEquals(0, count(db, t), t)
        assertFalse(s.has(f))
    }

    @Test
    fun aFailedDeleteIsRetriedByTheNextSave(@TempDir dir: Path) {
        val db = db()
        val s = storage(db)
        val store = HostedStore()
        s.load(store)
        val (f, tmp) = content(dir, random(10))
        s.put(f, tmp)
        store.add(f)
        s.save(store)
        db.trace = { throw SQLException("down", "HY000") }
        store.remove(f.id)
        assertThrows<IOException> { s.delete(f) }
        db.trace = null
        assertEquals(1, count(db, "hosted_file"))
        val log = ArrayList<String>()
        db.trace = { log.add(it) }
        s.save(store)
        assertTrue(log.first().startsWith("DELETE FROM ${prefix}hosted_file"), log.toString())
        assertEquals(0, count(db, "hosted_file"))
        assertEquals(0, count(db, "hosted_chunk"))
    }

    // ---- 状態の保存と読み込み ----

    @Test
    fun stateRoundTripsAndOnlyChangesAreWritten(@TempDir dir: Path) {
        val name = UUID.randomUUID().toString()
        val db = db(name)
        val s = storage(db)
        val store = HostedStore()
        s.load(store)
        val (f, tmp) = content(dir, random(5000))
        f.sources.add("https://a.example/a.png")
        f.sources.add("https://b.example/same.png")
        f.origins["world:1:2:3"] = "https://a.example/a.png"
        s.put(f, tmp)
        store.add(f)
        store.candidates["https://c.example/c.png"] = 5
        store.failures["https://d.example/d.m3u8"] = HostedStore.Failure(HostingPolicy.Reject.STREAM, 9)
        store.rewrites["world:4:5:6"] = HostedStore.Rewrite("world", 4, 5, 6, "https://b.example/same.png", "https://h.example/${f.fileName}")
        store.denied.add("https://e.example/e.png")
        store.dirty = true
        s.save(store)
        assertFalse(store.dirty)

        // 何も変わっていなければ、何も書かない
        val log = ArrayList<String>()
        db.trace = { log.add(it) }
        s.save(store)
        assertEquals(listOf<String>(), log)
        // 使った時刻はまとめて 1 つのバッチ
        f.lastUsedAt = 999
        s.save(store)
        assertEquals(listOf("UPDATE ${prefix}hosted_file SET last_used_at = ? WHERE id = ?"), log)
        db.trace = null

        val loaded = HostedStore()
        storage(db(name)).load(loaded)
        val lf = loaded.files[f.id]!!
        assertEquals(5000L, lf.size)
        assertEquals(999L, lf.lastUsedAt)
        assertEquals(f.id, loaded.bySource["https://b.example/same.png"])
        assertEquals("https://a.example/a.png", lf.origins["world:1:2:3"])
        assertEquals(5L, loaded.candidates["https://c.example/c.png"])
        assertEquals(HostingPolicy.Reject.STREAM, loaded.failures["https://d.example/d.m3u8"]!!.reason)
        assertEquals("https://h.example/${f.fileName}", loaded.rewrites["world:4:5:6"]!!.to)
        assertTrue("https://e.example/e.png" in loaded.denied)
        assertFalse(loaded.dirty)
        val hashes = db.use { c ->
            db.query(c, "SELECT url, url_hash FROM ${prefix}hosted_source") { rs ->
                val m = HashMap<String, String>()
                while (rs.next()) m[rs.getString(1)] = hex(rs.getBytes(2))
                m
            }
        }
        assertEquals(hex(MessageDigest.getInstance("SHA-256").digest("https://a.example/a.png".toByteArray(StandardCharsets.UTF_8))), hashes["https://a.example/a.png"])

        // 元の URL を外すと、その行も消える
        val s2 = storage(db(name))
        val again = HostedStore()
        s2.load(again)
        again.bySource.remove("https://b.example/same.png")
        s2.save(again)
        assertEquals(1, count(db, "hosted_source"))
    }

    @Test
    fun brokenRowsAreSkippedWithTheSameChecksAsHostingJson(@TempDir dir: Path) {
        val name = UUID.randomUUID().toString()
        val db = db(name)
        val s = storage(db)
        val store = HostedStore()
        s.load(store)
        val (f, tmp) = content(dir, random(10))
        s.put(f, tmp)
        store.add(f)
        store.denied.add("https://e.example/e.png")
        store.candidates["https://c.example/c.png"] = 5
        s.save(store)
        db.use { c ->
            db.update(c, "UPDATE ${prefix}state SET json = ? WHERE name = ?", "[\"bad\\nurl\"]", "denied")
            db.update(c, "INSERT INTO ${prefix}hosted_file (id, ext, content_type, size, chunk_size, chunks, sha256, hosted_at, last_used_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                "../../etc/pw", "png", "image/png", 1L, mib, 1, "0".repeat(64), 1L, 1L)
        }
        val loaded = HostedStore()
        storage(db(name)).load(loaded)
        assertEquals(setOf(f.id), loaded.files.keys)
        assertTrue(loaded.denied.isEmpty(), "壊れた行は空から")
        assertEquals(5L, loaded.candidates["https://c.example/c.png"], "ほかの行は読む")
    }

    // ---- storage: files からの取り込み ----

    @Test
    fun filesStorageIsImportedOnceAndHostingJsonIsRenamed(@TempDir dir: Path) {
        val hosted = Files.createDirectories(dir.resolve("hosted"))
        val tmpDir = Files.createDirectories(dir.resolve("tmp"))
        val a = placed(hosted, random(mib + 5, 10), "png")
        val orphan = placed(hosted, random(300, 11), "jpg")
        val (missing, _) = content(tmpDir, random(50, 12))
        // 名前は id の形だが、中身が合わない
        Files.write(hosted.resolve("aaaaaaaaaaaa.png"), random(20, 13))
        Files.writeString(hosted.resolve("notes.txt"), "not hosted")

        val old = HostedStore()
        a.sources.add("https://a.example/a.png")
        a.origins["world:1:2:3"] = "https://a.example/a.png"
        old.add(a)
        missing.sources.add("https://m.example/m.png")
        old.add(missing)
        old.candidates["https://c.example/c.png"] = 7
        old.rewrites["world:1:2:3"] = HostedStore.Rewrite("world", 1, 2, 3, "https://a.example/a.png", "https://h.example/${a.fileName}")
        old.denied.add("https://d.example/d.png")
        val json = dir.resolve("hosting.json")
        old.save(json)

        val name = UUID.randomUUID().toString()
        val db = db(name)
        val store = HostedStore()
        storage(db, json, hosted).load(store)
        assertEquals(setOf(a.id, orphan.id), store.files.keys, "フォルダにあるファイルだけ (hosting.json にないものも)")
        assertEquals(a.id, store.bySource["https://a.example/a.png"])
        assertEquals("https://a.example/a.png", store.files[a.id]!!.origins["world:1:2:3"])
        assertEquals(7L, store.candidates["https://c.example/c.png"])
        assertTrue("https://d.example/d.png" in store.denied)
        assertEquals(100L, store.files[a.id]!!.hostedAt, "hosting.json の時刻を残す")
        assertFalse(Files.exists(json))
        assertTrue(Files.exists(dir.resolve("hosting.json.migrated")))
        assertTrue(Files.exists(hosted.resolve(a.fileName)), "フォルダは消さない")
        assertEquals(2, count(db, "hosted_file"))
        assertEquals(2, chunks(db, a.id).size)

        // データベースから読み直しても同じ。もう一度起動しても、二重に取り込まない
        val again = HostedStore()
        storage(db(name), json, hosted).load(again)
        assertEquals(setOf(a.id, orphan.id), again.files.keys)
        assertEquals(a.id, again.bySource["https://a.example/a.png"])
        assertEquals("https://h.example/${a.fileName}", again.rewrites["world:1:2:3"]!!.to)
        assertEquals(2, count(db, "hosted_file"))
        assertEquals(3, count(db, "hosted_chunk"))

        // 途中で止まって hosting.json が残っていても、同じ結果
        Files.move(dir.resolve("hosting.json.migrated"), json)
        val third = HostedStore()
        storage(db(name), json, hosted).load(third)
        assertEquals(setOf(a.id, orphan.id), third.files.keys)
        assertEquals(2, count(db, "hosted_file"))
        assertEquals(1, count(db, "hosted_source"))
    }

    // ---- 接続しているクライアント ----

    private fun hex(b: ByteArray) = HexFormat.of().formatHex(b)

    @Test
    fun ipHashIsHmacSha256OfTheRawAddressWithIpv4MappedAsIpv4() {
        val secret = "toro-test-secret".toByteArray(StandardCharsets.UTF_8)
        val v4 = byteArrayOf(203.toByte(), 0, 113, 7)
        val mapped = ByteArray(10) + byteArrayOf(-1, -1) + v4
        // 別の実装 (.NET の HMACSHA256) で計算した値
        assertEquals("e1c405fcb31f1d509c674ff3f1d007c9ad5cd4afb5a05f665027a52e469027fd", hex(ClientTracker.ipHash(secret, v4)))
        assertEquals("e1c405fcb31f1d509c674ff3f1d007c9ad5cd4afb5a05f665027a52e469027fd", hex(ClientTracker.ipHash(secret, mapped)))
        assertEquals("dbae9af72afe1e92ba8ee40262375ea3197640da6b14ae2f6eb8ae895e4f195c",
            hex(ClientTracker.ipHash(secret, java.net.InetAddress.getByName("2001:db8::1").address)))
        assertArrayEquals(v4, ClientTracker.normalize(mapped))
        // IPv4 互換 (::a.b.c.d、射影ではない) や、ふつうの IPv6 はそのまま
        val compat = ByteArray(12) + v4
        assertEquals(16, ClientTracker.normalize(compat).size)
        assertEquals(32, ClientTracker.ipHash(secret, compat).size)
    }

    @Test
    fun clientTableTracksJoinQuitHeartbeatAndCleanup() {
        val db = db()
        val t = ClientTracker.ClientTable(db, prefix)
        val a = ByteArray(32) { 1 }
        val b = ByteArray(32) { 2 }
        fun row(h: ByteArray): Pair<Long, Int>? = db.use { c ->
            db.query(c, "SELECT last_seen, online FROM ${prefix}client WHERE ip_hash = ?", h) { rs -> if (rs.next()) rs.getLong(1) to rs.getInt(2) else null }
        }
        val now = 10L * 24 * 3_600_000
        db.use { c ->
            t.seen(c, a, now, online = true)
            t.seen(c, b, now, online = true)
        }
        assertEquals(now to 1, row(a))
        db.use { c -> t.seen(c, a, now + 5, online = false) }
        assertEquals(now + 5 to 0, row(a))
        // 1 分ごと: いる人を記録し、3 分以上記録のないオンラインの行はオフラインに
        db.use { c -> t.heartbeat(c, listOf(a), now + 4 * 60_000) }
        assertEquals(now + 4 * 60_000 to 1, row(a))
        assertEquals(now to 0, row(b), "ログアウトを取りこぼしても、最後に見た時刻は残してオフラインに")
        db.use { c -> t.resetOnline(c) }
        assertEquals(now + 4 * 60_000 to 0, row(a))
        db.use { c -> t.seen(c, a, now + 5 * 60_000, online = true) }
        db.use { c -> t.allOffline(c, now + 6 * 60_000) }
        assertEquals(now + 6 * 60_000 to 0, row(a))
        db.use { c -> t.cleanup(c, now + 24 * 3_600_000 + 1) }
        assertNull(row(b), "1 日より前の行は消す")
        assertEquals(now + 6 * 60_000 to 0, row(a))
    }

    // ---- 設定と接続 ----

    @Test
    fun configHasTheDatabaseKeys() {
        HostingDbTest::class.java.getResourceAsStream("/config.yml").use { input ->
            val yaml = YamlConfiguration.loadConfiguration(InputStreamReader(input!!, StandardCharsets.UTF_8))
            val h = HostingSettings.read(yaml) { throw AssertionError(it) }
            assertEquals("files", h.storage)
            assertEquals("jdbc:mysql://tidb:4000/marumasign", h.dbUrl)
            assertEquals("", h.dbUser, "既定のユーザーはない")
            assertEquals("", h.dbPassword)
            assertEquals("msc_", h.tablePrefix)
            assertTrue(h.clientCheckEnabled)
            assertEquals("", h.clientSecret)
            assertEquals(10, h.clientGraceMinutes)
        }
        HostingDbTest::class.java.getResourceAsStream("/plugin.yml").use { input ->
            val yaml = YamlConfiguration.loadConfiguration(InputStreamReader(input!!, StandardCharsets.UTF_8))
            assertTrue(yaml.getStringList("libraries").any { it.startsWith("com.mysql:mysql-connector-j:") })
        }
        val y = YamlConfiguration()
        y.set("hosting.storage", "database")
        y.set("hosting.database.table-prefix", "bad-prefix;")
        val warnings = ArrayList<String>()
        val h = HostingSettings.read(y) { warnings.add(it) }
        assertTrue(h.usesDatabase)
        assertEquals("msc_", h.tablePrefix)
        assertEquals(1, warnings.size)
    }

    private fun read(vararg values: Pair<String, Any>): HostingSettings {
        val y = YamlConfiguration()
        for ((k, v) in values) y.set(k, v)
        return HostingSettings.read(y) { throw AssertionError(it) }
    }

    @Test
    fun databaseStorageNeedsAUserAndAPassword() {
        // files では使わない
        assertNull(read().databaseCredentialProblem())
        assertNull(read("hosting.storage" to "files").databaseCredentialProblem())

        // 行がないとき、以前の既定 (root と空のパスワード) には戻らない
        val absent = read("hosting.storage" to "database")
        assertEquals("", absent.dbUser)
        assertEquals("", absent.dbPassword)
        assertEquals("hosting.database.user and hosting.database.password not set", absent.databaseCredentialProblem())

        assertEquals("hosting.database.password not set",
            read("hosting.storage" to "database", "hosting.database.user" to "marumasign").databaseCredentialProblem())
        assertEquals("hosting.database.password not set",
            read("hosting.storage" to "database", "hosting.database.user" to "root", "hosting.database.password" to "").databaseCredentialProblem(),
            "root と空のパスワードを書いてあっても、つながない")
        assertEquals("hosting.database.user not set",
            read("hosting.storage" to "database", "hosting.database.password" to "x").databaseCredentialProblem())

        val ok = read("hosting.storage" to "database", "hosting.database.user" to "marumasign", "hosting.database.password" to "x")
        assertNull(ok.databaseCredentialProblem())
        assertFalse(ok.usesRootDatabaseUser)

        // root をパスワードつきで書いてあれば動かす (警告だけ)。問題の説明に値を出さない
        val root = read("hosting.storage" to "database", "hosting.database.user" to "root", "hosting.database.password" to "x")
        assertNull(root.databaseCredentialProblem())
        assertTrue(root.usesRootDatabaseUser)
    }

    @Test
    fun emptyExtraBlockedAddressesAreWarnedAbout() {
        val warning = MarumaSignCompanion.extraBlockedAddressesWarning(AddressDenyList(listOf()))
        assertTrue(warning!!.contains("link-check.extra-blocked-addresses"))
        assertNotNull(MarumaSignCompanion.extraBlockedAddressesWarning(AddressDenyList(listOf("not an address"))), "読めるものがなければ空と同じ")
        assertNull(MarumaSignCompanion.extraBlockedAddressesWarning(AddressDenyList(listOf("192.0.2.5"))))
    }

    @Test
    fun databaseUrlIsSplitForCreateDatabaseAndShownWithoutSecrets() {
        assertEquals("jdbc:mysql://tidb:4000/" to "marumasign", Db.splitDatabase("jdbc:mysql://tidb:4000/marumasign"))
        assertEquals("jdbc:mysql://tidb:4000/?useSSL=false" to "bluemap", Db.splitDatabase("jdbc:mysql://tidb:4000/bluemap?useSSL=false"))
        assertNull(Db.splitDatabase("jdbc:mysql://tidb:4000/"))
        assertNull(Db.splitDatabase("jdbc:mysql://tidb:4000/a`b"))
        assertEquals("jdbc:mysql://tidb:4000/marumasign", Db.displayUrl("jdbc:mysql://u:pw@tidb:4000/marumasign?password=pw"))
    }

    @Test
    fun schemaIsTheAgreedContract() {
        val ddl = DbHostingStorage.schema("msc_")
        assertEquals(6, ddl.size)
        assertTrue(ddl[0].contains("msc_hosted_file (id CHAR(12) PRIMARY KEY"))
        assertTrue(ddl[1].contains("data MEDIUMBLOB NOT NULL, PRIMARY KEY (id, seq)"))
        assertTrue(ddl[5].contains("msc_client (ip_hash BINARY(32) PRIMARY KEY, last_seen BIGINT NOT NULL, online TINYINT NOT NULL)"))
        assertEquals(1048576, DbHostingStorage.CHUNK_SIZE)
    }
}
