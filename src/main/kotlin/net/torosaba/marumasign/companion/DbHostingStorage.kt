package net.torosaba.marumasign.companion

import com.google.gson.JsonParser
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.sql.Connection
import java.sql.SQLException
import java.util.HexFormat
import java.util.logging.Logger

/**
 * ホストしたファイルの中身と状態を、データベース (TiDB / MySQL) に置く (hosting.storage: database)。
 * 配るのは別の assets サーバーで、データベースを直接読む (内蔵の Web サーバーは使わない)。表は [schema]。
 *
 * - 中身は 1 MiB ごとの行 (hosted_chunk) に分ける。TiDB は 1 つの書き込みが 6 MB を超えると断るので、1 行ずつ書く。
 * - 新しいファイルは、中身の行をすべて書いてから hosted_file の行を書く。assets サーバーは hosted_file の行があるものだけ配り、
 *   chunks と size を信じるので、中身がそろっていない hosted_file の行は作らない。消すときは hosted_file の行を先に消す。
 * - 状態は [HostedStore] のまま、起動時にデータベースから読み、いつもの間隔で、変わったところだけ書く。
 * - 以前の storage: files の hosting.json とフォルダがあれば、最初に取り込む ([migrate])。
 * - ファイルの持ち主 (hosted_owner)、看板の作者 (sign_author)、監査ログ (hosted_audit) は別の表に置く。assets サーバーが読む表の形は変えない。
 *   hosted_owner の行がないファイル (記録を始める前からあるものなど) は、作者が分からないファイルで、だれの上限にも数えない。
 */
internal class DbHostingStorage(
    private val logger: Logger,
    internal val db: Db,
    prefix: String,
    private val displayUrl: String,
    /** 以前の storage: files の hosting.json とフォルダ (あれば取り込む) */
    private val oldStateFile: Path?,
    private val oldDirectory: Path?,
    private val clock: () -> Long = System::currentTimeMillis,
) : HostingStorage {

    override val kind = HostingSettings.DATABASE

    private val tFile = "${prefix}hosted_file"
    private val tChunk = "${prefix}hosted_chunk"
    private val tSource = "${prefix}hosted_source"
    private val tOrigin = "${prefix}sign_origin"
    private val tState = "${prefix}state"
    private val tOwner = "${prefix}hosted_owner"
    private val tAuthor = "${prefix}sign_author"
    private val tAudit = "${prefix}hosted_audit"

    // データベースにあるもの (最後に読んだ・書いたもの)。save はこれとの差分だけ書く
    private val rows = HashMap<String, Long>()
    private val sources = HashMap<String, String>()
    private val origins = HashMap<String, Pair<String, String>>()
    private val states = HashMap<String, String>()
    private val owners = HashMap<String, String>()
    private val authors = HashMap<String, HostedStore.SignAuthor>()

    // ---- 読み込み ----

    private class Loaded {
        val files = LinkedHashMap<String, HostedStore.HostedFile>()
        val sources = HashMap<String, String>()
        val origins = HashMap<String, Pair<String, String>>()
        val states = HashMap<String, String>()
        val owners = HashMap<String, String>()
        val authors = HashMap<String, HostedStore.SignAuthor>()
        var bad = 0
    }

    @Synchronized
    override fun load(store: HostedStore) {
        val l = try {
            db.use { c -> read(c) }
        } catch (e: SQLException) {
            throw IOException("Could not read the hosting tables: " + Db.describe(e), e)
        }
        rows.clear(); sources.clear(); origins.clear(); states.clear(); owners.clear(); authors.clear()
        for (f in l.files.values) {
            store.add(f)
            rows[f.id] = f.lastUsedAt
        }
        sources.putAll(l.sources)
        origins.putAll(l.origins)
        owners.putAll(l.owners)
        authors.putAll(l.authors)
        store.authors.putAll(l.authors)
        for (name in HostedStore.STATE_NAMES) {
            val json = l.states[name] ?: continue
            try {
                if (json.length > MAX_STATE_CHARS) throw IllegalArgumentException("too large")
                store.readState(name, JsonParser.parseString(json))
                states[name] = json
            } catch (e: RuntimeException) {
                store.clearState(name)
                logger.warning("Hosting: the database row '$name' in $tState is broken (${e.javaClass.simpleName}); starting it empty")
            }
        }
        if (l.bad > 0) logger.warning("Hosting: ignored ${l.bad} broken rows in the hosting tables")
        store.dirty = false
        migrate(store)
    }

    private fun read(c: Connection): Loaded {
        // 消す途中で止まったときの残り (hosted_file の行がない中身・元の URL) を片づける
        db.update(c, "DELETE FROM $tChunk WHERE id NOT IN (SELECT id FROM $tFile)")
        db.update(c, "DELETE FROM $tSource WHERE file_id NOT IN (SELECT id FROM $tFile)")
        db.update(c, "DELETE FROM $tOrigin WHERE file_id NOT IN (SELECT id FROM $tFile)")
        db.update(c, "DELETE FROM $tOwner WHERE file_id NOT IN (SELECT id FROM $tFile)")
        val l = Loaded()
        db.query(c, "SELECT id, ext, size, hosted_at, last_used_at, sha256 FROM $tFile") { rs ->
            while (rs.next()) {
                try {
                    val f = HostedStore.checkedFile(rs.getString(1).trim(), rs.getString(2), rs.getLong(3), rs.getLong(4), rs.getLong(5))
                    f.sha256 = HostedStore.checkSha256(rs.getString(6))
                    l.files[f.id] = f
                } catch (e: RuntimeException) {
                    l.bad++
                }
            }
        }
        db.query(c, "SELECT url, file_id FROM $tSource") { rs ->
            while (rs.next()) {
                val f = l.files[rs.getString(2).trim()] ?: continue
                try {
                    val url = HostedStore.checkUrl(rs.getString(1))
                    f.sources.add(url)
                    l.sources[url] = f.id
                } catch (e: RuntimeException) {
                    l.bad++
                }
            }
        }
        db.query(c, "SELECT sign_key, file_id, original_url FROM $tOrigin") { rs ->
            while (rs.next()) {
                val f = l.files[rs.getString(2).trim()] ?: continue
                try {
                    val key = HostedStore.checkKey(rs.getString(1))
                    val url = HostedStore.checkUrl(rs.getString(3))
                    f.origins[key] = url
                    l.origins[key] = f.id to url
                } catch (e: RuntimeException) {
                    l.bad++
                }
            }
        }
        db.query(c, "SELECT file_id, player_uuid FROM $tOwner") { rs ->
            while (rs.next()) {
                val f = l.files[rs.getString(1).trim()] ?: continue
                val owner = HostedStore.checkOwner(rs.getString(2))
                if (owner == null) {
                    l.bad++
                    continue
                }
                f.owner = owner
                l.owners[f.id] = owner
            }
        }
        db.query(c, "SELECT sign_key, player_uuid, url, written_at FROM $tAuthor") { rs ->
            while (rs.next()) {
                try {
                    val key = HostedStore.checkKey(rs.getString(1))
                    val author = HostedStore.checkedAuthor(rs.getString(2), rs.getString(3), rs.getLong(4))
                    if (author == null) l.bad++ else l.authors[key] = author
                } catch (e: RuntimeException) {
                    l.bad++
                }
            }
        }
        db.query(c, "SELECT name, json FROM $tState") { rs ->
            while (rs.next()) l.states[rs.getString(1)] = rs.getString(2)
        }
        return l
    }

    // ---- 保存 (変わったところだけ) ----

    @Synchronized
    override fun save(store: HostedStore) {
        store.dirty = false
        try {
            db.use { c -> write(c, store) }
        } catch (e: SQLException) {
            store.dirty = true
            throw IOException("Could not save to the database: " + Db.describe(e), e)
        }
    }

    private fun write(c: Connection, store: HostedStore) {
        // ホストをやめたファイル (delete が失敗していたときも、ここでやり直す)
        for (id in rows.keys.filter { !store.files.containsKey(it) }) removeRows(c, id)
        // 使った時刻はまとめて
        val used = store.files.values.filter { f -> rows[f.id].let { it != null && it != f.lastUsedAt } }.map { it.id to it.lastUsedAt }
        db.batch(c, "UPDATE $tFile SET last_used_at = ? WHERE id = ?", used.map { arrayOf<Any?>(it.second, it.first) })
        for ((id, t) in used) rows[id] = t

        val curSources = HashMap<String, String>()
        for ((url, id) in store.bySource) if (id in rows) curSources[url] = id
        val goneSources = sources.keys.filter { it !in curSources }
        db.batch(c, "DELETE FROM $tSource WHERE url_hash = ?", goneSources.map { arrayOf<Any?>(urlHash(it)) })
        goneSources.forEach { sources.remove(it) }
        val newSources = curSources.entries.filter { sources[it.key] != it.value }.map { it.key to it.value }
        db.batch(c, "INSERT INTO $tSource (url_hash, url, file_id) VALUES (?, ?, ?) ON DUPLICATE KEY UPDATE url = VALUES(url), file_id = VALUES(file_id)",
            newSources.map { arrayOf<Any?>(urlHash(it.first), it.first, it.second) })
        for ((url, id) in newSources) sources[url] = id

        val curOrigins = HashMap<String, Pair<String, String>>()
        for (f in store.files.values) if (f.id in rows) for ((key, url) in f.origins) curOrigins[key] = f.id to url
        val goneOrigins = origins.keys.filter { it !in curOrigins }
        db.batch(c, "DELETE FROM $tOrigin WHERE sign_key = ?", goneOrigins.map { arrayOf<Any?>(it) })
        goneOrigins.forEach { origins.remove(it) }
        val newOrigins = curOrigins.entries.filter { origins[it.key] != it.value }.map { it.key to it.value }
        db.batch(c, "INSERT INTO $tOrigin (sign_key, file_id, original_url) VALUES (?, ?, ?) ON DUPLICATE KEY UPDATE file_id = VALUES(file_id), original_url = VALUES(original_url)",
            newOrigins.map { arrayOf<Any?>(it.first, it.second.first, it.second.second) })
        for ((key, v) in newOrigins) origins[key] = v

        val curOwners = HashMap<String, String>()
        for (f in store.files.values) {
            val owner = f.owner
            if (owner != null && f.id in rows) curOwners[f.id] = owner
        }
        val goneOwners = owners.keys.filter { it !in curOwners }
        db.batch(c, "DELETE FROM $tOwner WHERE file_id = ?", goneOwners.map { arrayOf<Any?>(it) })
        goneOwners.forEach { owners.remove(it) }
        val newOwners = curOwners.entries.filter { owners[it.key] != it.value }.map { it.key to it.value }
        db.batch(c, "INSERT INTO $tOwner (file_id, player_uuid) VALUES (?, ?) ON DUPLICATE KEY UPDATE player_uuid = VALUES(player_uuid)",
            newOwners.map { arrayOf<Any?>(it.first, it.second) })
        for ((id, owner) in newOwners) owners[id] = owner

        val goneAuthors = authors.keys.filter { !store.authors.containsKey(it) }
        db.batch(c, "DELETE FROM $tAuthor WHERE sign_key = ?", goneAuthors.map { arrayOf<Any?>(it) })
        goneAuthors.forEach { authors.remove(it) }
        val newAuthors = store.authors.entries.filter { authors[it.key] != it.value }.map { it.key to it.value }
        db.batch(c, "INSERT INTO $tAuthor (sign_key, player_uuid, url, written_at) VALUES (?, ?, ?, ?) ON DUPLICATE KEY UPDATE player_uuid = VALUES(player_uuid), url = VALUES(url), written_at = VALUES(written_at)",
            newAuthors.map { arrayOf<Any?>(it.first, it.second.uuid, it.second.url, it.second.at) })
        for ((key, v) in newAuthors) authors[key] = v

        for (name in HostedStore.STATE_NAMES) {
            val json = store.stateJson(name).toString()
            if (states[name] == json) continue
            db.update(c, "INSERT INTO $tState (name, json) VALUES (?, ?) ON DUPLICATE KEY UPDATE json = VALUES(json)", name, json)
            states[name] = json
        }
    }

    // ---- 中身 ----

    override fun put(file: HostedStore.HostedFile, tmp: Path) {
        try {
            writeFile(file, tmp)
        } catch (e: SQLException) {
            throw IOException("Could not write ${file.fileName} to the database: " + Db.describe(e), e)
        } finally {
            Files.deleteIfExists(tmp)
        }
    }

    /**
     * ファイルの中身を 1 MiB ごとの行に書いてから、hosted_file の行を書く。もう hosted_file の行があれば何もしない
     * (id は中身から作るので、同じ id なら同じ中身。行があるなら中身はそろっている)。
     * 中身が id と合わなければ (取り込むファイルが壊れているなど)、書いた行を消して IOException。
     */
    @Synchronized
    @Throws(IOException::class, SQLException::class)
    internal fun writeFile(f: HostedStore.HostedFile, path: Path) {
        db.use { c ->
            val exists = db.query(c, "SELECT 1 FROM $tFile WHERE id = ?", f.id) { it.next() }
            if (!exists) {
                // 前に途中で止まった残りがあれば消す (hosted_file の行がないので、だれも読んでいない)
                db.update(c, "DELETE FROM $tChunk WHERE id = ?", f.id)
                val digest = MessageDigest.getInstance("SHA-256")
                var seq = 0
                var size = 0L
                Files.newInputStream(path).use { input ->
                    val buf = ByteArray(CHUNK_SIZE)
                    while (true) {
                        val n = input.readNBytes(buf, 0, CHUNK_SIZE)
                        if (n <= 0) break
                        digest.update(buf, 0, n)
                        db.update(c, "INSERT INTO $tChunk (id, seq, data) VALUES (?, ?, ?) ON DUPLICATE KEY UPDATE data = VALUES(data)",
                            f.id, seq, if (n == CHUNK_SIZE) buf else buf.copyOf(n))
                        seq++
                        size += n
                        if (n < CHUNK_SIZE) break
                    }
                }
                // id は中身と「.拡張子」の SHA-256 から作る ([HostFetcher.idOf])
                val idDigest = digest.clone() as MessageDigest
                idDigest.update(".${f.ext}".toByteArray(StandardCharsets.US_ASCII))
                if (HostFetcher.idOf(idDigest.digest()) != f.id || size != f.size) {
                    db.update(c, "DELETE FROM $tChunk WHERE id = ?", f.id)
                    throw IOException("The content does not match ${f.fileName}")
                }
                val sha256 = HexFormat.of().formatHex(digest.digest())
                db.update(c, "INSERT INTO $tFile (id, ext, content_type, size, chunk_size, chunks, sha256, hosted_at, last_used_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    f.id, f.ext, HostingPolicy.contentTypeFor(f.ext), size, CHUNK_SIZE, seq, sha256, f.hostedAt, f.lastUsedAt)
                f.sha256 = sha256
            }
            rows[f.id] = f.lastUsedAt
        }
    }

    override fun has(file: HostedStore.HostedFile): Boolean = synchronized(this) { file.id in rows }

    @Synchronized
    override fun delete(file: HostedStore.HostedFile) {
        try {
            db.use { c -> removeRows(c, file.id) }
        } catch (e: SQLException) {
            // 次の保存でやり直す (rows に残っているので)
            throw IOException("Could not delete ${file.fileName} from the database: " + Db.describe(e), e)
        }
    }

    /** hosted_file の行を先に消してから (assets サーバーがもう配らないように)、中身・元の URL・看板の元の URL・持ち主 */
    private fun removeRows(c: Connection, id: String) {
        db.update(c, "DELETE FROM $tFile WHERE id = ?", id)
        db.update(c, "DELETE FROM $tChunk WHERE id = ?", id)
        db.update(c, "DELETE FROM $tSource WHERE file_id = ?", id)
        db.update(c, "DELETE FROM $tOrigin WHERE file_id = ?", id)
        if (id in owners) db.update(c, "DELETE FROM $tOwner WHERE file_id = ?", id)
        rows.remove(id)
        sources.values.removeIf { it == id }
        origins.values.removeIf { it.first == id }
        owners.remove(id)
    }

    @Synchronized
    override fun audit(entries: List<AuditEntry>) {
        try {
            db.use { c ->
                db.batch(c, "INSERT INTO $tAudit (event_at, kind, file_id, sha256, size, url, sign_key, player_uuid) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                    entries.map { arrayOf<Any?>(it.at, it.kind, it.fileId, it.sha256, it.size, it.url, it.signKey, it.playerUuid) })
            }
        } catch (e: SQLException) {
            throw IOException("Could not write the audit log to the database: " + Db.describe(e), e)
        }
    }

    override fun close() = db.close()

    override fun describe(): List<String> {
        val ok = db.lastOkAt
        val err = db.lastError
        val state = when {
            err != null -> "失敗: $err"
            ok > 0 -> "OK (最後の成功 " + (clock() - ok) / 1000 + " 秒前)"
            else -> "まだつないでいない"
        }
        return listOf("保存先: database ($displayUrl、表 ${tFile.removeSuffix("hosted_file")}*)", "データベース: $state")
    }

    // ---- storage: files からの取り込み ----

    /**
     * 以前の storage: files の hosting.json とフォルダのファイルを取り込む (何度やっても同じ結果)。
     * ファイルは中身の行に、状態は行に入れてから、hosting.json を hosting.json.migrated に名前を変える。フォルダは消さない。
     */
    private fun migrate(store: HostedStore) {
        val state = oldStateFile?.takeIf { Files.isRegularFile(it) }
        // hosting.json.migrated があれば、もう取り込んである (フォルダは毎回は見ない)
        val done = oldStateFile?.resolveSibling("hosting.json.migrated")?.let { Files.exists(it) } == true
        val dir = oldDirectory?.takeIf { Files.isDirectory(it) && (state != null || !done) }
        if (state == null && dir == null) return
        val old = HostedStore()
        if (state != null) {
            try {
                old.load(state)
            } catch (e: IOException) {
                val aside = state.resolveSibling("hosting.json.corrupt-" + clock())
                try {
                    Files.move(state, aside, StandardCopyOption.REPLACE_EXISTING)
                } catch (ignored: IOException) {
                    // 退避できなくても、フォルダのファイルは取り込む
                }
                logger.warning("Hosting: hosting.json was broken; moved it aside and importing only the files in the folder")
            }
        }
        var imported = 0
        var missing = 0
        var skipped = 0
        val now = clock()
        for (f in old.files.values) {
            val existing = store.files[f.id]
            if (existing != null) {
                existing.sources.addAll(f.sources)
                for ((k, v) in f.origins) existing.origins.putIfAbsent(k, v)
                if (existing.owner == null) existing.owner = f.owner
                store.add(existing)
                continue
            }
            val path = dir?.resolve(f.fileName)
            if (path == null || !Files.isRegularFile(path)) {
                missing++
                continue
            }
            val nf = HostedStore.HostedFile(f.id, f.ext, Files.size(path), f.hostedAt, f.lastUsedAt)
            nf.sources.addAll(f.sources)
            nf.origins.putAll(f.origins)
            nf.owner = f.owner
            if (importFile(nf, path, store)) imported++ else skipped++
        }
        // hosting.json にない、フォルダのファイル (看板はもうこのファイルを指しているかもしれない)
        if (dir != null) {
            val paths = Files.list(dir).use { s -> s.toList() }
            for (p in paths) {
                val m = FILE_NAME.matchEntire(p.fileName.toString()) ?: continue
                val id = m.groupValues[1]
                if (store.files.containsKey(id) || old.files.containsKey(id) || !Files.isRegularFile(p)) continue
                if (importFile(HostedStore.HostedFile(id, m.groupValues[2], Files.size(p), now, now), p, store)) imported++ else skipped++
            }
        }
        for ((k, v) in old.candidates) store.candidates.putIfAbsent(k, v)
        for ((k, v) in old.failures) store.failures.putIfAbsent(k, v)
        for ((k, v) in old.rewrites) store.rewrites.putIfAbsent(k, v)
        for ((k, v) in old.authors) store.authors.putIfAbsent(k, v)
        store.denied.addAll(old.denied)
        store.approved.addAll(old.approved)
        if (state == null && imported == 0) return
        store.dirty = true
        save(store)
        if (state != null && Files.isRegularFile(state)) {
            Files.move(state, state.resolveSibling("hosting.json.migrated"), StandardCopyOption.REPLACE_EXISTING)
        }
        val sb = StringBuilder("Hosting: imported $imported files")
        if (state != null) sb.append(" and the state of hosting.json (renamed to hosting.json.migrated)")
        sb.append(" into the database")
        if (missing > 0) sb.append("; $missing files in hosting.json were not in the folder")
        if (skipped > 0) sb.append("; skipped $skipped files whose content did not match their name")
        if (dir != null) sb.append(". The folder $dir was left as it is; delete it when you no longer need it")
        logger.info(sb.toString())
    }

    private fun importFile(f: HostedStore.HostedFile, path: Path, store: HostedStore): Boolean {
        try {
            writeFile(f, path)
        } catch (e: IOException) {
            logger.warning("Hosting: did not import ${f.fileName}: ${e.message?.let { SafeText.clean(it, 120) }}")
            return false
        } catch (e: SQLException) {
            throw IOException("Could not import ${f.fileName} into the database: " + Db.describe(e), e)
        }
        store.add(f)
        return true
    }

    companion object {
        /** 中身の 1 行の大きさ (最後の行だけ短い) */
        const val CHUNK_SIZE = 1024 * 1024
        private const val MAX_STATE_CHARS = 64 * 1024 * 1024
        private val FILE_NAME = Regex("([a-z2-7]{12})\\.([a-z0-9]{1,10})")

        /**
         * 表 (はじめの 6 つは assets サーバーが読むので、形を変えない)。時刻はすべて epoch ミリ秒。
         * あとの 3 つは assets サーバーは読まない。表を足すだけで、これまでの表と行には手を付けない。
         */
        fun schema(prefix: String): List<String> = listOf(
            "CREATE TABLE IF NOT EXISTS ${prefix}hosted_file (id CHAR(12) PRIMARY KEY, ext VARCHAR(10) NOT NULL, content_type VARCHAR(100) NOT NULL, " +
                "size BIGINT NOT NULL, chunk_size INT NOT NULL, chunks INT NOT NULL, sha256 CHAR(64) NOT NULL, hosted_at BIGINT NOT NULL, last_used_at BIGINT NOT NULL)",
            "CREATE TABLE IF NOT EXISTS ${prefix}hosted_chunk (id CHAR(12) NOT NULL, seq INT NOT NULL, data MEDIUMBLOB NOT NULL, PRIMARY KEY (id, seq))",
            "CREATE TABLE IF NOT EXISTS ${prefix}hosted_source (url_hash BINARY(32) PRIMARY KEY, url TEXT NOT NULL, file_id CHAR(12) NOT NULL, KEY (file_id))",
            "CREATE TABLE IF NOT EXISTS ${prefix}sign_origin (sign_key VARCHAR(128) PRIMARY KEY, file_id CHAR(12) NOT NULL, original_url TEXT NOT NULL, KEY (file_id))",
            "CREATE TABLE IF NOT EXISTS ${prefix}state (name VARCHAR(32) PRIMARY KEY, json LONGTEXT NOT NULL)",
            "CREATE TABLE IF NOT EXISTS ${prefix}client (ip_hash BINARY(32) PRIMARY KEY, last_seen BIGINT NOT NULL, online TINYINT NOT NULL)",
            "CREATE TABLE IF NOT EXISTS ${prefix}hosted_owner (file_id CHAR(12) PRIMARY KEY, player_uuid CHAR(36) NOT NULL, KEY (player_uuid))",
            "CREATE TABLE IF NOT EXISTS ${prefix}sign_author (sign_key VARCHAR(128) PRIMARY KEY, player_uuid CHAR(36) NOT NULL, url TEXT NOT NULL, written_at BIGINT NOT NULL, KEY (player_uuid))",
            "CREATE TABLE IF NOT EXISTS ${prefix}hosted_audit (seq BIGINT AUTO_INCREMENT PRIMARY KEY, event_at BIGINT NOT NULL, kind VARCHAR(16) NOT NULL, file_id CHAR(12) NOT NULL, " +
                "sha256 VARCHAR(64) NOT NULL, size BIGINT NOT NULL, url TEXT NOT NULL, sign_key VARCHAR(128) NULL, player_uuid CHAR(36) NULL, KEY (file_id), KEY (player_uuid))",
        )

        /** 設定のデータベースへの接続 (つないだら、表がなければ作る) */
        fun connect(cfg: HostingSettings, logger: Logger): Db =
            Db(cfg.dbUrl, cfg.dbUser, cfg.dbPassword, logger, init = { db, c -> for (sql in schema(cfg.tablePrefix)) db.update(c, sql) })

        /** hosted_source の url_hash: URL の UTF-8 の SHA-256 */
        fun urlHash(url: String): ByteArray = MessageDigest.getInstance("SHA-256").digest(url.toByteArray(StandardCharsets.UTF_8))
    }
}
