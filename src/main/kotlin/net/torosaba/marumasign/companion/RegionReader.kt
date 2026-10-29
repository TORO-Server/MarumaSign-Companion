package net.torosaba.marumasign.companion

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.zip.GZIPInputStream
import java.util.zip.InflaterInputStream

/**
 * region ファイル (.mca) を読み取り専用で走査して、中の看板を取り出す。
 */
object RegionReader {

    private const val SECTOR = 4096
    // 1 つのチャンクを展開した大きさの上限 (壊れたファイルでメモリを使い切らないように)
    private const val MAX_CHUNK_BYTES = 64 * 1024 * 1024

    private val DOT = "\\.".toPattern()

    class Result {
        val signs: MutableList<SignTextExtractor.RawSign> = ArrayList()
        var chunks = 0
        var errors = 0
        var firstError: Exception? = null
    }

    /** region ファイル名 (r.X.Z.mca) から region 座標を取る。形式が違えば null */
    fun parseRegionName(fileName: String): IntArray? {
        if (!fileName.startsWith("r.") || !fileName.endsWith(".mca")) return null
        val p = DOT.split(fileName.substring(2, fileName.length - 4))
        if (p.size != 2) return null
        return try {
            intArrayOf(Integer.parseInt(p[0]), Integer.parseInt(p[1]))
        } catch (e: NumberFormatException) {
            null
        }
    }

    fun scan(file: Path): Result {
        val result = Result()
        FileChannel.open(file, StandardOpenOption.READ).use { ch ->
            val size = ch.size()
            if (size < 2 * SECTOR) return result
            val header = ByteBuffer.allocate(SECTOR)
            readFully(ch, header, 0)
            header.flip()
            val offsets = IntArray(1024)
            for (i in 0 until 1024) offsets[i] = header.getInt()
            val dir = file.parent
            for (i in 0 until 1024) {
                val entry = offsets[i]
                if (entry == 0) continue
                val start = (entry ushr 8).toLong() * SECTOR
                val sectors = entry and 0xFF
                if (start < 2L * SECTOR || start + 5 > size) continue
                result.chunks++
                try {
                    val raw = readChunk(ch, start, Math.min(sectors * SECTOR.toLong(), size - start), dir, file, i) ?: continue
                    result.signs.addAll(SignTextExtractor.extract(raw))
                } catch (e: Exception) {
                    if (result.errors++ == 0) result.firstError = e
                }
            }
        }
        return result
    }

    private fun readChunk(ch: FileChannel, start: Long, span: Long, dir: Path, file: Path, index: Int): ByteArray? {
        val head = ByteBuffer.allocate(5)
        readFully(ch, head, start)
        head.flip()
        val length = head.getInt()
        var type = head.get().toInt() and 0xFF
        if (length <= 1) return null
        val external = (type and 0x80) != 0
        type = type and 0x7F
        val payload: ByteArray
        if (external) {
            val rc = parseRegionName(file.fileName.toString()) ?: return null
            val cx = rc[0] * 32 + (index and 31)
            val cz = rc[1] * 32 + (index shr 5)
            val mcc = dir.resolve("c.$cx.$cz.mcc")
            if (!Files.isRegularFile(mcc) || Files.size(mcc) > MAX_CHUNK_BYTES) return null
            payload = Files.readAllBytes(mcc)
        } else {
            val dataLength = length - 1
            if (dataLength > span - 5 || dataLength > MAX_CHUNK_BYTES) return null
            val data = ByteBuffer.allocate(dataLength)
            readFully(ch, data, start + 5)
            payload = data.array()
        }
        return decompress(payload, type)
    }

    internal fun decompress(payload: ByteArray, type: Int): ByteArray {
        val input: InputStream = when (type) {
            1 -> GZIPInputStream(ByteArrayInputStream(payload))
            2 -> InflaterInputStream(ByteArrayInputStream(payload))
            3 -> return payload
            else -> throw IOException("Unsupported chunk compression $type")
        }
        input.use { inp ->
            val out = ByteArrayOutputStream(Math.max(1024, payload.size * 3))
            val buf = ByteArray(16384)
            while (true) {
                val n = inp.read(buf)
                if (n <= 0) break
                if (out.size() + n > MAX_CHUNK_BYTES) throw IOException("Chunk too large")
                out.write(buf, 0, n)
            }
            return out.toByteArray()
        }
    }

    private fun readFully(ch: FileChannel, buf: ByteBuffer, position: Long) {
        var pos = position
        while (buf.hasRemaining()) {
            val n = ch.read(buf, pos)
            if (n < 0) throw IOException("Unexpected end of file")
            pos += n
        }
    }
}
