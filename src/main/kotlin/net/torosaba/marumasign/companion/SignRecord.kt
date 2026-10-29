package net.torosaba.marumasign.companion

/**
 * マップに出す候補の看板 (MarumaSign の形式で、URL は看板に書かれたまま)。
 * values は看板に書かれた数値: X, Y, Z, 横幅, 高さ, Z軸回転, Y軸回転, X軸回転。orient は [Orient] の符号。
 */
class SignRecord(val x: Int, val y: Int, val z: Int, val url: String, values: FloatArray?, orient: Int) {

    // キャッシュなど外から来た値も、MarumaSign 本体と同じ範囲に収める (有限で ±1000 まで)
    val values: FloatArray = if (values == null || values.size != VALUE_COUNT) {
        DEFAULT_VALUES
    } else {
        val safe = FloatArray(VALUE_COUNT)
        for (i in 0 until VALUE_COUNT) {
            val v = values[i]
            safe[i] = if (v.isNaN() || v.isInfinite()) DEFAULT_VALUES[i] else Math.max(-MarumaSpec.MAX_VALUE, Math.min(MarumaSpec.MAX_VALUE, v))
        }
        safe
    }

    val orient: Int = if (!Orient.valid(orient)) Orient.NONE else orient

    constructor(x: Int, y: Int, z: Int, url: String) : this(x, y, z, url, DEFAULT_VALUES, Orient.NONE)

    fun withUrl(newUrl: String): SignRecord = SignRecord(x, y, z, newUrl, values, orient)

    fun key(): Long = pack(x, y, z)

    fun regionX(): Int = x shr 9

    fun regionZ(): Int = z shr 9

    override fun equals(other: Any?): Boolean =
        other is SignRecord && x == other.x && y == other.y && z == other.z && url == other.url &&
            orient == other.orient && values.contentEquals(other.values)

    override fun hashCode(): Int = 31 * (31 * (31 * (31 * x + y) + z) + url.hashCode()) + values.contentHashCode() + orient

    override fun toString(): String = "SignRecord[$x,$y,$z,$url,orient=$orient]"

    companion object {
        const val VALUE_COUNT = 8
        private val DEFAULT_VALUES = floatArrayOf(0f, 0f, 0f, 1f, 1f, 0f, 0f, 0f)

        fun pack(x: Int, y: Int, z: Int): Long {
            // Minecraft の BlockPos と同じ詰め方 (x: 26 ビット、z: 26 ビット、y: 12 ビット)
            return ((x and 0x3FFFFFF).toLong() shl 38) or ((z and 0x3FFFFFF).toLong() shl 12) or (y and 0xFFF).toLong()
        }
    }
}
