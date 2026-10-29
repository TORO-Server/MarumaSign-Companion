package net.torosaba.signmap;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 看板ブロックの向き (チャンクの sections のパレットから読む) */
class OrientationTest {

    private static final String SIGN_TEXT = "https://a.b/c.png|0|0|0|1|1|0|0|0";

    @Test
    void fromStateCoversSignKinds() {
        assertEquals(4, Orient.fromState("minecraft:oak_sign", "4", null));
        assertEquals(0, Orient.fromState("minecraft:cherry_sign", "0", null));
        assertEquals(15, Orient.fromState("minecraft:pale_oak_sign", "15", null));
        assertEquals(16, Orient.fromState("minecraft:oak_wall_sign", null, "south"));
        assertEquals(17, Orient.fromState("minecraft:oak_wall_sign", null, "west"));
        assertEquals(18, Orient.fromState("minecraft:bamboo_wall_sign", null, "north"));
        assertEquals(19, Orient.fromState("minecraft:crimson_wall_sign", null, "east"));
        // MarumaSign 本体は吊り看板の画像を描かない
        assertEquals(Orient.NONE, Orient.fromState("minecraft:oak_hanging_sign", "4", null));
        assertEquals(Orient.NONE, Orient.fromState("minecraft:oak_wall_hanging_sign", null, "north"));
        assertEquals(Orient.NONE, Orient.fromState("minecraft:stone", null, null));
        assertEquals(Orient.NONE, Orient.fromState("minecraft:oak_sign", "16", null));
        assertEquals(Orient.NONE, Orient.fromState("minecraft:oak_sign", "x", null));
        assertEquals(Orient.NONE, Orient.fromState("minecraft:oak_wall_sign", null, "up"));
    }

    @Test
    void yawMatchesMarumaSign() {
        assertEquals(0f, Orient.yaw(0));
        assertEquals(90f, Orient.yaw(4));
        assertEquals(337.5f, Orient.yaw(15));
        assertEquals(0f, Orient.yaw(16));
        assertEquals(90f, Orient.yaw(17));
        assertEquals(180f, Orient.yaw(18));
        assertEquals(270f, Orient.yaw(19));
        assertFalse(Orient.isWall(15));
        assertTrue(Orient.isWall(16));
    }

    @Test
    void readsStandingSignRotationFromPalette() throws IOException {
        final var signs = SignTextExtractor.extract(chunk(5, 70, 9, SIGN_TEXT, new String[][]{
                {"minecraft:air"},
                {"minecraft:oak_sign", "rotation", "4"},
                {"minecraft:oak_wall_sign", "facing", "east"}}, 1, 4));
        assertEquals(1, signs.size());
        assertEquals(4, signs.get(0).orient());
    }

    @Test
    void readsTheNewPaletteKeysOfVersion26() throws IOException {
        // 26.x は Name / Properties ではなく id / properties
        final var signs = SignTextExtractor.extract(chunk(5, 70, 9, SIGN_TEXT, new String[][]{
                {"minecraft:air"},
                {"minecraft:oak_sign", "rotation", "9"},
                {"minecraft:oak_wall_sign", "facing", "east"}}, 1, 4, "id", "properties"));
        assertEquals(9, signs.get(0).orient());
    }

    @Test
    void defaultStatesHaveNoPropertiesInVersion26() throws IOException {
        // 26.x は、プロパティがすべて初期値のブロックを、空の名前のキーに名前だけを入れて書く。
        // 立て看板の rotation の初期値は 8 (実際のサーバーで確かめた)、壁掛け看板の facing の初期値は north
        final var standing = SignTextExtractor.extract(chunk(5, 70, 9, SIGN_TEXT, new String[][]{
                {"minecraft:air"}, {"minecraft:oak_sign"}}, 1, 4, "", "properties"));
        assertEquals(8, standing.get(0).orient());
        final var wall = SignTextExtractor.extract(chunk(5, 70, 9, SIGN_TEXT, new String[][]{
                {"minecraft:air"}, {"minecraft:oak_wall_sign"}}, 1, 4, "", "properties"));
        assertEquals(18, wall.get(0).orient());
        // 古い形式 (Name) で プロパティがなければ、立て看板は rotation 0
        final var legacy = SignTextExtractor.extract(chunk(5, 70, 9, SIGN_TEXT, new String[][]{
                {"minecraft:air"}, {"minecraft:oak_sign"}}, 1, 4));
        assertEquals(0, legacy.get(0).orient());
    }

    @Test
    void readsWallSignFacingFromPalette() throws IOException {
        final var signs = SignTextExtractor.extract(chunk(5, 70, 9, SIGN_TEXT, new String[][]{
                {"minecraft:air"},
                {"minecraft:oak_sign", "rotation", "4"},
                {"minecraft:oak_wall_sign", "facing", "east"}}, 2, 4));
        assertEquals(19, signs.get(0).orient());
    }

    @Test
    void negativeSectionAndSinglePalette() throws IOException {
        // y = -20 は section Y = -2 (パレット 1 つだと data がなく、全部そのブロック)
        final var signs = SignTextExtractor.extract(chunk(-3, -20, -7, SIGN_TEXT, new String[][]{
                {"minecraft:spruce_wall_sign", "facing", "north"}}, 0, -2));
        assertEquals(18, signs.get(0).orient());
    }

    @Test
    void unknownWhenBlockIsNotASignOrSectionMissing() throws IOException {
        final var notSign = SignTextExtractor.extract(chunk(5, 70, 9, SIGN_TEXT, new String[][]{
                {"minecraft:air"}, {"minecraft:oak_sign", "rotation", "4"}}, 0, 4));
        assertEquals(Orient.NONE, notSign.get(0).orient());
        // 別のセクションしかなければ向きは分からない (看板そのものは残る)
        final var otherSection = SignTextExtractor.extract(chunk(5, 70, 9, SIGN_TEXT, new String[][]{
                {"minecraft:air"}, {"minecraft:oak_sign", "rotation", "4"}}, 1, 7));
        assertEquals(1, otherSection.size());
        assertEquals(Orient.NONE, otherSection.get(0).orient());
    }

    @Test
    void ordinarySignsDoNotNeedBlockLookup() throws IOException {
        final var signs = SignTextExtractor.extract(chunk(5, 70, 9, "hello", new String[][]{
                {"minecraft:air"}, {"minecraft:oak_sign", "rotation", "4"}}, 1, 4));
        assertEquals(1, signs.size());
        assertEquals(Orient.NONE, signs.get(0).orient());
    }

    @Test
    void largePaletteUsesWiderIndexes() throws IOException {
        // パレットが 17 個以上だと 5 ビット
        final String[][] palette = new String[20][];
        for (int i = 0; i < 20; i++) palette[i] = new String[]{"minecraft:stone_" + i};
        palette[18] = new String[]{"minecraft:oak_sign", "rotation", "9"};
        final var signs = SignTextExtractor.extract(chunk(5, 70, 9, SIGN_TEXT, palette, 18, 4));
        assertEquals(9, signs.get(0).orient());
    }

    // ---- チャンクの NBT を組み立てる ----

    /**
     * 看板 1 つのチャンク。paletteIndex は看板の位置のブロックのパレット番号、sectionY はそのセクションの Y。
     * palette の各要素は {名前, [プロパティ名, 値]...}。
     */
    private static byte[] chunk(int x, int y, int z, String text, String[][] palette, int paletteIndex, int sectionY) throws IOException {
        return chunk(x, y, z, text, palette, paletteIndex, sectionY, "Name", "Properties");
    }

    private static byte[] chunk(int x, int y, int z, String text, String[][] palette, int paletteIndex, int sectionY,
                                String nameKey, String propertiesKey) throws IOException {
        final ByteArrayOutputStream bos = new ByteArrayOutputStream();
        final DataOutputStream o = new DataOutputStream(bos);
        o.writeByte(10);
        o.writeUTF("");
        // sections を先に置く (block_entities が後ろでも読めること)
        o.writeByte(9);
        o.writeUTF("sections");
        o.writeByte(10);
        o.writeInt(2);
        // 空のセクション (別の Y)
        o.writeByte(1);
        o.writeUTF("Y");
        o.writeByte(99);
        o.writeByte(0);
        // 看板のあるセクション。block_states を Y より先に置く
        o.writeByte(10);
        o.writeUTF("block_states");
        o.writeByte(9);
        o.writeUTF("palette");
        o.writeByte(10);
        o.writeInt(palette.length);
        for (String[] entry : palette) {
            o.writeByte(8);
            o.writeUTF(nameKey);
            o.writeUTF(entry[0]);
            if (entry.length > 1) {
                o.writeByte(10);
                o.writeUTF(propertiesKey);
                for (int i = 1; i + 1 < entry.length; i += 2) {
                    o.writeByte(8);
                    o.writeUTF(entry[i]);
                    o.writeUTF(entry[i + 1]);
                }
                o.writeByte(0);
            }
            o.writeByte(0);
        }
        if (palette.length > 1) {
            final int bits = Math.max(4, 32 - Integer.numberOfLeadingZeros(palette.length - 1));
            final int perLong = 64 / bits;
            final long[] data = new long[(4096 + perLong - 1) / perLong];
            final int index = ((((y & 15) << 4) | (z & 15)) << 4) | (x & 15);
            data[index / perLong] |= ((long) paletteIndex) << ((index % perLong) * bits);
            o.writeByte(12);
            o.writeUTF("data");
            o.writeInt(data.length);
            for (long l : data) o.writeLong(l);
        }
        o.writeByte(0);
        o.writeByte(1);
        o.writeUTF("Y");
        o.writeByte(sectionY);
        o.writeByte(0);
        // block_entities
        o.writeByte(9);
        o.writeUTF("block_entities");
        o.writeByte(10);
        o.writeInt(1);
        o.writeByte(8);
        o.writeUTF("id");
        o.writeUTF("minecraft:sign");
        o.writeByte(3);
        o.writeUTF("x");
        o.writeInt(x);
        o.writeByte(3);
        o.writeUTF("y");
        o.writeInt(y);
        o.writeByte(3);
        o.writeUTF("z");
        o.writeInt(z);
        o.writeByte(10);
        o.writeUTF("front_text");
        o.writeByte(9);
        o.writeUTF("messages");
        o.writeByte(8);
        o.writeInt(1);
        o.writeUTF(text);
        o.writeByte(0);
        o.writeByte(0);
        o.writeByte(0);
        return bos.toByteArray();
    }

    @Test
    void recordSanitizesValuesAndOrient() {
        final SignRecord r = new SignRecord(1, 2, 3, "https://a.b/c.png",
                new float[]{Float.NaN, 5000, -5000, Float.POSITIVE_INFINITY, 1, 2, 3, 4}, 99);
        assertEquals(0f, r.values()[0]);
        assertEquals(1000f, r.values()[1]);
        assertEquals(-1000f, r.values()[2]);
        assertEquals(1f, r.values()[3]); // 無限大は既定値
        assertEquals(Orient.NONE, r.orient());
        assertEquals(8, new SignRecord(1, 2, 3, "u", null, 3).values().length);
        assertEquals(List.of(1f, 1f), List.of(new SignRecord(0, 0, 0, "u").values()[3], new SignRecord(0, 0, 0, "u").values()[4]));
    }
}
