package net.torosaba.signmap

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream

/** 看板ブロックの向き (チャンクの sections のパレットから読む) */
internal class OrientationTest {

    @Test
    fun fromStateCoversSignKinds() {
        assertEquals(4, Orient.fromState("minecraft:oak_sign", "4", null))
        assertEquals(0, Orient.fromState("minecraft:cherry_sign", "0", null))
        assertEquals(15, Orient.fromState("minecraft:pale_oak_sign", "15", null))
        assertEquals(16, Orient.fromState("minecraft:oak_wall_sign", null, "south"))
        assertEquals(17, Orient.fromState("minecraft:oak_wall_sign", null, "west"))
        assertEquals(18, Orient.fromState("minecraft:bamboo_wall_sign", null, "north"))
        assertEquals(19, Orient.fromState("minecraft:crimson_wall_sign", null, "east"))
        // MarumaSign 本体は吊り看板の画像を描かない
        assertEquals(Orient.NONE, Orient.fromState("minecraft:oak_hanging_sign", "4", null))
        assertEquals(Orient.NONE, Orient.fromState("minecraft:oak_wall_hanging_sign", null, "north"))
        assertEquals(Orient.NONE, Orient.fromState("minecraft:stone", null, null))
        assertEquals(Orient.NONE, Orient.fromState("minecraft:oak_sign", "16", null))
        assertEquals(Orient.NONE, Orient.fromState("minecraft:oak_sign", "x", null))
        assertEquals(Orient.NONE, Orient.fromState("minecraft:oak_wall_sign", null, "up"))
    }

    @Test
    fun yawMatchesMarumaSign() {
        assertEquals(0f, Orient.yaw(0))
        assertEquals(90f, Orient.yaw(4))
        assertEquals(337.5f, Orient.yaw(15))
        assertEquals(0f, Orient.yaw(16))
        assertEquals(90f, Orient.yaw(17))
        assertEquals(180f, Orient.yaw(18))
        assertEquals(270f, Orient.yaw(19))
        assertFalse(Orient.isWall(15))
        assertTrue(Orient.isWall(16))
    }

    @Test
    fun readsStandingSignRotationFromPalette() {
        val signs = SignTextExtractor.extract(chunk(5, 70, 9, SIGN_TEXT, arrayOf(
            arrayOf("minecraft:air"),
            arrayOf("minecraft:oak_sign", "rotation", "4"),
            arrayOf("minecraft:oak_wall_sign", "facing", "east")), 1, 4))
        assertEquals(1, signs.size)
        assertEquals(4, signs[0].orient)
    }

    @Test
    fun readsTheNewPaletteKeysOfVersion26() {
        // 26.x は Name / Properties ではなく id / properties
        val signs = SignTextExtractor.extract(chunk(5, 70, 9, SIGN_TEXT, arrayOf(
            arrayOf("minecraft:air"),
            arrayOf("minecraft:oak_sign", "rotation", "9"),
            arrayOf("minecraft:oak_wall_sign", "facing", "east")), 1, 4, "id", "properties"))
        assertEquals(9, signs[0].orient)
    }

    @Test
    fun defaultStatesHaveNoPropertiesInVersion26() {
        // 26.x は、プロパティがすべて初期値のブロックを、空の名前のキーに名前だけを入れて書く。
        // 立て看板の rotation の初期値は 8 (実際のサーバーで確かめた)、壁掛け看板の facing の初期値は north
        val standing = SignTextExtractor.extract(chunk(5, 70, 9, SIGN_TEXT, arrayOf(
            arrayOf("minecraft:air"), arrayOf("minecraft:oak_sign")), 1, 4, "", "properties"))
        assertEquals(8, standing[0].orient)
        val wall = SignTextExtractor.extract(chunk(5, 70, 9, SIGN_TEXT, arrayOf(
            arrayOf("minecraft:air"), arrayOf("minecraft:oak_wall_sign")), 1, 4, "", "properties"))
        assertEquals(18, wall[0].orient)
        // 古い形式 (Name) で プロパティがなければ、立て看板は rotation 0
        val legacy = SignTextExtractor.extract(chunk(5, 70, 9, SIGN_TEXT, arrayOf(
            arrayOf("minecraft:air"), arrayOf("minecraft:oak_sign")), 1, 4))
        assertEquals(0, legacy[0].orient)
    }

    @Test
    fun readsWallSignFacingFromPalette() {
        val signs = SignTextExtractor.extract(chunk(5, 70, 9, SIGN_TEXT, arrayOf(
            arrayOf("minecraft:air"),
            arrayOf("minecraft:oak_sign", "rotation", "4"),
            arrayOf("minecraft:oak_wall_sign", "facing", "east")), 2, 4))
        assertEquals(19, signs[0].orient)
    }

    @Test
    fun negativeSectionAndSinglePalette() {
        // y = -20 は section Y = -2 (パレット 1 つだと data がなく、全部そのブロック)
        val signs = SignTextExtractor.extract(chunk(-3, -20, -7, SIGN_TEXT, arrayOf(
            arrayOf("minecraft:spruce_wall_sign", "facing", "north")), 0, -2))
        assertEquals(18, signs[0].orient)
    }

    @Test
    fun unknownWhenBlockIsNotASignOrSectionMissing() {
        val notSign = SignTextExtractor.extract(chunk(5, 70, 9, SIGN_TEXT, arrayOf(
            arrayOf("minecraft:air"), arrayOf("minecraft:oak_sign", "rotation", "4")), 0, 4))
        assertEquals(Orient.NONE, notSign[0].orient)
        // 別のセクションしかなければ向きは分からない (看板そのものは残る)
        val otherSection = SignTextExtractor.extract(chunk(5, 70, 9, SIGN_TEXT, arrayOf(
            arrayOf("minecraft:air"), arrayOf("minecraft:oak_sign", "rotation", "4")), 1, 7))
        assertEquals(1, otherSection.size)
        assertEquals(Orient.NONE, otherSection[0].orient)
    }

    @Test
    fun ordinarySignsDoNotNeedBlockLookup() {
        val signs = SignTextExtractor.extract(chunk(5, 70, 9, "hello", arrayOf(
            arrayOf("minecraft:air"), arrayOf("minecraft:oak_sign", "rotation", "4")), 1, 4))
        assertEquals(1, signs.size)
        assertEquals(Orient.NONE, signs[0].orient)
    }

    @Test
    fun largePaletteUsesWiderIndexes() {
        // パレットが 17 個以上だと 5 ビット
        val palette = Array(20) { i -> arrayOf("minecraft:stone_$i") }
        palette[18] = arrayOf("minecraft:oak_sign", "rotation", "9")
        val signs = SignTextExtractor.extract(chunk(5, 70, 9, SIGN_TEXT, palette, 18, 4))
        assertEquals(9, signs[0].orient)
    }

    @Test
    fun recordSanitizesValuesAndOrient() {
        val r = SignRecord(1, 2, 3, "https://a.b/c.png",
            floatArrayOf(Float.NaN, 5000f, -5000f, Float.POSITIVE_INFINITY, 1f, 2f, 3f, 4f), 99)
        assertEquals(0f, r.values[0])
        assertEquals(1000f, r.values[1])
        assertEquals(-1000f, r.values[2])
        assertEquals(1f, r.values[3]) // 無限大は既定値
        assertEquals(Orient.NONE, r.orient)
        assertEquals(8, SignRecord(1, 2, 3, "u", null, 3).values.size)
        assertEquals(listOf(1f, 1f), listOf(SignRecord(0, 0, 0, "u").values[3], SignRecord(0, 0, 0, "u").values[4]))
    }

    companion object {
        private const val SIGN_TEXT = "https://a.b/c.png|0|0|0|1|1|0|0|0"

        // ---- チャンクの NBT を組み立てる ----

        /**
         * 看板 1 つのチャンク。paletteIndex は看板の位置のブロックのパレット番号、sectionY はそのセクションの Y。
         * palette の各要素は {名前, [プロパティ名, 値]...}。
         */
        private fun chunk(x: Int, y: Int, z: Int, text: String, palette: Array<Array<String>>, paletteIndex: Int, sectionY: Int,
                          nameKey: String = "Name", propertiesKey: String = "Properties"): ByteArray {
            val bos = ByteArrayOutputStream()
            val o = DataOutputStream(bos)
            o.writeByte(10)
            o.writeUTF("")
            // sections を先に置く (block_entities が後ろでも読めること)
            o.writeByte(9)
            o.writeUTF("sections")
            o.writeByte(10)
            o.writeInt(2)
            // 空のセクション (別の Y)
            o.writeByte(1)
            o.writeUTF("Y")
            o.writeByte(99)
            o.writeByte(0)
            // 看板のあるセクション。block_states を Y より先に置く
            o.writeByte(10)
            o.writeUTF("block_states")
            o.writeByte(9)
            o.writeUTF("palette")
            o.writeByte(10)
            o.writeInt(palette.size)
            for (entry in palette) {
                o.writeByte(8)
                o.writeUTF(nameKey)
                o.writeUTF(entry[0])
                if (entry.size > 1) {
                    o.writeByte(10)
                    o.writeUTF(propertiesKey)
                    var i = 1
                    while (i + 1 < entry.size) {
                        o.writeByte(8)
                        o.writeUTF(entry[i])
                        o.writeUTF(entry[i + 1])
                        i += 2
                    }
                    o.writeByte(0)
                }
                o.writeByte(0)
            }
            if (palette.size > 1) {
                val bits = Math.max(4, 32 - Integer.numberOfLeadingZeros(palette.size - 1))
                val perLong = 64 / bits
                val data = LongArray((4096 + perLong - 1) / perLong)
                val index = ((((y and 15) shl 4) or (z and 15)) shl 4) or (x and 15)
                data[index / perLong] = data[index / perLong] or (paletteIndex.toLong() shl ((index % perLong) * bits))
                o.writeByte(12)
                o.writeUTF("data")
                o.writeInt(data.size)
                for (l in data) o.writeLong(l)
            }
            o.writeByte(0)
            o.writeByte(1)
            o.writeUTF("Y")
            o.writeByte(sectionY)
            o.writeByte(0)
            // block_entities
            o.writeByte(9)
            o.writeUTF("block_entities")
            o.writeByte(10)
            o.writeInt(1)
            o.writeByte(8)
            o.writeUTF("id")
            o.writeUTF("minecraft:sign")
            o.writeByte(3)
            o.writeUTF("x")
            o.writeInt(x)
            o.writeByte(3)
            o.writeUTF("y")
            o.writeInt(y)
            o.writeByte(3)
            o.writeUTF("z")
            o.writeInt(z)
            o.writeByte(10)
            o.writeUTF("front_text")
            o.writeByte(9)
            o.writeUTF("messages")
            o.writeByte(8)
            o.writeInt(1)
            o.writeUTF(text)
            o.writeByte(0)
            o.writeByte(0)
            o.writeByte(0)
            return bos.toByteArray()
        }
    }
}
