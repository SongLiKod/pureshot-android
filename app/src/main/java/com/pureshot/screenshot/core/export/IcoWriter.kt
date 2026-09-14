package com.pureshot.screenshot.core.export

import android.graphics.Bitmap
import java.io.ByteArrayOutputStream

/**
 * ICO 多尺寸图标导出（Issue15）：支持 16/32/48/64px 标准图标尺寸。
 * 采用 PNG 压缩条目（Vista+ 标准 ICO 兼容写法）。
 */
object IcoWriter {

    val STANDARD_SIZES = listOf(16, 32, 48, 64)

    fun write(src: Bitmap, sizes: List<Int>): ByteArray {
        val out = ByteArrayOutputStream()
        val images = sizes.distinct().sorted().map { size ->
            size to resizeSquare(src, size)
        }
        // ICONDIR
        out.write(shortLE(0)); out.write(shortLE(1)); out.write(shortLE(images.size))
        var offset = 6 + 16 * images.size
        val pngs = images.map { (size, bmp) ->
            val baos = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.PNG, 100, baos)
            size to baos.toByteArray()
        }
        for ((size, data) in pngs) {
            val w = if (size >= 256) 0 else size
            val h = if (size >= 256) 0 else size
            out.write(w); out.write(h)
            out.write(0); out.write(0)
            out.write(shortLE(1))
            out.write(shortLE(32))
            out.write(intLE(data.size))
            out.write(intLE(offset))
            offset += data.size
        }
        for ((_, data) in pngs) out.write(data)
        return out.toByteArray()
    }

    private fun resizeSquare(src: Bitmap, size: Int): Bitmap {
        val side = minOf(src.width, src.height)
        val x = (src.width - side) / 2
        val y = (src.height - side) / 2
        val sq = Bitmap.createBitmap(src, x, y, side, side)
        val out = Bitmap.createScaledBitmap(sq, size, size, true)
        if (out !== sq) sq.recycle()
        return out
    }

    private fun shortLE(v: Int) = byteArrayOf((v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte())
    private fun intLE(v: Int) = byteArrayOf(
        (v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte(),
        ((v shr 16) and 0xFF).toByte(), ((v shr 24) and 0xFF).toByte()
    )
}
