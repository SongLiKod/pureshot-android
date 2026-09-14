package com.pureshot.screenshot.core.longshot

import android.graphics.Bitmap
import android.graphics.Color
import com.pureshot.screenshot.core.util.BitmapUtil

/**
 * 长截图拼接引擎（Issue6，主引擎）：帧采样 + 重叠区域智能识别 + 自动去重 + 接缝像素微调。
 * 无需任何无障碍权限。
 */
class FrameStitcher(private val frameWidth: Int, private val maxRows: Int = 14000) {

    private val strips = ArrayList<IntArray>()
    private val stripHeights = ArrayList<Int>()
    private val rowSigs = ArrayList<LongArray>()
    private var totalRows = 0

    private var prevFrame: IntArray? = null
    private var prevSigs: LongArray? = null
    private var prevHeight = 0

    // 最近一次拼接记录（接缝微调回滚用）
    private var lastFrame: IntArray? = null
    private var lastFrameH = 0
    private var lastOverlap = 0
    private var lastAppendedRows = 0

    var onMaxReached: (() -> Unit)? = null

    val isEmpty: Boolean get() = totalRows == 0

    fun appendFirst(frame: IntArray, height: Int) {
        if (totalRows > 0) return
        appendRows(frame, 0, height)
        prevFrame = frame.copyOf()
        prevSigs = sigsOf(frame, height)
        prevHeight = height
    }

    /**
     * 与上一帧匹配重叠区并追加新内容。
     * @return true 表示有新内容被拼接
     */
    fun stitch(frame: IntArray, height: Int): Boolean {
        val prev = prevFrame ?: run { appendFirst(frame, height); return true }
        val prevSig = prevSigs ?: return true
        val frameSig = sigsOf(frame, height)
        val minOverlap = (height * 0.25f).toInt().coerceAtLeast(16)
        var overlap = -1
        var l = height - 1
        while (l >= minOverlap) {
            if (matchScore(prev, prevSig, prevHeight, frame, frameSig, l) >= 0.96f) {
                overlap = l
                break
            }
            l--
        }
        if (overlap < 0) {
            // 重叠过少：视为页面跳转，全帧追加保证不丢内容
            overlap = 0
        }
        if (overlap >= height - 2) return false // 未滚动，去重跳过
        lastFrame = frame.copyOf()
        lastFrameH = height
        lastOverlap = overlap
        val appended = height - overlap
        if (totalRows + appended > maxRows) {
            onMaxReached?.invoke()
            return false
        }
        appendRows(frame, overlap, height)
        prevFrame = frame.copyOf()
        prevSigs = frameSig
        prevHeight = height
        lastAppendedRows = appended
        return true
    }

    /** 接缝像素微调：±delta 调整最近一次拼接的重叠量 */
    fun adjustSeam(delta: Int): Boolean {
        val frame = lastFrame ?: return false
        if (lastAppendedRows == 0) return false
        val newOverlap = (lastOverlap + delta).coerceIn(0, lastFrameH - 2)
        if (newOverlap == lastOverlap) return false
        // 回滚上一次追加的行，再按新重叠重贴
        var remove = lastAppendedRows
        while (remove > 0 && strips.isNotEmpty()) {
            val h = stripHeights.removeAt(stripHeights.size - 1)
            rowSigs.removeAt(rowSigs.size - 1)
            strips.removeAt(strips.size - 1)
            totalRows -= h
            remove -= h
        }
        appendRows(frame, newOverlap, lastFrameH)
        lastOverlap = newOverlap
        lastAppendedRows = lastFrameH - newOverlap
        prevFrame = frame.copyOf()
        prevSigs = sigsOf(frame, lastFrameH)
        prevHeight = lastFrameH
        return true
    }

    private fun appendRows(frame: IntArray, from: Int, to: Int) {
        if (to <= from) return
        val h = to - from
        val strip = IntArray(h * frameWidth)
        System.arraycopy(frame, from * frameWidth, strip, 0, strip.size)
        val sig = LongArray(h)
        for (y in 0 until h) sig[y] = BitmapUtil.rowSignature(frame, frameWidth, from + y)
        strips.add(strip)
        stripHeights.add(h)
        rowSigs.add(sig)
        totalRows += h
    }

    private fun sigsOf(frame: IntArray, height: Int): LongArray {
        val s = LongArray(height)
        for (y in 0 until height) s[y] = BitmapUtil.rowSignature(frame, frameWidth, y)
        return s
    }

    /** prev 末尾 l 行与 frame 开头 l 行的匹配比例 */
    private fun matchScore(
        prev: IntArray, prevSig: LongArray, prevH: Int,
        frame: IntArray, frameSig: LongArray, l: Int
    ): Float {
        var match = 0
        for (i in 0 until l) {
            if (prevSig[prevH - l + i] == frameSig[i]) match++
        }
        return match.toFloat() / l
    }

    fun toBitmap(): Bitmap? {
        if (totalRows <= 0) return null
        return try {
            val out = Bitmap.createBitmap(frameWidth, totalRows, Bitmap.Config.ARGB_8888)
            var y = 0
            for (i in strips.indices) {
                out.setPixels(strips[i], 0, frameWidth, 0, y, frameWidth, stripHeights[i])
                y += stripHeights[i]
            }
            out
        } catch (e: Throwable) {
            null
        }
    }

    /** 预览缩略图（按需渲染，避免大图频繁拷贝） */
    fun previewBitmap(maxW: Int, maxH: Int): Bitmap? {
        val full = toBitmap() ?: return null
        return try {
            val ratio = minOf(maxW.toFloat() / full.width, maxH.toFloat() / full.height, 1f)
            if (ratio >= 1f) full else Bitmap.createScaledBitmap(full, (full.width * ratio).toInt(), (full.height * ratio).toInt(), true).also {
                if (it !== full) full.recycle()
            }
        } catch (e: Throwable) {
            full
        }
    }

    fun release() {
        strips.clear()
        stripHeights.clear()
        rowSigs.clear()
        totalRows = 0
        prevFrame = null
        prevSigs = null
    }
}
