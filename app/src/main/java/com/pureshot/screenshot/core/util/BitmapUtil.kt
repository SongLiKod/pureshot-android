package com.pureshot.screenshot.core.util

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * 图片处理工具：像素化马赛克、高斯模糊(多级盒模糊近似)、黑边裁切、采样缩放等。
 * 全部基于原生 Bitmap 处理，无重型图片框架依赖。
 */
object BitmapUtil {

    /** 像素化马赛克：block 为像素块大小 */
    fun pixelate(src: Bitmap, block: Int): Bitmap {
        val w = src.width
        val h = src.height
        val small = Bitmap.createBitmap(max(1, w / block), max(1, h / block), Bitmap.Config.ARGB_8888)
        val p = Paint().apply { isFilterBitmap = true }
        Canvas(small).drawBitmap(src, null, Rect(0, 0, small.width, small.height), p)
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val p2 = Paint().apply {
            isFilterBitmap = false
            isDither = false
        }
        Canvas(out).drawBitmap(small, null, Rect(0, 0, w, h), p2)
        small.recycle()
        return out
    }

    /** 高斯模糊：多级盒模糊近似，radius 越大越模糊 */
    fun blur(src: Bitmap, radius: Int): Bitmap {
        val r = max(1, radius)
        val down = max(1, r / 2)
        val sw = max(1, src.width / down)
        val sh = max(1, src.height / down)
        val scaled = Bitmap.createScaledBitmap(src, sw, sh, true)
        val pixels = IntArray(sw * sh)
        scaled.getPixels(pixels, 0, sw, 0, 0, sw, sh)
        var data = pixels
        repeat(3) {
            data = boxBlurPass(data, sw, sh, max(1, r / down))
            data = boxBlurPassTranspose(data, sw, sh, max(1, r / down))
        }
        scaled.setPixels(data, 0, sw, 0, 0, sw, sh)
        val out = Bitmap.createScaledBitmap(scaled, src.width, src.height, true)
        if (out !== scaled) scaled.recycle()
        return out
    }

    private fun boxBlurPass(px: IntArray, w: Int, h: Int, r: Int): IntArray {
        val out = IntArray(px.size)
        val div = r * 2 + 1
        for (y in 0 until h) {
            val row = y * w
            var sr = 0; var sg = 0; var sb = 0; var sa = 0
            for (i in -r..r) {
                val c = px[row + i.coerceIn(0, w - 1)]
                sa += c ushr 24; sr += (c shr 16) and 0xFF; sg += (c shr 8) and 0xFF; sb += c and 0xFF
            }
            for (x in 0 until w) {
                out[row + x] = (sa / div shl 24) or (sr / div shl 16) or (sg / div shl 8) or (sb / div)
                val add = px[row + (x + r + 1).coerceIn(0, w - 1)]
                val sub = px[row + (x - r).coerceIn(0, w - 1)]
                sa += (add ushr 24) - (sub ushr 24)
                sr += (add shr 16 and 0xFF) - (sub shr 16 and 0xFF)
                sg += (add shr 8 and 0xFF) - (sub shr 8 and 0xFF)
                sb += (add and 0xFF) - (sub and 0xFF)
            }
        }
        return out
    }

    private fun boxBlurPassTranspose(px: IntArray, w: Int, h: Int, r: Int): IntArray {
        val out = IntArray(px.size)
        val div = r * 2 + 1
        for (x in 0 until w) {
            var sr = 0; var sg = 0; var sb = 0; var sa = 0
            for (i in -r..r) {
                val c = px[i.coerceIn(0, h - 1) * w + x]
                sa += c ushr 24; sr += (c shr 16) and 0xFF; sg += (c shr 8) and 0xFF; sb += c and 0xFF
            }
            for (y in 0 until h) {
                out[y * w + x] = (sa / div shl 24) or (sr / div shl 16) or (sg / div shl 8) or (sb / div)
                val add = px[(y + r + 1).coerceIn(0, h - 1) * w + x]
                val sub = px[(y - r).coerceIn(0, h - 1) * w + x]
                sa += (add ushr 24) - (sub ushr 24)
                sr += (add shr 16 and 0xFF) - (sub shr 16 and 0xFF)
                sg += (add shr 8 and 0xFF) - (sub shr 8 and 0xFF)
                sb += (add and 0xFF) - (sub and 0xFF)
            }
        }
        return out
    }

    /** 自动黑边裁切：扫描四周近黑像素带，返回内容区域 */
    fun contentBounds(src: Bitmap, threshold: Int = 24): Rect {
        val w = src.width
        val h = src.height
        val px = IntArray(w)
        fun rowDark(y: Int): Boolean {
            src.getPixels(px, 0, w, 0, y, w, 1)
            var dark = 0
            for (c in px) if (luma(c) <= threshold) dark++
            return dark > w * 0.98
        }
        var top = 0
        while (top < h / 2 && rowDark(top)) top++
        var bottom = h - 1
        while (bottom > h / 2 && rowDark(bottom)) bottom--
        val col = IntArray(max(1, h))
        fun colDark(x: Int): Boolean {
            src.getPixels(col, 0, 1, x, 0, 1, col.size)
            var dark = 0
            for (c in col) if (luma(c) <= threshold) dark++
            return dark > col.size * 0.98
        }
        var left = 0
        while (left < w / 2 && colDark(left)) left++
        var right = w - 1
        while (right > w / 2 && colDark(right)) right--
        if (right - left < 16 || bottom - top < 16) return Rect(0, 0, w, h)
        return Rect(left, top, right + 1, bottom + 1)
    }

    private fun luma(c: Int): Int =
        ((c shr 16 and 0xFF) * 299 + (c shr 8 and 0xFF) * 587 + (c and 0xFF) * 114) / 1000

    fun rotate90(src: Bitmap, clockwise: Boolean): Bitmap {
        val m = Matrix().apply { postRotate(if (clockwise) 90f else -90f) }
        return Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
    }

    fun flip(src: Bitmap, horizontal: Boolean): Bitmap {
        val m = Matrix().apply {
            if (horizontal) postScale(-1f, 1f, src.width / 2f, src.height / 2f)
            else postScale(1f, -1f, src.width / 2f, src.height / 2f)
        }
        return Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
    }

    fun crop(src: Bitmap, rect: Rect): Bitmap {
        val r = Rect(rect).apply {
            left = coerceAtLeast(0, left); top = coerceAtLeast(0, top)
            right = coerceAtMost(src.width - 1, right); bottom = coerceAtMost(src.height - 1, bottom)
        }
        if (r.width() < 1 || r.height() < 1) return src
        return Bitmap.createBitmap(src, r.left, r.top, r.width(), r.height())
    }

    private fun coerceAtLeast(min: Int, v: Int) = max(min, v)
    private fun coerceAtMost(max: Int, v: Int) = min(max, v)

    fun scale(src: Bitmap, targetW: Int): Bitmap {
        if (targetW <= 0 || targetW >= src.width) return src
        val ratio = targetW.toFloat() / src.width
        return Bitmap.createScaledBitmap(src, targetW, max(1, (src.height * ratio).toInt()), true)
    }

    /** 大图采样解码，避免 OOM */
    fun decodeSampled(path: String, maxSide: Int): Bitmap? = try {
        val opts = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeFile(path, opts)
        if (opts.outWidth <= 0 || opts.outHeight <= 0) null else {
            var sample = 1
            while (max(opts.outWidth, opts.outHeight) / (sample * 2) >= maxSide) sample *= 2
            val decode = android.graphics.BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            android.graphics.BitmapFactory.decodeFile(path, decode)
        }
    } catch (e: Throwable) {
        null
    }

    /** 兼容文件路径与 content:// Uri 的采样解码 */
    fun decodeSampledAny(ctx: android.content.Context, loc: String?, maxSide: Int): Bitmap? {
        if (loc.isNullOrBlank()) return null
        if (!loc.startsWith("content://")) return decodeSampled(loc, maxSide)
        return try {
            val uri = android.net.Uri.parse(loc)
            val opts = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            ctx.contentResolver.openInputStream(uri)?.use {
                android.graphics.BitmapFactory.decodeStream(it, null, opts)
            }
            if (opts.outWidth <= 0 || opts.outHeight <= 0) return null
            var sample = 1
            while (max(opts.outWidth, opts.outHeight) / (sample * 2) >= maxSide) sample *= 2
            val decode = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
            ctx.contentResolver.openInputStream(uri)?.use {
                android.graphics.BitmapFactory.decodeStream(it, null, decode)
            }
        } catch (e: Throwable) {
            null
        }
    }

    fun copyMutable(src: Bitmap): Bitmap =
        if (src.isMutable) src else src.copy(Bitmap.Config.ARGB_8888, true)

    /** 行内容签名（长截图重叠识别用）：横向采样像素生成哈希 */
    fun rowSignature(pixels: IntArray, width: Int, row: Int, step: Int = 8): Long {
        var h = 1469598103934665603L
        val base = row * width
        var x = 0
        while (x < width) {
            val c = pixels[base + x]
            h = (h xor (c.toLong() and 0xFFFFFFFFL)) * 1099511628211L
            x += step
        }
        return h
    }

    fun sameColor(a: Int, b: Int, tol: Int = 12): Boolean {
        return abs((a shr 16 and 0xFF) - (b shr 16 and 0xFF)) <= tol &&
            abs((a shr 8 and 0xFF) - (b shr 8 and 0xFF)) <= tol &&
            abs((a and 0xFF) - (b and 0xFF)) <= tol
    }

    fun ensureArgb(bmp: Bitmap): Bitmap =
        if (bmp.config == Bitmap.Config.ARGB_8888) bmp else {
            val out = bmp.copy(Bitmap.Config.ARGB_8888, false)
            if (out !== bmp) bmp.recycle()
            out
        }

    @Suppress("DEPRECATION")
    fun compressFormatOf(fmt: String): Bitmap.CompressFormat = when (fmt) {
        "png" -> Bitmap.CompressFormat.PNG
        "jpg", "jpeg" -> Bitmap.CompressFormat.JPEG
        else -> if (android.os.Build.VERSION.SDK_INT >= 30)
            Bitmap.CompressFormat.WEBP_LOSSY
        else
            Bitmap.CompressFormat.WEBP
    }
}
