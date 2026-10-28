package net.torosaba.signmap;

import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/** デバッグ用: region ファイルの 1 つのチャンクの NBT の構造を出力する */
public final class NbtDump {

    public static void main(String[] args) throws Exception {
        final Path file = Path.of(args[0]);
        final int index = Integer.parseInt(args[1]);
        final int maxDepth = Integer.parseInt(args[2]);
        try (FileChannel ch = FileChannel.open(file, StandardOpenOption.READ)) {
            final ByteBuffer h = ByteBuffer.allocate(4096);
            ch.read(h, 0);
            h.flip();
            final int entry = h.getInt(index * 4);
            final long start = (long) (entry >>> 8) * 4096;
            final ByteBuffer head = ByteBuffer.allocate(5);
            ch.read(head, start);
            head.flip();
            final int len = head.getInt();
            final int type = head.get() & 0x7F;
            final ByteBuffer d = ByteBuffer.allocate(len - 1);
            ch.read(d, start + 5);
            final byte[] raw = RegionReader.decompress(d.array(), type);
            System.out.println("compression=" + type + " bytes=" + raw.length);
            final ByteBuffer b = ByteBuffer.wrap(raw);
            final int t = b.get();
            System.out.println("root type " + t + " name=" + str(b));
            dumpCompound(b, 1, maxDepth);
        }
    }

    static String str(ByteBuffer b) {
        final int n = b.getShort() & 0xFFFF;
        final String s = new String(b.array(), b.position(), n, StandardCharsets.UTF_8);
        b.position(b.position() + n);
        return s;
    }

    static void dumpCompound(ByteBuffer b, int depth, int maxDepth) {
        while (true) {
            final int t = b.get();
            if (t == 0) return;
            final String name = str(b);
            System.out.println("  ".repeat(depth) + name + " <" + t + ">" + (t <= 6 || t == 8 ? " = " + peek(b, t) : ""));
            value(b, t, depth, maxDepth);
        }
    }

    static String peek(ByteBuffer b, int t) {
        final int p = b.position();
        final String s = switch (t) {
            case 1 -> "" + b.get();
            case 2 -> "" + b.getShort();
            case 3 -> "" + b.getInt();
            case 4 -> "" + b.getLong();
            case 5 -> "" + b.getFloat();
            case 6 -> "" + b.getDouble();
            case 8 -> {
                final String x = str(b);
                yield x.length() > 120 ? x.substring(0, 120) + "..." : x;
            }
            default -> "";
        };
        b.position(p);
        return s;
    }

    static void value(ByteBuffer b, int t, int depth, int maxDepth) {
        switch (t) {
            case 1 -> b.position(b.position() + 1);
            case 2 -> b.position(b.position() + 2);
            case 3, 5 -> b.position(b.position() + 4);
            case 4, 6 -> b.position(b.position() + 8);
            case 7 -> { final int n = b.getInt(); b.position(b.position() + n); }
            case 8 -> str(b);
            case 9 -> {
                final int et = b.get();
                final int n = b.getInt();
                System.out.println("  ".repeat(depth + 1) + "[list of " + et + " x" + n + "]");
                for (int i = 0; i < n; i++) {
                    if (et == 10 && depth < maxDepth && i < 3) {
                        System.out.println("  ".repeat(depth + 1) + "- item " + i);
                        dumpCompound(b, depth + 2, maxDepth);
                    } else if (et == 8 && depth < maxDepth && i < 6) {
                        System.out.println("  ".repeat(depth + 1) + "- " + peek(b, 8));
                        str(b);
                    } else {
                        value(b, et, depth + 1, -1);
                    }
                }
            }
            case 10 -> {
                if (depth < maxDepth) dumpCompound(b, depth + 1, maxDepth);
                else value10(b);
            }
            case 11 -> { final int n = b.getInt(); b.position(b.position() + n * 4); }
            case 12 -> { final int n = b.getInt(); b.position(b.position() + n * 8); }
            default -> throw new IllegalStateException("tag " + t);
        }
    }

    static void value10(ByteBuffer b) {
        while (true) {
            final int t = b.get();
            if (t == 0) return;
            str(b);
            value(b, t, 99, -1);
        }
    }
}
