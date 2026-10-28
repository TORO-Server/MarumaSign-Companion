package net.torosaba.signmap;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;

/**
 * region ファイル (.mca) を読み取り専用で走査して、中の看板を取り出す。
 */
public final class RegionReader {

    private static final int SECTOR = 4096;
    // 1 つのチャンクを展開した大きさの上限 (壊れたファイルでメモリを使い切らないように)
    private static final int MAX_CHUNK_BYTES = 64 * 1024 * 1024;

    private RegionReader() {
    }

    public static final class Result {
        public final List<SignTextExtractor.RawSign> signs = new ArrayList<>();
        public int chunks;
        public int errors;
        public Exception firstError;
    }

    /** region ファイル名 (r.X.Z.mca) から region 座標を取る。形式が違えば null */
    public static int[] parseRegionName(String fileName) {
        if (!fileName.startsWith("r.") || !fileName.endsWith(".mca")) return null;
        final String[] p = fileName.substring(2, fileName.length() - 4).split("\\.");
        if (p.length != 2) return null;
        try {
            return new int[]{Integer.parseInt(p[0]), Integer.parseInt(p[1])};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public static Result scan(Path file) throws IOException {
        final Result result = new Result();
        try (FileChannel ch = FileChannel.open(file, StandardOpenOption.READ)) {
            final long size = ch.size();
            if (size < 2 * SECTOR) return result;
            final ByteBuffer header = ByteBuffer.allocate(SECTOR);
            readFully(ch, header, 0);
            header.flip();
            final int[] offsets = new int[1024];
            for (int i = 0; i < 1024; i++) offsets[i] = header.getInt();
            final Path dir = file.getParent();
            for (int i = 0; i < 1024; i++) {
                final int entry = offsets[i];
                if (entry == 0) continue;
                final long start = (long) (entry >>> 8) * SECTOR;
                final int sectors = entry & 0xFF;
                if (start < 2L * SECTOR || start + 5 > size) continue;
                result.chunks++;
                try {
                    final byte[] raw = readChunk(ch, start, Math.min(sectors * (long) SECTOR, size - start), dir, file, i);
                    if (raw == null) continue;
                    result.signs.addAll(SignTextExtractor.extract(raw));
                } catch (Exception e) {
                    if (result.errors++ == 0) result.firstError = e;
                }
            }
        }
        return result;
    }

    private static byte[] readChunk(FileChannel ch, long start, long span, Path dir, Path file, int index) throws IOException {
        final ByteBuffer head = ByteBuffer.allocate(5);
        readFully(ch, head, start);
        head.flip();
        final int length = head.getInt();
        int type = head.get() & 0xFF;
        if (length <= 1) return null;
        final boolean external = (type & 0x80) != 0;
        type &= 0x7F;
        final byte[] payload;
        if (external) {
            final int[] rc = parseRegionName(file.getFileName().toString());
            if (rc == null) return null;
            final int cx = rc[0] * 32 + (index & 31);
            final int cz = rc[1] * 32 + (index >> 5);
            final Path mcc = dir.resolve("c." + cx + "." + cz + ".mcc");
            if (!Files.isRegularFile(mcc) || Files.size(mcc) > MAX_CHUNK_BYTES) return null;
            payload = Files.readAllBytes(mcc);
        } else {
            final int dataLength = length - 1;
            if (dataLength > span - 5 || dataLength > MAX_CHUNK_BYTES) return null;
            final ByteBuffer data = ByteBuffer.allocate(dataLength);
            readFully(ch, data, start + 5);
            payload = data.array();
        }
        return decompress(payload, type);
    }

    static byte[] decompress(byte[] payload, int type) throws IOException {
        final InputStream in;
        switch (type) {
            case 1 -> in = new GZIPInputStream(new ByteArrayInputStream(payload));
            case 2 -> in = new InflaterInputStream(new ByteArrayInputStream(payload));
            case 3 -> {
                return payload;
            }
            default -> throw new IOException("Unsupported chunk compression " + type);
        }
        try (in) {
            final ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(1024, payload.length * 3));
            final byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) {
                if (out.size() + n > MAX_CHUNK_BYTES) throw new IOException("Chunk too large");
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        }
    }

    private static void readFully(FileChannel ch, ByteBuffer buf, long position) throws IOException {
        long pos = position;
        while (buf.hasRemaining()) {
            final int n = ch.read(buf, pos);
            if (n < 0) throw new IOException("Unexpected end of file");
            pos += n;
        }
    }
}
