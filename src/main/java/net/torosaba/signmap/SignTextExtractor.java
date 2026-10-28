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

    /** 看板 1 枚分 (表と裏の文字をつないだもの) */
    public record RawSign(int x, int y, int z, String text) {
    }

    private SignTextExtractor() {
    }

    public static List<RawSign> extract(byte[] data) {
        final List<RawSign> result = new ArrayList<>();
        final ByteBuffer buf = ByteBuffer.wrap(data);
        if (buf.remaining() < 3 || buf.get() != COMPOUND) return result;
        skipString(buf); // ルートの名前
        readRoot(buf, result);
        return result;
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
