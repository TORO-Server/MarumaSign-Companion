package net.torosaba.signmap

import com.google.gson.JsonElement
import com.google.gson.JsonParser
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

/**
 * チャンクの NBT (圧縮をほどいたもの) から、看板の block entity の座標と文字だけを取り出す。
 * 必要なところ以外は読み飛ばし、オブジェクトも作らない (巨大なワールドを走査するため)。
 */
object SignTextExtractor {

    private const val END = 0
    private const val BYTE = 1
    private const val SHORT = 2
    private const val INT = 3
    private const val LONG = 4
    private const val FLOAT = 5
    private const val DOUBLE = 6
    private const val BYTE_ARRAY = 7
    private const val STRING = 8
    private const val LIST = 9
    private const val COMPOUND = 10
    private const val INT_ARRAY = 11
    private const val LONG_ARRAY = 12

    /** 看板 1 枚分 (表と裏の文字をつないだもの)。orient は [Orient] の符号 (MarumaSign の形式の看板だけ調べる) */
    data class RawSign(val x: Int, val y: Int, val z: Int, val text: String, val orient: Int = Orient.NONE)

    fun extract(data: ByteArray): List<RawSign> {
        val result = ArrayList<RawSign>()
        val buf = ByteBuffer.wrap(data)
        if (buf.remaining() < 3 || buf.get().toInt() != COMPOUND) return result
        skipString(buf) // ルートの名前
        readRoot(buf, result)
        if (result.isNotEmpty()) addOrientations(data, result)
        return result
    }

    // ---- 看板の向き (sections の block_states のパレット) ----

    // MarumaSign の形式かもしれない看板 ('|' が 8 つ以上) だけ向きを調べる。ふつうの看板のためにブロックを読まない
    private fun maybeMaruma(text: String): Boolean {
        var n = 0
        for (i in 0 until text.length) {
            if (text[i] == '|' && ++n >= 8) return true
        }
        return false
    }

    private fun addOrientations(data: ByteArray, signs: MutableList<RawSign>) {
        // セクション (Y を 16 で割った値) ごとに、調べる看板の位置
        var needed: MutableMap<Int, MutableList<Int>>? = null
        for (i in signs.indices) {
            val s = signs[i]
            if (!maybeMaruma(s.text)) continue
            if (needed == null) needed = HashMap()
            needed.computeIfAbsent(s.y shr 4) { ArrayList() }.add(i)
        }
        if (needed == null) return
        val orient = IntArray(signs.size)
        orient.fill(Orient.NONE)
        try {
            val buf = ByteBuffer.wrap(data)
            buf.get()
            skipString(buf)
            readSections(buf, signs, needed, orient)
        } catch (e: RuntimeException) {
            // 読めなければ向きは分からないまま (地図には出る)
        }
        for (i in signs.indices) {
            val s = signs[i]
            if (orient[i] != Orient.NONE) signs[i] = RawSign(s.x, s.y, s.z, s.text, orient[i])
        }
    }

    private fun readSections(buf: ByteBuffer, signs: List<RawSign>, needed: Map<Int, List<Int>>, out: IntArray) {
        while (buf.hasRemaining()) {
            val type = buf.get().toInt()
            if (type == END) return
            val name = readString(buf)
            if (type == LIST && name == "sections") {
                val elementType = buf.get().toInt()
                val count = buf.getInt()
                if (elementType != COMPOUND) {
                    for (i in 0 until count) skip(buf, elementType)
                    continue
                }
                for (i in 0 until count) readSection(buf, signs, needed, out)
            } else if (type == COMPOUND && name == "Level") {
                readSections(buf, signs, needed, out)
            } else {
                skip(buf, type)
            }
        }
    }

    private fun readSection(buf: ByteBuffer, signs: List<RawSign>, needed: Map<Int, List<Int>>, out: IntArray) {
        var sectionY = Int.MIN_VALUE
        var statesStart = -1
        while (true) {
            val type = buf.get().toInt()
            if (type == END) break
            val name = readString(buf)
            if (name == "Y" && type == BYTE) {
                sectionY = buf.get().toInt()
            } else if (name == "block_states" && type == COMPOUND) {
                statesStart = buf.position()
                skip(buf, type)
            } else {
                skip(buf, type)
            }
        }
        if (statesStart < 0) return
        val wanted = needed[sectionY] ?: return
        val end = buf.position()
        buf.position(statesStart)
        try {
            readPaletteAndData(buf, wanted, signs, out)
        } finally {
            buf.position(end)
        }
    }

    // block_states の中身を読み、wanted の看板の位置のブロックから向きを決める
    private fun readPaletteAndData(buf: ByteBuffer, wanted: List<Int>, signs: List<RawSign>, out: IntArray) {
        var orients: IntArray? = null
        var longs: LongArray? = null
        while (true) {
            val type = buf.get().toInt()
            if (type == END) break
            val name = readString(buf)
            if (name == "palette" && type == LIST) {
                val elementType = buf.get().toInt()
                val count = buf.getInt()
                if (elementType != COMPOUND) {
                    for (i in 0 until count) skip(buf, elementType)
                    continue
                }
                orients = IntArray(count)
                for (i in 0 until count) orients[i] = readPaletteEntry(buf)
            } else if (name == "data" && type == LONG_ARRAY) {
                val n = buf.getInt()
                if (n < 0 || n > 4096) throw IllegalArgumentException("Bad block data length")
                longs = LongArray(n)
                for (i in 0 until n) longs[i] = buf.getLong()
            } else {
                skip(buf, type)
            }
        }
        if (orients == null || orients.isEmpty()) return
        val bits = Math.max(4, 32 - Integer.numberOfLeadingZeros(orients.size - 1))
        val perLong = 64 / bits
        val mask = (1L shl bits) - 1
        for (idx in wanted) {
            val s = signs[idx]
            val index = ((((s.y and 15) shl 4) or (s.z and 15)) shl 4) or (s.x and 15)
            var p = 0
            if (orients.size > 1) {
                if (longs == null) continue
                val li = index / perLong
                if (li >= longs.size) continue
                p = ((longs[li] ushr ((index % perLong) * bits)) and mask).toInt()
            }
            if (p < orients.size) out[idx] = orients[p]
        }
    }

    // パレットの 1 つ (Name と Properties) から向きの符号
    private fun readPaletteEntry(buf: ByteBuffer): Int {
        var blockName: String? = null
        var rotation: String? = null
        var facing: String? = null
        var modern = false
        while (true) {
            val type = buf.get().toInt()
            if (type == END) break
            val name = readString(buf)
            // 1.18-1.21 は Name / Properties。26.x は id / properties で、プロパティがすべて初期値のブロックは
            // 名前だけを、空の名前のキー ("") に入れて書く
            if ((name == "Name" || name == "id" || name.isEmpty()) && type == STRING) {
                blockName = readString(buf)
                modern = modern or (name != "Name")
            } else if ((name == "Properties" || name == "properties") && type == COMPOUND) {
                while (true) {
                    val t = buf.get().toInt()
                    if (t == END) break
                    val key = readString(buf)
                    if (t == STRING) {
                        val value = readString(buf)
                        if (key == "rotation") rotation = value
                        else if (key == "facing") facing = value
                    } else {
                        skip(buf, t)
                    }
                }
            } else {
                skip(buf, type)
            }
        }
        // プロパティがないのは、初期値のブロック。立て看板の rotation の初期値は、26.x では 8 (北)、それ以前は 0
        return Orient.fromState(blockName, rotation, facing, if (modern) 8 else 0)
    }

    private fun readRoot(buf: ByteBuffer, out: MutableList<RawSign>) {
        while (buf.hasRemaining()) {
            val type = buf.get().toInt()
            if (type == END) return
            val name = readString(buf)
            if (type == LIST && name == "block_entities") {
                readBlockEntities(buf, out)
            } else if (type == COMPOUND && name == "Level") {
                // 古い形式 (1.17 以前) の入れ物。中に同じ構造がある
                readRoot(buf, out)
            } else if (type == LIST && name == "TileEntities") {
                readBlockEntities(buf, out)
            } else {
                skip(buf, type)
            }
        }
    }

    private fun readBlockEntities(buf: ByteBuffer, out: MutableList<RawSign>) {
        val elementType = buf.get().toInt()
        val count = buf.getInt()
        if (elementType != COMPOUND) {
            for (i in 0 until count) skip(buf, elementType)
            return
        }
        for (i in 0 until count) {
            val sign = readBlockEntity(buf)
            if (sign != null) out.add(sign)
        }
    }

    private fun readBlockEntity(buf: ByteBuffer): RawSign? {
        var id: String? = null
        var x: Int? = null
        var y: Int? = null
        var z: Int? = null
        var front: String? = null
        var back: String? = null
        // 古い形式 (1.19 以前) の Text1..Text4
        val oldLines = arrayOfNulls<String>(4)
        var hasOld = false
        while (true) {
            val type = buf.get().toInt()
            if (type == END) break
            val name = readString(buf)
            when (name) {
                "id" -> {
                    if (type == STRING) id = readString(buf)
                    else skip(buf, type)
                }
                "x" -> x = if (type == INT) buf.getInt() else skipNull(buf, type)
                "y" -> y = if (type == INT) buf.getInt() else skipNull(buf, type)
                "z" -> z = if (type == INT) buf.getInt() else skipNull(buf, type)
                "front_text" -> {
                    if (type == COMPOUND) front = readSide(buf)
                    else skip(buf, type)
                }
                "back_text" -> {
                    if (type == COMPOUND) back = readSide(buf)
                    else skip(buf, type)
                }
                "Text1", "Text2", "Text3", "Text4" -> {
                    if (type == STRING) {
                        oldLines[name[4] - '1'] = componentToText(readString(buf))
                        hasOld = true
                    } else {
                        skip(buf, type)
                    }
                }
                else -> skip(buf, type)
            }
        }
        if (id == null || x == null || y == null || z == null) return null
        if (!isSignId(id)) return null
        val text = StringBuilder()
        if (front != null) text.append(front)
        if (back != null) text.append(back)
        if (hasOld) {
            for (line in oldLines) if (line != null) text.append(line)
        }
        return RawSign(x, y, z, text.toString())
    }

    internal fun isSignId(id: String): Boolean = when (id) {
        "minecraft:sign", "minecraft:hanging_sign", "Sign" -> true
        else -> false
    }

    private fun skipNull(buf: ByteBuffer, type: Int): Int? {
        skip(buf, type)
        return null
    }

    // front_text / back_text の messages (4 行) を行順につないだ文字列
    private fun readSide(buf: ByteBuffer): String {
        val sb = StringBuilder()
        while (true) {
            val type = buf.get().toInt()
            if (type == END) break
            val name = readString(buf)
            if (type == LIST && name == "messages") {
                val elementType = buf.get().toInt()
                val count = buf.getInt()
                for (i in 0 until count) sb.append(readComponent(buf, elementType))
            } else {
                skip(buf, type)
            }
        }
        return sb.toString()
    }

    // 行の文字。26.x では文字列 (JSON の文字列/ただの文字)、コンポーネントの compound、リストのどれもありうる
    private fun readComponent(buf: ByteBuffer, type: Int): String {
        when (type) {
            STRING -> return componentToText(readString(buf))
            COMPOUND -> {
                val sb = StringBuilder()
                readComponentCompound(buf, sb)
                return sb.toString()
            }
            LIST -> {
                val elementType = buf.get().toInt()
                val count = buf.getInt()
                val sb = StringBuilder()
                for (i in 0 until count) sb.append(readComponent(buf, elementType))
                return sb.toString()
            }
            else -> {
                skip(buf, type)
                return ""
            }
        }
    }

    private fun readComponentCompound(buf: ByteBuffer, sb: StringBuilder) {
        var text = ""
        var extra = ""
        while (true) {
            val type = buf.get().toInt()
            if (type == END) break
            val name = readString(buf)
            if (name == "text" && type == STRING) {
                text = readString(buf)
            } else if (name == "extra" && type == LIST) {
                extra = readComponent(buf, LIST)
            } else {
                skip(buf, type)
            }
        }
        sb.append(text).append(extra)
    }

    /** 文字列タグの中身 (JSON の文字列、またはただの文字) を表示される文字にする */
    internal fun componentToText(s: String?): String {
        if (s == null || s.isEmpty()) return ""
        val c = s[0]
        if (c != '{' && c != '[' && c != '"') return s
        return try {
            val sb = StringBuilder()
            flatten(JsonParser.parseString(s), sb)
            sb.toString()
        } catch (e: Exception) {
            s
        }
    }

    private fun flatten(e: JsonElement?, sb: StringBuilder) {
        if (e == null || e.isJsonNull) return
        if (e.isJsonPrimitive) {
            sb.append(e.asString)
        } else if (e.isJsonArray) {
            val array = e.asJsonArray
            for (item in array) flatten(item, sb)
        } else if (e.isJsonObject) {
            val o = e.asJsonObject
            val text = o.get("text")
            if (text != null && text.isJsonPrimitive) sb.append(text.asString)
            val extra = o.get("extra")
            if (extra != null) flatten(extra, sb)
        }
    }

    private fun readString(buf: ByteBuffer): String {
        val len = buf.getShort().toInt() and 0xFFFF
        val s = String(buf.array(), buf.arrayOffset() + buf.position(), len, StandardCharsets.UTF_8)
        buf.position(buf.position() + len)
        return s
    }

    private fun skipString(buf: ByteBuffer) {
        val len = buf.getShort().toInt() and 0xFFFF
        buf.position(buf.position() + len)
    }

    private fun skipBytes(buf: ByteBuffer, count: Int, size: Int) {
        if (count < 0) throw IllegalArgumentException("Negative length")
        buf.position(buf.position() + count * size)
    }

    private fun skip(buf: ByteBuffer, type: Int) {
        when (type) {
            END -> {
            }
            BYTE -> buf.position(buf.position() + 1)
            SHORT -> buf.position(buf.position() + 2)
            INT, FLOAT -> buf.position(buf.position() + 4)
            LONG, DOUBLE -> buf.position(buf.position() + 8)
            BYTE_ARRAY -> skipBytes(buf, buf.getInt(), 1)
            STRING -> skipString(buf)
            LIST -> {
                val elementType = buf.get().toInt()
                val count = buf.getInt()
                when (elementType) {
                    END -> {
                    }
                    BYTE -> buf.position(buf.position() + count)
                    SHORT -> buf.position(buf.position() + count * 2)
                    INT, FLOAT -> buf.position(buf.position() + count * 4)
                    LONG, DOUBLE -> buf.position(buf.position() + count * 8)
                    else -> {
                        for (i in 0 until count) skip(buf, elementType)
                    }
                }
            }
            COMPOUND -> {
                while (true) {
                    val t = buf.get().toInt()
                    if (t == END) break
                    skipString(buf)
                    skip(buf, t)
                }
            }
            INT_ARRAY -> skipBytes(buf, buf.getInt(), 4)
            LONG_ARRAY -> skipBytes(buf, buf.getInt(), 8)
            else -> throw IllegalArgumentException("Unknown NBT tag type $type")
        }
    }
}
