package com.pureshot.screenshot.ui.editor

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.Rect
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.pureshot.screenshot.core.editor.EditDocument
import com.pureshot.screenshot.core.util.BitmapUtil
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * 裁剪蒙版层（Issue9）：自由裁剪、四角缩放、边界微调、90°旋转、上下/左右翻转、
 * 预设比例、网格参考线、水平校正(±10°微调)、自动黑边裁切。
 */
class CropOverlayView @JvmOverloads constructor(
    ctx: Context, attrs: AttributeSet? = null
) : View(ctx, attrs) {

    var doc: EditDocument? = null

    var ratio: Float = 0f // 0=自由
        set(v) {
            field = v
            applyRatio()
            invalidate()
        }

    var fineAngle = 0f // 水平校正角度
        set(v) {
            field = v
            rebuildRotated()
            resetRect()
            invalidate()
        }

    var showGrid = true
        set(v) {
            field = v
            invalidate()
        }

    var onCropConfirmed: (() -> Unit)? = null

    private val dim = Paint().apply { color = 0x99000000.toInt() }
    private val frame = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 3f
    }
    private val third = Paint().apply { color = 0x66FFFFFF; strokeWidth = 1f }
    private val handleFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val handleBorder = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * resources.displayMetrics.density
        color = androidx.core.content.ContextCompat.getColor(
            context, com.pureshot.screenshot.R.color.brand_primary
        )
    }

    // 手柄按屏幕像素绘制，尺寸恒定，不受图片缩放影响
    private val handleRadius = 9f * resources.displayMetrics.density
    private val touchRadius = 28f * resources.displayMetrics.density

    private var rotated: Bitmap? = null
    private val rect = RectF()
    private var fitScale = 1f
    private var offX = 0f
    private var offY = 0f
    private var downX = 0f
    private var downY = 0f
    private var anchorX = 0f
    private var anchorY = 0f
    private var mode = 0 // 1 draw 2 move 3 resize
    private val hot = IntArray(8)

    companion object {
        private const val MIN = 80f
    }

    fun bind() {
        rebuildRotated()
        resetRect()
        invalidate()
    }

    private fun rebuildRotated() {
        val d = doc ?: return
        rotated?.let { try { if (it !== d.base) it.recycle() } catch (e: Throwable) {} }
        rotated = if (fineAngle == 0f) d.base else {
            val m = Matrix().apply { postRotate(fineAngle, d.base.width / 2f, d.base.height / 2f) }
            Bitmap.createBitmap(d.base, 0, 0, d.base.width, d.base.height, m, true)
        }
    }

    private fun resetRect() {
        val r = rotated ?: return
        rect.set(r.width * 0.06f, r.height * 0.06f, r.width * 0.94f, r.height * 0.94f)
        if (ratio > 0f) applyRatio()
    }

    private fun applyRatio() {
        val rt = rotated ?: return
        if (ratio <= 0f) return
        var w = rect.width()
        var h = w / ratio
        if (h > rect.height()) {
            h = rect.height(); w = h * ratio
        }
        val cx = rect.centerX(); val cy = rect.centerY()
        rect.set(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2)
        clampRect(rt)
    }

    private fun clampRect(rt: Bitmap) {
        val w = rect.width(); val h = rect.height()
        var l = rect.left.coerceIn(0f, rt.width - w)
        var t = rect.top.coerceIn(0f, rt.height - h)
        rect.set(l, t, l + w, t + h)
    }

    /** 自动黑边裁切 */
    fun autoTrimBlack() {
        val d = doc ?: return
        val b = BitmapUtil.contentBounds(d.base)
        fineAngle = 0f
        rebuildRotated()
        rect.set(b.left.toFloat(), b.top.toFloat(), b.right.toFloat(), b.bottom.toFloat())
        if (ratio > 0f) applyRatio()
        invalidate()
    }

    /** 90° 旋转（烘焙到底图，元素同步映射） */
    fun rotate90() {
        val d = doc ?: return
        fineAngle = 0f
        val m = Matrix().apply { postRotate(90f, d.base.width / 2f, d.base.height / 2f) }
        val nb = Bitmap.createBitmap(d.base, 0, 0, d.base.width, d.base.height, m, true)
        d.applyTransform(nb, m)
        rebuildRotated()
        resetRect()
        invalidate()
    }

    fun flip(horizontal: Boolean) {
        val d = doc ?: return
        fineAngle = 0f
        val m = Matrix().apply {
            if (horizontal) postScale(-1f, 1f, d.base.width / 2f, d.base.height / 2f)
            else postScale(1f, -1f, d.base.width / 2f, d.base.height / 2f)
        }
        val nb = Bitmap.createBitmap(d.base, 0, 0, d.base.width, d.base.height, m, true)
        d.applyTransform(nb, m)
        rebuildRotated()
        resetRect()
        invalidate()
    }

    fun reset() {
        val d = doc ?: return
        ratio = 0f
        fineAngle = 0f
        rebuildRotated()
        resetRect()
        invalidate()
    }

    /** 确认裁剪：烘焙裁剪区域与水平校正角，映射所有元素 */
    fun confirm() {
        val d = doc ?: return
        val rt = rotated ?: return
        if (rt.width < 1 || rt.height < 1) return
        val sx = rt.width.toFloat() / displayW()
        val sy = rt.height.toFloat() / displayH()
        val cropRect = Rect(
            (rect.left * sx).toInt().coerceIn(0, rt.width - 1),
            (rect.top * sy).toInt().coerceIn(0, rt.height - 1),
            (rect.right * sx).toInt().coerceIn(1, rt.width),
            (rect.bottom * sy).toInt().coerceIn(1, rt.height)
        )
        val cropped = BitmapUtil.crop(rt, cropRect)
        val transform = Matrix().apply {
            if (fineAngle != 0f) postRotate(fineAngle, d.base.width / 2f, d.base.height / 2f)
            postTranslate(-cropRect.left.toFloat(), -cropRect.top.toFloat())
        }
        d.applyTransform(cropped, transform)
        fineAngle = 0f
        rebuildRotated()
        resetRect()
        onCropConfirmed?.invoke()
        invalidate()
    }

    // ---- 显示几何 ----
    private fun displayW(): Float {
        val rt = rotated ?: return 1f
        val availW = width - 32f
        val availH = height - 32f
        val s = min(availW / rt.width, availH / rt.height)
        return rt.width * s
    }

    private fun displayH(): Float {
        val rt = rotated ?: return 1f
        val availW = width - 32f
        val availH = height - 32f
        val s = min(availW / rt.width, availH / rt.height)
        return rt.height * s
    }

    private fun computeFit() {
        val rt = rotated ?: return
        val availW = width - 32f
        val availH = height - 32f
        fitScale = min(availW / rt.width, availH / rt.height)
        offX = (width - rt.width * fitScale) / 2f
        offY = (height - rt.height * fitScale) / 2f
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        computeFit()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val rt = rotated ?: return
        computeFit()
        canvas.save()
        canvas.translate(offX, offY)
        canvas.scale(fitScale, fitScale)
        canvas.drawBitmap(rt, 0f, 0f, null)
        // 蒙版外区域压暗
        canvas.drawRect(0f, 0f, rt.width.toFloat(), rect.top, dim)
        canvas.drawRect(0f, rect.bottom, rt.width.toFloat(), rt.height.toFloat(), dim)
        canvas.drawRect(0f, rect.top, rect.left, rect.bottom, dim)
        canvas.drawRect(rect.right, rect.top, rt.width.toFloat(), rect.bottom, dim)
        canvas.drawRect(rect, frame)
        if (showGrid) {
            val w3 = rect.width() / 3; val h3 = rect.height() / 3
            canvas.drawLine(rect.left + w3, rect.top, rect.left + w3, rect.bottom, third)
            canvas.drawLine(rect.left + 2 * w3, rect.top, rect.left + 2 * w3, rect.bottom, third)
            canvas.drawLine(rect.left, rect.top + h3, rect.right, rect.top + h3, third)
            canvas.drawLine(rect.left, rect.top + 2 * h3, rect.right, rect.top + 2 * h3, third)
        }
        canvas.restore()
        // 8 个拖拽点（4 角 + 4 边中点），屏幕坐标绘制，大小恒定且便于点选
        val pts = handlePoints()
        for (i in 0 until 8) {
            val hx = pts[i * 2]
            val hy = pts[i * 2 + 1]
            canvas.drawCircle(hx, hy, handleRadius, handleFill)
            canvas.drawCircle(hx, hy, handleRadius, handleBorder)
        }
    }

    /** 8 个手柄的屏幕坐标，顺序：左上、右上、左下、右下、上中、下中、左中、右中 */
    private fun handlePoints(): FloatArray {
        val l = offX + rect.left * fitScale
        val r = offX + rect.right * fitScale
        val t = offY + rect.top * fitScale
        val b = offY + rect.bottom * fitScale
        val cx = (l + r) / 2f
        val cy = (t + b) / 2f
        return floatArrayOf(
            l, t, r, t, l, b, r, b,
            cx, t, cx, b, l, cy, r, cy
        )
    }

    private fun inRect(ev: MotionEvent): PointF {
        computeFit()
        return PointF((ev.x - offX) / fitScale, (ev.y - offY) / fitScale)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val rt = rotated ?: return false
        val p = inRect(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = p.x; downY = p.y
                val pts = handlePoints()
                for (i in 0 until 8) {
                    val hx = pts[i * 2]
                    val hy = pts[i * 2 + 1]
                    hot[i] = if (abs(event.x - hx) <= touchRadius && abs(event.y - hy) <= touchRadius) 1 else 0
                }
                mode = when {
                    hot.any { it == 1 } -> 3
                    rect.contains(p.x, p.y) -> 2
                    else -> 1
                }
                if (mode == 1) { anchorX = p.x; anchorY = p.y }
            }
            MotionEvent.ACTION_MOVE -> {
                when (mode) {
                    1 -> {
                        rect.set(
                            min(anchorX, p.x), min(anchorY, p.y),
                            max(anchorX, p.x), max(anchorY, p.y)
                        )
                        if (ratio > 0f) {
                            var w = rect.width(); var h = w / ratio
                            if (rect.bottom + h > rt.height) h = rt.height - rect.top
                            rect.bottom = rect.top + h
                        }
                    }
                    2 -> {
                        val dx = p.x - downX; val dy = p.y - downY
                        downX = p.x; downY = p.y
                        val w = rect.width(); val h = rect.height()
                        var l = rect.left + dx; var t = rect.top + dy
                        l = l.coerceIn(0f, rt.width - w); t = t.coerceIn(0f, rt.height - h)
                        rect.set(l, t, l + w, t + h)
                    }
                    3 -> {
                        resize(p, rt)
                    }
                }
                invalidate()
            }
            MotionEvent.ACTION_UP -> {
                if (rect.width() < MIN) resetRect()
                mode = 0
                invalidate()
            }
        }
        return true
    }

    private fun resize(p: PointF, rt: Bitmap) {
        when {
            hot[0] == 1 -> { rect.left = p.x; rect.top = p.y }
            hot[1] == 1 -> { rect.right = p.x; rect.top = p.y }
            hot[2] == 1 -> { rect.left = p.x; rect.bottom = p.y }
            hot[3] == 1 -> { rect.right = p.x; rect.bottom = p.y }
            hot[4] == 1 -> rect.top = p.y
            hot[5] == 1 -> rect.bottom = p.y
            hot[6] == 1 -> rect.left = p.x
            hot[7] == 1 -> rect.right = p.x
        }
        rect.sort()
        if (ratio > 0f) {
            var w = rect.width(); var h = w / ratio
            rect.bottom = rect.top + h
        }
        if (rect.width() < MIN) rect.right = rect.left + MIN
        if (rect.height() < MIN) rect.bottom = rect.top + MIN
        clampRect(rt)
    }
}
