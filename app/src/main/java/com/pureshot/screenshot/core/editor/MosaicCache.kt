package com.pureshot.screenshot.core.editor

import android.graphics.Bitmap
import com.pureshot.screenshot.core.util.BitmapUtil

/**
 * 马赛克层缓存：按需生成像素化/高斯模糊底图并缓存，图层按需渲染，闲置即时释放，避免 OOM。
 */
class MosaicCache {

    private val mosaicMap = HashMap<Int, Bitmap>()
    private val blurMap = HashMap<Int, Bitmap>()
    private var base: Bitmap? = null

    fun bind(bitmap: Bitmap) {
        dispose()
        base = bitmap
    }

    fun baseRect(): android.graphics.RectF {
        val b = base ?: return android.graphics.RectF(0f, 0f, 0f, 0f)
        return android.graphics.RectF(0f, 0f, b.width.toFloat(), b.height.toFloat())
    }

    /** 马赛克像素大小：小/中/大 → block 8/16/24 */
    fun mosaicFor(block: Int): Bitmap? {
        val src = base ?: return null
        return mosaicMap.getOrPut(block) { BitmapUtil.pixelate(src, block) }
    }

    /** 模糊强度：低/中/高 → radius 10/20/32 */
    fun blurFor(radius: Int): Bitmap? {
        val src = base ?: return null
        return blurMap.getOrPut(radius) { BitmapUtil.blur(src, radius) }
    }

    fun dispose() {
        mosaicMap.values.forEach { try { it.recycle() } catch (e: Throwable) {} }
        blurMap.values.forEach { try { it.recycle() } catch (e: Throwable) {} }
        mosaicMap.clear()
        blurMap.clear()
        base = null
    }
}
