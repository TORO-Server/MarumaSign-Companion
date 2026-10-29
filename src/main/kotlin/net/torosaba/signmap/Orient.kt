package net.torosaba.signmap

/**
 * 看板ブロックの向きの符号。MarumaSign 本体は、立て看板 (rotation 0-15) と壁掛け看板 (facing) の画像だけを描く。
 * - 0..15: 立て看板の rotation (0 が南、1 増えるごとに 22.5 度、西へ回る)
 * - 16..19: 壁掛け看板の向き (16 南、17 西、18 北、19 東)
 * - -1: 向きが分からない、または画像を描かない種類 (吊り看板など)
 */
object Orient {

    const val NONE = -1

    fun isWall(orient: Int): Boolean = orient >= 16

    fun valid(orient: Int): Boolean = orient >= 0 && orient < 20

    /** MarumaSign 本体と同じ「看板の向き」(度) */
    fun yaw(orient: Int): Float = if (orient >= 16) (orient - 16) * 90f else orient * 22.5f

    /**
     * ブロックの名前と状態 (rotation, facing) から符号を作る。画像を描かない種類なら NONE。状態がなければ初期値 (立て看板は 0)
     * @param defaultRotation 立て看板で rotation の指定がないとき (初期値のブロック) の rotation。
     *                        壁掛け看板の facing の初期値は north
     */
    fun fromState(blockName: String?, rotation: String?, facing: String?, defaultRotation: Int = 0): Int {
        if (blockName == null) return NONE
        var name: String = blockName
        val colon = name.indexOf(':')
        if (colon >= 0) name = name.substring(colon + 1)
        if (name.contains("hanging")) return NONE
        if (name.endsWith("_wall_sign") || name == "wall_sign") {
            val f = facingIndex(facing ?: "north")
            return if (f < 0) NONE else 16 + f
        }
        if (name.endsWith("_sign") || name == "sign") {
            if (rotation == null) return defaultRotation
            return try {
                val r = Integer.parseInt(rotation.trim { it <= ' ' })
                if (r >= 0 && r < 16) r else NONE
            } catch (e: NumberFormatException) {
                NONE
            }
        }
        return NONE
    }

    /** 壁掛け看板の向き。Direction.toYRot() の順 (南 0、西 90、北 180、東 270) の番号 */
    fun facingIndex(facing: String?): Int {
        if (facing == null) return -1
        return when (facing) {
            "south" -> 0
            "west" -> 1
            "north" -> 2
            "east" -> 3
            else -> -1
        }
    }
}
