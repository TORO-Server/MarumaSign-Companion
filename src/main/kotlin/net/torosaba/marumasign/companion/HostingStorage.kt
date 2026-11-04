package net.torosaba.marumasign.companion

import com.google.gson.JsonObject
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.logging.Logger

/**
 * ホストしたファイルの中身と、ホストの状態 ([HostedStore]) を置く場所。
 * files ([FileHostingStorage]) と database ([DbHostingStorage]) がある。呼ぶのは [HostingManager] のスレッドだけ
 * (止めるときの最後の保存を除く)。
 */
internal interface HostingStorage {
    /** config.yml の hosting.storage の値 */
    val kind: String

    /** 保存してある状態を store に読む。読めないときは IOException (あとでやり直す) */
    @Throws(IOException::class)
    fun load(store: HostedStore)

    /** store の状態を保存する (dirty のとき) */
    @Throws(IOException::class)
    fun save(store: HostedStore)

    /** 新しいファイルの中身を置く。tmp (読み込んだ一時ファイル) は動かすか消す */
    @Throws(IOException::class)
    fun put(file: HostedStore.HostedFile, tmp: Path)

    /** そのファイルの中身があるか */
    fun has(file: HostedStore.HostedFile): Boolean

    /** ホストをやめたファイルの中身を消す */
    @Throws(IOException::class)
    fun delete(file: HostedStore.HostedFile)

    /** 監査ログに足す (足すだけで、消さない・書き換えない)。失敗したら、呼ぶ側があとでやり直す */
    @Throws(IOException::class)
    fun audit(entries: List<AuditEntry>)

    fun close()

    /** /marumasign hosting status に出す行 */
    fun describe(): List<String>
}

/**
 * 監査ログの 1 行: いつ、どのファイル (id と中身の SHA-256、大きさ) を、どの URL から、どの看板のために、だれの分としてホストしたか。
 * signKey は world:x:y:z (看板が決まらないときは null)。playerUuid は作者が分からないときは null。
 */
internal data class AuditEntry(
    val at: Long,
    val kind: String,
    val fileId: String,
    val sha256: String,
    val size: Long,
    val url: String,
    val signKey: String?,
    val playerUuid: String?,
) {
    fun toJson(): String = JsonObject().apply {
        addProperty("at", at)
        addProperty("kind", kind)
        addProperty("fileId", fileId)
        addProperty("sha256", sha256)
        addProperty("size", size)
        addProperty("url", url)
        addProperty("sign", signKey)
        addProperty("player", playerUuid)
    }.toString()

    companion object {
        /** 新しいファイルをホストした */
        const val HOSTED = "hosted"
        /** ホストしているファイルと同じ中身の URL を、そのファイルにまとめた */
        const val SAME_CONTENT = "same-content"
        /** 看板をホスト先に書き換えた */
        const val SIGN_REWRITTEN = "sign-rewritten"
        /** どの看板にも使われなくなって、ホストをやめた */
        const val UNHOSTED = "unhosted"
        /** 管理者が取り下げた */
        const val REMOVED = "removed"
    }
}

/** これまでどおり、ファイルはフォルダに、状態は hosting.json に置く。配るのは内蔵の Web サーバー ([HostingServer]) か外の Web サーバー */
internal class FileHostingStorage(
    private val logger: Logger,
    val directory: Path,
    private val stateFile: Path,
    private val clock: () -> Long = System::currentTimeMillis,
) : HostingStorage {

    override val kind = HostingSettings.FILES

    /** 監査ログ (1 行に 1 つの JSON) */
    private val auditFile = stateFile.resolveSibling("hosting-audit.jsonl")

    override fun load(store: HostedStore) {
        try {
            store.load(stateFile)
        } catch (e: IOException) {
            val aside = stateFile.resolveSibling("hosting.json.corrupt-" + clock())
            try {
                Files.move(stateFile, aside, StandardCopyOption.REPLACE_EXISTING)
            } catch (ignored: IOException) {
                // 退避できなくても、空から始める
            }
            logger.warning("hosting.json was broken; moved it aside and starting empty")
        }
    }

    override fun save(store: HostedStore) = store.save(stateFile)

    override fun put(file: HostedStore.HostedFile, tmp: Path) {
        Files.move(tmp, directory.resolve(file.fileName), StandardCopyOption.REPLACE_EXISTING)
    }

    override fun has(file: HostedStore.HostedFile): Boolean = Files.isRegularFile(directory.resolve(file.fileName))

    override fun delete(file: HostedStore.HostedFile) {
        Files.deleteIfExists(directory.resolve(file.fileName))
    }

    override fun audit(entries: List<AuditEntry>) {
        Files.createDirectories(auditFile.parent)
        Files.newBufferedWriter(auditFile, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND).use { w ->
            for (e in entries) {
                w.write(e.toJson())
                w.newLine()
            }
        }
    }

    override fun close() {}

    override fun describe(): List<String> = listOf("保存先: files (フォルダ $directory、状態 ${stateFile.fileName})")
}
