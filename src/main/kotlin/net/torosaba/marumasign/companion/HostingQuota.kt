package net.torosaba.marumasign.companion

/**
 * プレイヤーごとの上限 (hosting.quota) の計算。いまホストしているファイルと、読み込んでいる途中の予約から作る (Bukkit に依存しない)。
 *
 * ファイルは、それをホストさせた看板の作者 1 人の分として数える ([HostedStore.HostedFile.owner])。
 * 作者が分からないファイル (記録を始める前からあるものなど) は、だれの分にも数えない (全体の上限には数える)。
 *
 * @param maxFiles 1 人の分のファイルの数の上限。0 なら上限なし
 * @param maxBytes 1 人の分の容量の上限。0 なら上限なし
 */
internal class HostingQuota(
    private val maxFiles: Int,
    private val maxBytes: Long,
    files: Collection<HostedStore.HostedFile>,
    reserved: Collection<Reservation> = listOf(),
) {
    /** 読み込んでいる途中の分。大きさは読み込みの上限いっぱいで数える (同時に読んでも、上限を超えないように) */
    data class Reservation(val owner: String?, val bytes: Long)

    /** だれの分として読むか。owner が null なら、だれの分にも数えない。room はその人の分の残り (バイト) */
    data class Choice(val owner: String?, val room: Long)

    private val usedFiles = HashMap<String, Int>()
    private val usedBytes = HashMap<String, Long>()

    init {
        for (f in files) f.owner?.let { add(it, f.size) }
        for (r in reserved) reserve(r)
    }

    private fun add(owner: String, bytes: Long) {
        usedFiles[owner] = filesOf(owner) + 1
        usedBytes[owner] = bytesOf(owner) + bytes
    }

    /** 読み込みを始めた分を足す */
    fun reserve(r: Reservation) {
        r.owner?.let { add(it, r.bytes) }
    }

    fun filesOf(owner: String): Int = usedFiles[owner] ?: 0

    fun bytesOf(owner: String): Long = usedBytes[owner] ?: 0L

    /** そのプレイヤーの分に、あと何バイト入れられるか。ファイルの数が上限なら 0 */
    fun room(owner: String): Long {
        if (maxFiles > 0 && filesOf(owner) >= maxFiles) return 0
        if (maxBytes <= 0) return Long.MAX_VALUE
        return maxOf(0L, maxBytes - bytesOf(owner))
    }

    /**
     * URL の看板の作者 (分からない看板は null) から、だれの分として読むかを決める。いま読めなければ null。
     * 作者の分からない看板が 1 枚でもあれば、だれの分にも数えない (以前からある看板のホストを止めない)。
     * 全員分かっていれば、早く書いた人から順に、上限に余りがあって available な人の分。
     */
    fun choose(authors: List<HostedStore.SignAuthor?>, available: (String) -> Boolean = { true }): Choice? {
        if (authors.isEmpty() || authors.any { it == null }) return Choice(null, Long.MAX_VALUE)
        for (a in authors.filterNotNull().sortedBy { it.at }) {
            val left = room(a.uuid)
            if (left > 0 && available(a.uuid)) return Choice(a.uuid, left)
        }
        return null
    }
}
