package net.torosaba.marumasign.companion

import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/** デバッグ用: region ファイルの 1 つのチャンクの NBT の構造を出力する */
object NbtDump {

    @JvmStatic
    fun main(args: Array<String>) {
        val file = Path.of(args[0])
        val index = Integer.parseInt(args[1])
        val maxDepth = Integer.parseInt(args[2])
        FileChannel.open(file, StandardOpenOption.READ).use { ch ->
            val h = ByteBuffer.allocate(4096)
            ch.read(h, 0)
            h.flip()
            val entry = h.getInt(index * 4)
            val start = (entry ushr 8).toLong() * 4096
            val head = ByteBuffer.allocate(5)
            ch.read(head, start)
            head.flip()
            val len = head.getInt()
            val type = head.get().toInt() and 0x7F
            val d = ByteBuffer.allocate(len - 1)
            ch.read(d, start + 5)
            val raw = RegionReader.decompress(d.array(), type)
            println("compression=" + type + " bytes=" + raw.size)
            val b = ByteBuffer.wrap(raw)
            val t = b.get().toInt()
            println("root type " + t + " name=" + str(b))
            dumpCompound(b, 1, maxDepth)
        }
    }

    internal fun str(b: ByteBuffer): String {
        val n = b.getShort().toInt() and 0xFFFF
        val s = String(b.array(), b.position(), n, StandardCharsets.UTF_8)
        b.position(b.position() + n)
        return s
    }

    internal fun dumpCompound(b: ByteBuffer, depth: Int, maxDepth: Int) {
        while (true) {
            val t = b.get().toInt()
            if (t == 0) return
            val name = str(b)
            println("  ".repeat(depth) + name + " <" + t + ">" + (if (t <= 6 || t == 8) " = " + peek(b, t) else ""))
            value(b, t, depth, maxDepth)
        }
    }

    internal fun peek(b: ByteBuffer, t: Int): String {
        val p = b.position()
        val s = when (t) {
            1 -> "" + b.get()
            2 -> "" + b.getShort()
            3 -> "" + b.getInt()
            4 -> "" + b.getLong()
            5 -> "" + b.getFloat()
            6 -> "" + b.getDouble()
            8 -> {
                val x = str(b)
                if (x.length > 120) x.substring(0, 120) + "..." else x
            }
            else -> ""
        }
        b.position(p)
        return s
    }

    internal fun value(b: ByteBuffer, t: Int, depth: Int, maxDepth: Int) {
        when (t) {
            1 -> b.position(b.position() + 1)
            2 -> b.position(b.position() + 2)
            3, 5 -> b.position(b.position() + 4)
            4, 6 -> b.position(b.position() + 8)
            7 -> { val n = b.getInt(); b.position(b.position() + n) }
            8 -> str(b)
            9 -> {
                val et = b.get().toInt()
                val n = b.getInt()
                println("  ".repeat(depth + 1) + "[list of " + et + " x" + n + "]")
                for (i in 0 until n) {
                    if (et == 10 && depth < maxDepth && i < 3) {
                        println("  ".repeat(depth + 1) + "- item " + i)
                        dumpCompound(b, depth + 2, maxDepth)
                    } else if (et == 8 && depth < maxDepth && i < 6) {
                        println("  ".repeat(depth + 1) + "- " + peek(b, 8))
                        str(b)
                    } else {
                        value(b, et, depth + 1, -1)
                    }
                }
            }
            10 -> {
                if (depth < maxDepth) dumpCompound(b, depth + 1, maxDepth)
                else value10(b)
            }
            11 -> { val n = b.getInt(); b.position(b.position() + n * 4) }
            12 -> { val n = b.getInt(); b.position(b.position() + n * 8) }
            else -> throw IllegalStateException("tag $t")
        }
    }

    internal fun value10(b: ByteBuffer) {
        while (true) {
            val t = b.get().toInt()
            if (t == 0) return
            str(b)
            value(b, t, 99, -1)
        }
    }
}
