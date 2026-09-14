package com.pureshot.screenshot.core.capture

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import androidx.core.content.ContextCompat
import com.pureshot.screenshot.R
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * 区域截图选区视图（Issue4）：自由拖拽绘制选区、拖拽边角/整体移动调整大小与位置。
 */
class RegionSelectorView @JvmOverloads constructor(
    ctx: Context, attrs: AttributeSet? = null
) : View(ctx, attrs) {

    private val dim = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x99000000.toInt() }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; strokeWidth = 3f; style = Paint.Style.STROKE
    }
    private val handle = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.brand_primary)
    }
    private val grid = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x55FFFFFF; strokeWidth = 1f; style = Paint.Style.STROKE
    }

    private val rect = RectF()
    private var initDone = false
    private var downX = 0f
    private var downY = 0f
    private var mode = 0 // 0 none,1 draw,2 move,3 resize
    private val hot = IntArray(8)
    private var anchorX = 0f
    private var anchorY = 0f

    companion object {
        private const val TOUCH = 48f
        private const val MIN = 60f
        const val NONE = 0
        const val DRAW = 1
        const val MOVE = 2
        const val RESIZE = 3
    }

    var onSelectionChanged: ((RectF) -> Unit)? = null

    fun selection(): RectF = RectF(rect)

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (!initDone && w > 0 && h > 0) {
            initDone = true
            rect.set(w * 0.2f, h * 0.25f, w * 0.8f, h * 0.6f)
        }
    }

    private fun corners() {
        hot[0] = hit(rect.left, rect.top)
        hot[1] = hit(rect.right, rect.top)
        hot[2] = hit(rect.left, rect.bottom)
        hot[3] = hit(rect.right, rect.bottom)
        hot[4] = hit((rect.left + rect.right) / 2, rect.top)
        hot[5] = hit((rect.left + rect.right) / 2, rect.bottom)
        hot[6] = hit(rect.left, (rect.top + rect.bottom) / 2)
        hot[7] = hit(rect.right, (rect.top + rect.bottom) / 2)
    }

    private fun hit(x: Float, y: Float) = if (abs(x - downX) < TOUCH && abs(y - downY) < TOUCH) 1 else 0

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x; downY = event.y
                corners()
                mode = when {
                    hot.any { it == 1 } -> RESIZE
                    rect.contains(downX, downY) -> MOVE
                    else -> {
                        anchorX = downX; anchorY = downY
                        rect.set(downX, downY, downX, downY); DRAW
                    }
                }
                parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.x - downX
                val dy = event.y - downY
                when (mode) {
                    DRAW -> {
                        rect.set(
                            min(anchorX, event.x), min(anchorY, event.y),
                            max(anchorX, event.x), max(anchorY, event.y)
                        )
                    }
                    MOVE -> {
                        val w = rect.width(); val h = rect.height()
                        var l = rect.left + dx; var t = rect.top + dy
                        l = l.coerceIn(0f, width - w); t = t.coerceIn(0f, height - h)
                        rect.set(l, t, l + w, t + h)
                    }
                    RESIZE -> resizeTo(event.x, event.y)
                }
                downX = event.x; downY = event.y
                onSelectionChanged?.invoke(selection())
                invalidate()
            }
            MotionEvent.ACTION_UP -> {
                if (mode == DRAW) {
                    if (rect.width() < MIN || rect.height() < MIN) {
                        rect.set((width * 0.2f), (height * 0.25f), (width * 0.8f), (height * 0.6f))
                    }
                    onSelectionChanged?.invoke(selection())
                }
                mode = NONE
                invalidate()
            }
        }
        return true
    }

    private fun resizeTo(x: Float, y: Float) {
        when {
            hot[0] == 1 -> { rect.left = x; rect.top = y }
            hot[1] == 1 -> { rect.right = x; rect.top = y }
            hot[2] == 1 -> { rect.left = x; rect.bottom = y }
            hot[3] == 1 -> { rect.right = x; rect.bottom = y }
            hot[4] == 1 -> rect.top = y
            hot[5] == 1 -> rect.bottom = y
            hot[6] == 1 -> rect.left = x
            hot[7] == 1 -> rect.right = x
        }
        rect.sort()
        if (rect.width() < MIN) rect.right = rect.left + MIN
        if (rect.height() < MIN) rect.bottom = rect.top + MIN
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawRect(0f, 0f, width.toFloat(), rect.top, dim)
        canvas.drawRect(0f, rect.bottom, width.toFloat(), height.toFloat(), dim)
        canvas.drawRect(0f, rect.top, rect.left, rect.bottom, dim)
        canvas.drawRect(rect.right, rect.top, width.toFloat(), rect.bottom, dim)
        canvas.drawRect(rect, line)
        // 三分参考线
        val w3 = rect.width() / 3
        val h3 = rect.height() / 3
        canvas.drawLine(rect.left + w3, rect.top, rect.left + w3, rect.bottom, grid)
        canvas.drawLine(rect.left + 2 * w3, rect.top, rect.left + 2 * w3, rect.bottom, grid)
        canvas.drawLine(rect.left, rect.top + h3, rect.right, rect.top + h3, grid)
        canvas.drawLine(rect.left, rect.top + 2 * h3, rect.right, rect.top + 2 * h3, grid)
        drawHandle(canvas, rect.left, rect.top)
        drawHandle(canvas, rect.right, rect.top)
        drawHandle(canvas, rect.left, rect.bottom)
        drawHandle(canvas, rect.right, rect.bottom)
    }

    private fun drawHandle(canvas: Canvas, x: Float, y: Float) {
        canvas.drawCircle(x, y, 12f, handle)
    }
}
