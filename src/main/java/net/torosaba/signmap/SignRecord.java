package net.torosaba.signmap;

/** マップに出す候補の看板 (MarumaSign の形式で、URL は看板に書かれたまま) */
public record SignRecord(int x, int y, int z, String url) {

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
}
