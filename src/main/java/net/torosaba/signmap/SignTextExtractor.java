package net.torosaba.signmap;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * チャンクの NBT (圧縮をほどいたもの) から、看板の block entity の座標と文字だけを取り出す。
 * 必要なところ以外は読み飛ばし、オブジェクトも作らない (巨大なワールドを走査するため)。
 */
public final class SignTextExtractor {

    private static final int END = 0, BYTE = 1, SHORT = 2, INT = 3, LONG = 4, FLOAT = 5, DOUBLE = 6, BYTE_ARRAY = 7,
            STRING = 8, LIST = 9, COMPOUND = 10, INT_ARRAY = 11, LONG_ARRAY = 12;

    /** 看板 1 枚分 (表と裏の文字をつないだもの)。orient は {@link Orient} の符号 (MarumaSign の形式の看板だけ調べる) */
    public record RawSign(int x, int y, int z, String text, int orient) {
        public RawSign(int x, int y, int z, String text) {
            this(x, y, z, text, Orient.NONE);
        }
    }

    private SignTextExtractor() {
    }

    public static List<RawSign> extract(byte[] data) {
        final List<RawSign> result = new ArrayList<>();
        final ByteBuffer buf = ByteBuffer.wrap(data);
        if (buf.remaining() < 3 || buf.get() != COMPOUND) return result;
        skipString(buf); // ルートの名前
        readRoot(buf, result);
        if (!result.isEmpty()) addOrientations(data, result);
        return result;
    }

    // ---- 看板の向き (sections の block_states のパレット) ----

    // MarumaSign の形式かもしれない看板 ('|' が 8 つ以上) だけ向きを調べる。ふつうの看板のためにブロックを読まない
    private static boolean maybeMaruma(String text) {
        int n = 0;
        for (int i = 0, len = text.length(); i < len; i++) {
            if (text.charAt(i) == '|' && ++n >= 8) return true;
        }
        return false;
    }

    private static void addOrientations(byte[] data, List<RawSign> signs) {
        // セクション (Y を 16 で割った値) ごとに、調べる看板の位置
        java.util.Map<Integer, List<Integer>> needed = null;
        for (int i = 0; i < signs.size(); i++) {
            final RawSign s = signs.get(i);
            if (!maybeMaruma(s.text())) continue;
            if (needed == null) needed = new java.util.HashMap<>();
            needed.computeIfAbsent(s.y() >> 4, k -> new ArrayList<>()).add(i);
        }
        if (needed == null) return;
        final int[] orient = new int[signs.size()];
        java.util.Arrays.fill(orient, Orient.NONE);
        try {
            final ByteBuffer buf = ByteBuffer.wrap(data);
            buf.get();
            skipString(buf);
            readSections(buf, signs, needed, orient);
        } catch (RuntimeException e) {
            // 読めなければ向きは分からないまま (地図には出る)
        }
        for (int i = 0; i < signs.size(); i++) {
            final RawSign s = signs.get(i);
            if (orient[i] != Orient.NONE) signs.set(i, new RawSign(s.x(), s.y(), s.z(), s.text(), orient[i]));
        }
    }

    private static void readSections(ByteBuffer buf, List<RawSign> signs, java.util.Map<Integer, List<Integer>> needed, int[] out) {
        while (buf.hasRemaining()) {
            final int type = buf.get();
            if (type == END) return;
            final String name = readString(buf);
            if (type == LIST && name.equals("sections")) {
                final int elementType = buf.get();
                final int count = buf.getInt();
                if (elementType != COMPOUND) {
                    for (int i = 0; i < count; i++) skip(buf, elementType);
                    continue;
                }
                for (int i = 0; i < count; i++) readSection(buf, signs, needed, out);
            } else if (type == COMPOUND && name.equals("Level")) {
                readSections(buf, signs, needed, out);
            } else {
                skip(buf, type);
            }
        }
    }

    private static void readSection(ByteBuffer buf, List<RawSign> signs, java.util.Map<Integer, List<Integer>> needed, int[] out) {
        int sectionY = Integer.MIN_VALUE;
        int statesStart = -1;
        while (true) {
            final int type = buf.get();
            if (type == END) break;
            final String name = readString(buf);
            if (name.equals("Y") && type == BYTE) {
                sectionY = buf.get();
            } else if (name.equals("block_states") && type == COMPOUND) {
                statesStart = buf.position();
                skip(buf, type);
            } else {
                skip(buf, type);
            }
        }
        if (statesStart < 0) return;
        final List<Integer> wanted = needed.get(sectionY);
        if (wanted == null) return;
        final int end = buf.position();
        buf.position(statesStart);
        try {
            readPaletteAndData(buf, wanted, signs, out);
        } finally {
            buf.position(end);
        }
    }

    // block_states の中身を読み、wanted の看板の位置のブロックから向きを決める
    private static void readPaletteAndData(ByteBuffer buf, List<Integer> wanted, List<RawSign> signs, int[] out) {
        int[] orients = null;
        long[] longs = null;
        while (true) {
            final int type = buf.get();
            if (type == END) break;
            final String name = readString(buf);
            if (name.equals("palette") && type == LIST) {
                final int elementType = buf.get();
                final int count = buf.getInt();
                if (elementType != COMPOUND) {
                    for (int i = 0; i < count; i++) skip(buf, elementType);
                    continue;
                }
                orients = new int[count];
                for (int i = 0; i < count; i++) orients[i] = readPaletteEntry(buf);
            } else if (name.equals("data") && type == LONG_ARRAY) {
                final int n = buf.getInt();
                if (n < 0 || n > 4096) throw new IllegalArgumentException("Bad block data length");
                longs = new long[n];
                for (int i = 0; i < n; i++) longs[i] = buf.getLong();
            } else {
                skip(buf, type);
            }
        }
        if (orients == null || orients.length == 0) return;
        final int bits = Math.max(4, 32 - Integer.numberOfLeadingZeros(orients.length - 1));
        final int perLong = 64 / bits;
        final long mask = (1L << bits) - 1;
        for (int idx : wanted) {
            final RawSign s = signs.get(idx);
            final int index = (((s.y() & 15) << 4 | (s.z() & 15)) << 4) | (s.x() & 15);
            int p = 0;
            if (orients.length > 1) {
                if (longs == null) continue;
                final int li = index / perLong;
                if (li >= longs.length) continue;
                p = (int) ((longs[li] >>> ((index % perLong) * bits)) & mask);
            }
            if (p < orients.length) out[idx] = orients[p];
        }
    }

    // パレットの 1 つ (Name と Properties) から向きの符号
    private static int readPaletteEntry(ByteBuffer buf) {
        String blockName = null, rotation = null, facing = null;
        boolean modern = false;
        while (true) {
            final int type = buf.get();
            if (type == END) break;
            final String name = readString(buf);
            // 1.18-1.21 は Name / Properties。26.x は id / properties で、プロパティがすべて初期値のブロックは
            // 名前だけを、空の名前のキー ("") に入れて書く
            if ((name.equals("Name") || name.equals("id") || name.isEmpty()) && type == STRING) {
                blockName = readString(buf);
                modern |= !name.equals("Name");
            } else if ((name.equals("Properties") || name.equals("properties")) && type == COMPOUND) {
                while (true) {
                    final int t = buf.get();
                    if (t == END) break;
                    final String key = readString(buf);
                    if (t == STRING) {
                        final String value = readString(buf);
                        if (key.equals("rotation")) rotation = value;
                        else if (key.equals("facing")) facing = value;
                    } else {
                        skip(buf, t);
                    }
                }
            } else {
                skip(buf, type);
            }
        }
        // プロパティがないのは、初期値のブロック。立て看板の rotation の初期値は、26.x では 8 (北)、それ以前は 0
        return Orient.fromState(blockName, rotation, facing, modern ? 8 : 0);
    }

    private static void readRoot(ByteBuffer buf, List<RawSign> out) {
        while (buf.hasRemaining()) {
            final int type = buf.get();
            if (type == END) return;
            final String name = readString(buf);
            if (type == LIST && name.equals("block_entities")) {
                readBlockEntities(buf, out);
            } else if (type == COMPOUND && name.equals("Level")) {
                // 古い形式 (1.17 以前) の入れ物。中に同じ構造がある
                readRoot(buf, out);
            } else if (type == LIST && name.equals("TileEntities")) {
                readBlockEntities(buf, out);
            } else {
                skip(buf, type);
            }
        }
    }

    private static void readBlockEntities(ByteBuffer buf, List<RawSign> out) {
        final int elementType = buf.get();
        final int count = buf.getInt();
        if (elementType != COMPOUND) {
            for (int i = 0; i < count; i++) skip(buf, elementType);
            return;
        }
        for (int i = 0; i < count; i++) {
            final RawSign sign = readBlockEntity(buf);
            if (sign != null) out.add(sign);
        }
    }

    private static RawSign readBlockEntity(ByteBuffer buf) {
        String id = null;
        Integer x = null, y = null, z = null;
        String front = null, back = null;
        // 古い形式 (1.19 以前) の Text1..Text4
        final String[] oldLines = new String[4];
        boolean hasOld = false;
        while (true) {
            final int type = buf.get();
            if (type == END) break;
            final String name = readString(buf);
            switch (name) {
                case "id" -> {
                    if (type == STRING) id = readString(buf);
                    else skip(buf, type);
                }
                case "x" -> x = type == INT ? Integer.valueOf(buf.getInt()) : skipNull(buf, type);
                case "y" -> y = type == INT ? Integer.valueOf(buf.getInt()) : skipNull(buf, type);
                case "z" -> z = type == INT ? Integer.valueOf(buf.getInt()) : skipNull(buf, type);
                case "front_text" -> {
                    if (type == COMPOUND) front = readSide(buf);
                    else skip(buf, type);
                }
                case "back_text" -> {
                    if (type == COMPOUND) back = readSide(buf);
                    else skip(buf, type);
                }
                case "Text1", "Text2", "Text3", "Text4" -> {
                    if (type == STRING) {
                        oldLines[name.charAt(4) - '1'] = componentToText(readString(buf));
                        hasOld = true;
                    } else {
                        skip(buf, type);
                    }
                }
                default -> skip(buf, type);
            }
        }
        if (id == null || x == null || y == null || z == null) return null;
        if (!isSignId(id)) return null;
        final StringBuilder text = new StringBuilder();
        if (front != null) text.append(front);
        if (back != null) text.append(back);
        if (hasOld) {
            for (String line : oldLines) if (line != null) text.append(line);
        }
        return new RawSign(x, y, z, text.toString());
    }

    static boolean isSignId(String id) {
        return switch (id) {
            case "minecraft:sign", "minecraft:hanging_sign", "Sign" -> true;
            default -> false;
        };
    }

    private static Integer skipNull(ByteBuffer buf, int type) {
        skip(buf, type);
        return null;
    }

    // front_text / back_text の messages (4 行) を行順につないだ文字列
    private static String readSide(ByteBuffer buf) {
        final StringBuilder sb = new StringBuilder();
        while (true) {
            final int type = buf.get();
            if (type == END) break;
            final String name = readString(buf);
            if (type == LIST && name.equals("messages")) {
                final int elementType = buf.get();
                final int count = buf.getInt();
                for (int i = 0; i < count; i++) sb.append(readComponent(buf, elementType));
            } else {
                skip(buf, type);
            }
        }
        return sb.toString();
    }

    // 行の文字。26.x では文字列 (JSON の文字列/ただの文字)、コンポーネントの compound、リストのどれもありうる
    private static String readComponent(ByteBuffer buf, int type) {
        switch (type) {
            case STRING:
                return componentToText(readString(buf));
            case COMPOUND: {
                final StringBuilder sb = new StringBuilder();
                readComponentCompound(buf, sb);
                return sb.toString();
            }
            case LIST: {
                final int elementType = buf.get();
                final int count = buf.getInt();
                final StringBuilder sb = new StringBuilder();
                for (int i = 0; i < count; i++) sb.append(readComponent(buf, elementType));
                return sb.toString();
            }
            default:
                skip(buf, type);
                return "";
        }
    }

    private static void readComponentCompound(ByteBuffer buf, StringBuilder sb) {
        String text = "";
        String extra = "";
        while (true) {
            final int type = buf.get();
            if (type == END) break;
            final String name = readString(buf);
            if (name.equals("text") && type == STRING) {
                text = readString(buf);
            } else if (name.equals("extra") && type == LIST) {
                extra = readComponent(buf, LIST);
            } else {
                skip(buf, type);
            }
        }
        sb.append(text).append(extra);
    }

    /** 文字列タグの中身 (JSON の文字列、またはただの文字) を表示される文字にする */
    static String componentToText(String s) {
        if (s == null || s.isEmpty()) return "";
        final char c = s.charAt(0);
        if (c != '{' && c != '[' && c != '"') return s;
        try {
            final StringBuilder sb = new StringBuilder();
            flatten(JsonParser.parseString(s), sb);
            return sb.toString();
        } catch (Exception e) {
            return s;
        }
    }

    private static void flatten(JsonElement e, StringBuilder sb) {
        if (e == null || e.isJsonNull()) return;
        if (e.isJsonPrimitive()) {
            sb.append(e.getAsString());
        } else if (e.isJsonArray()) {
            final JsonArray array = e.getAsJsonArray();
            for (JsonElement item : array) flatten(item, sb);
        } else if (e.isJsonObject()) {
            final JsonObject o = e.getAsJsonObject();
            final JsonElement text = o.get("text");
            if (text != null && text.isJsonPrimitive()) sb.append(text.getAsString());
            final JsonElement extra = o.get("extra");
            if (extra != null) flatten(extra, sb);
        }
    }

    private static String readString(ByteBuffer buf) {
        final int len = buf.getShort() & 0xFFFF;
        final String s = new String(buf.array(), buf.arrayOffset() + buf.position(), len, StandardCharsets.UTF_8);
        buf.position(buf.position() + len);
        return s;
    }

    private static void skipString(ByteBuffer buf) {
        final int len = buf.getShort() & 0xFFFF;
        buf.position(buf.position() + len);
    }

    private static void skipBytes(ByteBuffer buf, int count, int size) {
        if (count < 0) throw new IllegalArgumentException("Negative length");
        buf.position(buf.position() + count * size);
    }

    private static void skip(ByteBuffer buf, int type) {
        switch (type) {
            case END -> {
            }
            case BYTE -> buf.position(buf.position() + 1);
            case SHORT -> buf.position(buf.position() + 2);
            case INT, FLOAT -> buf.position(buf.position() + 4);
            case LONG, DOUBLE -> buf.position(buf.position() + 8);
            case BYTE_ARRAY -> skipBytes(buf, buf.getInt(), 1);
            case STRING -> skipString(buf);
            case LIST -> {
                final int elementType = buf.get();
                final int count = buf.getInt();
                switch (elementType) {
                    case END -> {
                    }
                    case BYTE -> buf.position(buf.position() + count);
                    case SHORT -> buf.position(buf.position() + count * 2);
                    case INT, FLOAT -> buf.position(buf.position() + count * 4);
                    case LONG, DOUBLE -> buf.position(buf.position() + count * 8);
                    default -> {
                        for (int i = 0; i < count; i++) skip(buf, elementType);
                    }
                }
            }
            case COMPOUND -> {
                while (true) {
                    final int t = buf.get();
                    if (t == END) break;
                    skipString(buf);
                    skip(buf, t);
                }
            }
            case INT_ARRAY -> skipBytes(buf, buf.getInt(), 4);
            case LONG_ARRAY -> skipBytes(buf, buf.getInt(), 8);
            default -> throw new IllegalArgumentException("Unknown NBT tag type " + type);
        }
    }
}
