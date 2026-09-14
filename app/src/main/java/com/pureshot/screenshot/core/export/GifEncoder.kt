package com.pureshot.screenshot.core.export

import android.graphics.Bitmap
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.min

/**
 * GIF 编码器（Issue15）：GIF89a 动画，可调帧率(delay)与循环播放次数，
 * 每帧自适应 256 色局部调色板 + 标准 LZW 压缩，无第三方重型依赖。
 */
class GifEncoder {

    private var width = -1
    private var height = -1
    private var delayMs = 100
    private var repeat = 0
    private val frames = ArrayList<Pair<ByteArray, ByteArray>>() // indexedPixels to 768B palette

    fun setDelay(ms: Int) { delayMs = max(20, ms) }
    fun setRepeat(n: Int) { repeat = n }

    fun addFrame(bmp: Bitmap) {
        var src = bmp
        if (width < 0) {
            val scale = min(1f, 720f / max(bmp.width, bmp.height))
            if (scale < 1f) {
                src = Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt(), (bmp.height * scale).toInt(), true)
            }
            width = src.width
            height = src.height
        } else if (src.width != width || src.height != height) {
            src = Bitmap.createScaledBitmap(src, width, height, true)
        }
        val n = width * height
        val px = IntArray(n)
        src.getPixels(px, 0, width, 0, 0, width, height)
        if (src !== bmp) src.recycle()

        // 统计颜色桶（5-5-5 量化键）
        val counts = HashMap<Int, LongArray>()
        for (c in px) {
            val key = bucketKey(c)
            val bucket = counts.getOrPut(key) { LongArray(4) }
            bucket[0] = bucket[0] + 1L
            bucket[1] = bucket[1] + (c shr 16 and 0xFF).toLong()
            bucket[2] = bucket[2] + (c shr 8 and 0xFF).toLong()
            bucket[3] = bucket[3] + (c and 0xFF).toLong()
        }
        val top = counts.entries.sortedByDescending { it.value[0] }.take(256)
        val palette = ByteArray(768)
        val palColors = IntArray(256)
        top.forEachIndexed { i, (key, sum) ->
            val cnt = max(1L, sum[0])
            palColors[i] = 0xFF000000.toInt() or
                ((sum[1] / cnt).toInt() shl 16) or ((sum[2] / cnt).toInt() shl 8) or (sum[3] / cnt).toInt()
            palette[i * 3] = (palColors[i] shr 16 and 0xFF).toByte()
            palette[i * 3 + 1] = (palColors[i] shr 8 and 0xFF).toByte()
            palette[i * 3 + 2] = (palColors[i] and 0xFF).toByte()
        }
        // 桶键 → 最近调色板索引 缓存
        val nearest = IntArray(32768) { -1 }
        val indexed = ByteArray(n)
        for (i in 0 until n) {
            val key = bucketKey(px[i])
            var idx = nearest[key]
            if (idx < 0) {
                idx = nearestPalette(px[i], palColors, top.size)
                nearest[key] = idx
            }
            indexed[i] = idx.toByte()
        }
        frames.add(indexed to palette)
    }

    private fun bucketKey(c: Int): Int =
        ((c shr 16 and 0xFF) shr 3 shl 10) or ((c shr 8 and 0xFF) shr 3 shl 5) or ((c and 0xFF) shr 3)

    private fun nearestPalette(c: Int, pal: IntArray, size: Int): Int {
        var best = 0
        var bestD = Long.MAX_VALUE
        val r = c shr 16 and 0xFF
        val g = c shr 8 and 0xFF
        val b = c and 0xFF
        for (i in 0 until size) {
            val pc = pal[i]
            val dr = (r - (pc shr 16 and 0xFF)).toLong()
            val dg = (g - (pc shr 8 and 0xFF)).toLong()
            val db = (b - (pc and 0xFF)).toLong()
            val d = dr * dr + dg * dg + db * db
            if (d < bestD) { bestD = d; best = i }
        }
        return best
    }

    fun finish(): ByteArray {
        check(frames.isNotEmpty()) { "无帧数据" }
        val out = ByteArrayOutputStream()
        out.write("GIF89a".toByteArray())
        out.write(shortLE(width)); out.write(shortLE(height))
        out.write(0); out.write(0); out.write(0) // 无全局色表
        // NETSCAPE 循环扩展
        out.write(0x21); out.write(0xFF); out.write(0x0B)
        out.write("NETSCAPE2.0".toByteArray())
        out.write(0x03); out.write(0x01)
        out.write(shortLE(repeat))
        out.write(0x00)
        for ((indexed, palette) in frames) {
            // 图形控制扩展
            out.write(0x21); out.write(0xF9); out.write(0x04)
            out.write(0x04)
            out.write(shortLE(delayMs / 10))
            out.write(0x00); out.write(0x00)
            // 图像描述符 + 局部调色板
            out.write(0x2C)
            out.write(shortLE(0)); out.write(shortLE(0))
            out.write(shortLE(width)); out.write(shortLE(height))
            out.write(0x87)
            out.write(palette)
            lzwEncode(indexed, 8, out)
        }
        out.write(0x3B)
        return out.toByteArray()
    }

    private fun shortLE(v: Int) = byteArrayOf((v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte())

    // ---------- GIF LZW ----------
    private fun lzwEncode(indices: ByteArray, minCodeSize: Int, out: ByteArrayOutputStream) {
        out.write(minCodeSize)
        val bits = BitBlockWriter(out)
        val clear = 1 shl minCodeSize
        val eoi = clear + 1
        var codeSize = minCodeSize + 1
        var maxCode = (1 shl codeSize) - 1
        var freeEnt = eoi + 1
        val dict = HashMap<Int, Int>(4096)
        bits.writeCode(clear, codeSize)
        var prefix = indices[0].toInt() and 0xFF
        for (i in 1 until indices.size) {
            val k = indices[i].toInt() and 0xFF
            val key = prefix * 256 + k
            val hit = dict[key]
            if (hit != null) {
                prefix = hit
            } else {
                bits.writeCode(prefix, codeSize)
                dict[key] = freeEnt++
                if (freeEnt > maxCode) {
                    if (codeSize < 12) {
                        codeSize++
                        maxCode = (1 shl codeSize) - 1
                    } else {
                        bits.writeCode(clear, codeSize)
                        dict.clear()
                        freeEnt = eoi + 1
                        codeSize = minCodeSize + 1
                        maxCode = (1 shl codeSize) - 1
                    }
                }
                prefix = k
            }
        }
        bits.writeCode(prefix, codeSize)
        bits.writeCode(eoi, codeSize)
        bits.finish()
    }

    private class BitBlockWriter(val out: ByteArrayOutputStream) {
        private var cur = 0
        private var nbits = 0
        private val block = ByteArray(255)
        private var bpos = 0

        fun writeCode(code: Int, size: Int) {
            cur = cur or (code shl nbits)
            nbits += size
            while (nbits >= 8) {
                block[bpos++] = (cur and 0xFF).toByte()
                cur = cur ushr 8
                nbits -= 8
                if (bpos == 255) flushBlock()
            }
        }

        fun finish() {
            if (nbits > 0) {
                block[bpos++] = (cur and 0xFF).toByte()
                cur = 0; nbits = 0
            }
            if (bpos > 0) flushBlock()
            out.write(0)
        }

        private fun flushBlock() {
            if (bpos == 0) return
            out.write(bpos)
            out.write(block, 0, bpos)
            bpos = 0
        }
    }
}
