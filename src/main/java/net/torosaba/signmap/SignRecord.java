package net.torosaba.signmap;

import java.util.Arrays;

/**
 * マップに出す候補の看板 (MarumaSign の形式で、URL は看板に書かれたまま)。
 * values は看板に書かれた数値: X, Y, Z, 横幅, 高さ, Z軸回転, Y軸回転, X軸回転。orient は {@link Orient} の符号。
 */
public record SignRecord(int x, int y, int z, String url, float[] values, int orient) {

    public static final int VALUE_COUNT = 8;
    private static final float[] DEFAULT_VALUES = {0, 0, 0, 1, 1, 0, 0, 0};

    public SignRecord {
        // キャッシュなど外から来た値も、MarumaSign 本体と同じ範囲に収める (有限で ±1000 まで)
        if (values == null || values.length != VALUE_COUNT) {
            values = DEFAULT_VALUES;
        } else {
            final float[] safe = new float[VALUE_COUNT];
            for (int i = 0; i < VALUE_COUNT; i++) {
                final float v = values[i];
                safe[i] = Float.isNaN(v) || Float.isInfinite(v) ? DEFAULT_VALUES[i] : Math.max(-MarumaSpec.MAX_VALUE, Math.min(MarumaSpec.MAX_VALUE, v));
            }
            values = safe;
        }
        if (!Orient.valid(orient)) orient = Orient.NONE;
    }

    public SignRecord(int x, int y, int z, String url) {
        this(x, y, z, url, DEFAULT_VALUES, Orient.NONE);
    }

    public SignRecord withUrl(String newUrl) {
        return new SignRecord(x, y, z, newUrl, values, orient);
    }

    public long key() {
        return pack(x, y, z);
    }

    public static long pack(int x, int y, int z) {
        // Minecraft の BlockPos と同じ詰め方 (x: 26 ビット、z: 26 ビット、y: 12 ビット)
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFF);
    }

    public int regionX() {
        return x >> 9;
    }

    public int regionZ() {
        return z >> 9;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof SignRecord r && x == r.x && y == r.y && z == r.z && url.equals(r.url)
                && orient == r.orient && Arrays.equals(values, r.values);
    }

    @Override
    public int hashCode() {
        return 31 * (31 * (31 * (31 * x + y) + z) + url.hashCode()) + Arrays.hashCode(values) + orient;
    }

    @Override
    public String toString() {
        return "SignRecord[" + x + "," + y + "," + z + "," + url + ",orient=" + orient + "]";
    }
}
