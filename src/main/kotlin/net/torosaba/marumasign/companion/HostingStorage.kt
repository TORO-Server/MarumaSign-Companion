package net.torosaba.marumasign.companion

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
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

    fun close()

    /** /marumasign hosting status に出す行 */
    fun describe(): List<String>
}

/** これまでどおり、ファイルはフォルダに、状態は hosting.json に置く。配るのは内蔵の Web サーバー ([HostingServer]) か外の Web サーバー */
internal class FileHostingStorage(
    private val logger: Logger,
    val directory: Path,
    private val stateFile: Path,
    private val clock: () -> Long = System::currentTimeMillis,
) : HostingStorage {

    override val kind = HostingSettings.FILES

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

    override fun close() {}

    override fun describe(): List<String> = listOf("保存先: files (フォルダ $directory、状態 ${stateFile.fileName})")
}
