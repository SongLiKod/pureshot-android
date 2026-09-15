package com.pureshot.screenshot.ui.editor

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import com.pureshot.screenshot.core.Prefs
import com.pureshot.screenshot.core.editor.ArrowElement
import com.pureshot.screenshot.core.editor.ArrowKind
import com.pureshot.screenshot.core.editor.EditDocument
import com.pureshot.screenshot.core.editor.EditElement
import com.pureshot.screenshot.core.editor.MosaicElement
import com.pureshot.screenshot.core.editor.MosaicKind
import com.pureshot.screenshot.core.editor.NumberElement
import com.pureshot.screenshot.core.editor.NumberShape
import com.pureshot.screenshot.core.editor.ShapeElement
import com.pureshot.screenshot.core.editor.ShapeKind
import com.pureshot.screenshot.core.editor.StrokeElement
import com.pureshot.screenshot.core.editor.TextElement
import com.pureshot.screenshot.core.editor.smoothPath
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

enum class Tool {
    SELECT, CROP,
    MOSAIC_RECT, MOSAIC_CIRCLE, MOSAIC_FREE, BLUR,
    NUMBER, ARROW, SHAPE, TEXT, PEN, HIGHLIGHTER, ERASER
}

/**
 * 图层化标注画布（Issue8-13）：原图层/马赛克层/标注层分离绘制，
 * 支持单元素选择、拖拽、双指缩放、锚点二次编辑、橡皮擦局部恢复。
 */
class LayerEditorView @JvmOverloads constructor(
    ctx: Context, attrs: AttributeSet? = null
) : View(ctx, attrs) {

    var doc: EditDocument? = null
        private set

    var tool: Tool = Tool.SELECT
        set(v) {
            field = v
            selected = null
            resetWork()
            invalidate()
        }

    // ---- 工具参数（受“工具参数记忆”控制持久化） ----
    var color = Prefs.lastColor
    var strokeWidth = Prefs.lastStrokeWidth
    var textSize = Prefs.lastTextSize
    var arrowHead = Prefs.lastArrowHead
    var arrowKind = ArrowKind.SINGLE
    var shapeKind = ShapeKind.RECT
    var numberShape = NumberShape.ROUND_RECT
    var fillEnabled = false
    var mosaicBlock = blockOf(Prefs.mosaicSize)
    var blurRadius = blurOf(Prefs.blurStrength)
    var eraserWidth = 64f

    companion object {
        fun blockOf(level: Int) = when (level) { 0 -> 8; 2 -> 24; else -> 16 }
        fun blurOf(level: Int) = when (level) { 0 -> 10; 2 -> 32; else -> 20 }
    }

    fun rememberParams() {
        if (!Prefs.rememberParams) return
        Prefs.lastColor = color
        Prefs.lastStrokeWidth = strokeWidth
        Prefs.lastTextSize = textSize
        Prefs.lastArrowHead = arrowHead
        Prefs.mosaicSize = when (mosaicBlock) { 8 -> 0; 24 -> 2; else -> 1 }
        Prefs.blurStrength = when (blurRadius) { 10 -> 0; 32 -> 2; else -> 1 }
    }

    // ---- 回调 ----
    var onTextRequested: ((Float, Float) -> Unit)? = null
    var onTextEditRequested: ((TextElement) -> Unit)? = null
    var onNumberEditRequested: ((NumberElement) -> Unit)? = null
    var onSelectionChanged: ((EditElement?) -> Unit)? = null
    var onDocumentChanged: (() -> Unit)? = null

    // ---- 内部状态 ----
    private val fit = Matrix()
    private val inverse = Matrix()
    private var lastBaseW = 0
    private var lastBaseH = 0
    private var work: EditElement? = null
    private val polyPoints = mutableListOf<PointF>()
    private var selected: EditElement? = null
    private var dragEndpoint = 0
    private var lastX = 0f
    private var lastY = 0f
    private var downX = 0f
    private var downY = 0f
    private var moved = false
    private var lastTapTime = 0L
    private var lastTapEl: EditElement? = null
    private var erasePushed = false

    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = androidx.core.content.ContextCompat.getColor(
            context, com.pureshot.screenshot.R.color.brand_primary
        )
        style = Paint.Style.STROKE; strokeWidth = 4f
    }
    private val handleFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val gridPaint = Paint().apply { color = 0x33FFFFFF; strokeWidth = 1f }

    fun setDocument(d: EditDocument) {
        doc = d
        selected = null
        work = null
        polyPoints.clear()
        requestLayout()
        recomputeFit()
        invalidate()
    }

    fun addText(x: Float, y: Float, text: String) {
        val d = doc ?: return
        d.pushHistory()
        d.elements.add(TextElement(PointF(x, y), text, color, textSize))
        onDocumentChanged?.invoke()
        invalidate()
    }

    fun updateNumber(el: NumberElement, value: Int) {
        val d = doc ?: return
        d.pushHistory()
        el.value = value
        onDocumentChanged?.invoke()
        invalidate()
    }

    fun undo(): Boolean = doc?.undo()?.also { onDocumentChanged?.invoke(); invalidate() } ?: false
    fun redo(): Boolean = doc?.redo()?.also { onDocumentChanged?.invoke(); invalidate() } ?: false

    fun deleteSelected() {
        val d = doc ?: return
        val s = selected ?: return
        d.deleteElement(s)
        selected = null
        onSelectionChanged?.invoke(null)
        onDocumentChanged?.invoke()
        invalidate()
    }

    private fun resetWork() {
        work = null
        polyPoints.clear()
    }

    private fun toImage(ev: MotionEvent, idx: Int = 0): PointF {
        val arr = floatArrayOf(ev.getX(idx), ev.getY(idx))
        inverse.mapPoints(arr)
        return PointF(arr[0], arr[1])
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        recomputeFit()
    }

    private fun recomputeFit() {
        val d = doc ?: return
        if (width == 0 || height == 0) return
        val sx = width.toFloat() / d.base.width
        val sy = height.toFloat() / d.base.height
        val s = min(sx, sy)
        val tx = (width - d.base.width * s) / 2f
        val ty = (height - d.base.height * s) / 2f
        fit.setValues(floatArrayOf(s, 0f, tx, 0f, s, ty, 0f, 0f, 1f))
        fit.invert(inverse)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val d = doc ?: return
        val fv = FloatArray(9)
        fit.getValues(fv)
        if (fv[0] == 0f || d.base.width != lastBaseW || d.base.height != lastBaseH) recomputeFit()
        lastBaseW = d.base.width
        lastBaseH = d.base.height
        canvas.save()
        canvas.concat(fit)
        canvas.drawBitmap(d.base, 0f, 0f, null)
        for (e in d.elements) e.draw(canvas, d)
        work?.draw(canvas, d)
        if (polyPoints.isNotEmpty()) drawPolygonDraft(canvas)
        selected?.let { drawSelection(canvas, it) }
        canvas.restore()
    }

    private fun drawPolygonDraft(canvas: Canvas) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = this@LayerEditorView.color; style = Paint.Style.STROKE; strokeWidth = 3f
        }
        for (pt in polyPoints) canvas.drawCircle(pt.x, pt.y, 8f, p)
        if (polyPoints.size >= 2) {
            val path = Path()
            path.moveTo(polyPoints[0].x, polyPoints[0].y)
            for (i in 1 until polyPoints.size) path.lineTo(polyPoints[i].x, polyPoints[i].y)
            canvas.drawPath(path, p)
        }
    }

    private fun drawSelection(canvas: Canvas, e: EditElement) {
        val b = e.bounds()
        canvas.drawRect(b, handlePaint)
        when (e) {
            is ArrowElement -> {
                canvas.drawCircle(e.from.x, e.from.y, 14f, handleFill)
                canvas.drawCircle(e.from.x, e.from.y, 14f, handlePaint)
                canvas.drawCircle(e.to.x, e.to.y, 14f, handleFill)
                canvas.drawCircle(e.to.x, e.to.y, 14f, handlePaint)
            }
            is NumberElement -> {
                canvas.drawCircle(e.center.x, e.center.y, e.radius + 10f, handlePaint)
            }
            else -> {
                for (cx in floatArrayOf(b.left, b.right)) for (cy in floatArrayOf(b.top, b.bottom)) {
                    canvas.drawRect(cx - 12, cy - 12, cx + 12, cy + 12, handleFill)
                    canvas.drawRect(cx - 12, cy - 12, cx + 12, cy + 12, handlePaint)
                }
            }
        }
    }

    // ---- 手势 ----
    private var pinchStart = 0f
    private var pinchRadius0 = 0f
    private var pinchText0 = 0f

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val d = doc ?: return false
        val p = toImage(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = p.x; downY = p.y; lastX = p.x; lastY = p.y; moved = false
                erasePushed = false
                handleDown(d, p)
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (event.pointerCount == 2 && selected is NumberElement) {
                    pinchStart = hypot(
                        (event.getX(0) - event.getX(1)).toDouble(),
                        (event.getY(0) - event.getY(1)).toDouble()
                    ).toFloat()
                    val n = selected as NumberElement
                    pinchRadius0 = n.radius; pinchText0 = n.textSize
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (event.pointerCount == 2 && selected is NumberElement && pinchStart > 0f) {
                    val dist = hypot(
                        (event.getX(0) - event.getX(1)).toDouble(),
                        (event.getY(0) - event.getY(1)).toDouble()
                    ).toFloat()
                    val factor = (dist / pinchStart).coerceIn(0.3f, 3f)
                    val n = selected as NumberElement
                    n.radius = pinchRadius0 * factor
                    n.textSize = pinchText0 * factor
                    invalidate()
                    return true
                }
                if (hypot((p.x - downX).toDouble(), (p.y - downY).toDouble()) > 8) moved = true
                handleMove(d, p)
            }
            MotionEvent.ACTION_UP -> handleUp(d, p, event)
            MotionEvent.ACTION_CANCEL -> resetWork()
        }
        return true
    }

    private fun handleDown(d: EditDocument, p: PointF) {
        when (tool) {
            Tool.SELECT -> {
                val sel = selected
                if (sel is ArrowElement) {
                    val ep = sel.endpointNear(p.x, p.y)
                    if (ep > 0) {
                        dragEndpoint = ep
                        return
                    }
                }
                val hit = d.elements.lastOrNull { it.hit(p.x, p.y) }
                selected = hit
                onSelectionChanged?.invoke(hit)
                if (hit != null) {
                    val now = System.currentTimeMillis()
                    if (hit === lastTapEl && now - lastTapTime < 320) {
                        onDoubleTap(hit)
                    }
                    lastTapTime = now; lastTapEl = hit
                }
            }
            Tool.MOSAIC_RECT -> work = MosaicElement(MosaicKind.RECT, RectF(p.x, p.y, p.x, p.y), block = mosaicBlock)
            Tool.BLUR -> work = MosaicElement(MosaicKind.BLUR, RectF(p.x, p.y, p.x, p.y), blurRadius = blurRadius)
            Tool.MOSAIC_CIRCLE -> work = MosaicElement(MosaicKind.CIRCLE, center = PointF(p.x, p.y), radius = 1f, block = mosaicBlock)
            Tool.MOSAIC_FREE -> work = MosaicElement(MosaicKind.FREE, points = mutableListOf(PointF(p.x, p.y)), brushWidth = mosaicBrush(), block = mosaicBlock)
            Tool.PEN -> work = StrokeElement(mutableListOf(PointF(p.x, p.y)), color, strokeWidth)
            Tool.HIGHLIGHTER -> work = StrokeElement(mutableListOf(PointF(p.x, p.y)), color, strokeWidth, highlighter = true)
            Tool.ARROW -> work = ArrowElement(PointF(p.x, p.y), PointF(p.x, p.y), color, strokeWidth, arrowHead, arrowKind)
            Tool.SHAPE -> {
                if (shapeKind == ShapeKind.POLYGON) return // 多边形按点击逐点添加
                work = ShapeElement(
                    shapeKind,
                    mutableListOf(PointF(p.x, p.y), PointF(p.x, p.y)),
                    color, strokeWidth, fillPaint()
                )
            }
            Tool.NUMBER -> {
                d.pushHistory()
                val el = NumberElement(PointF(p.x, p.y), d.nextNumber(), 36f * (textSize / 40f), color, Color.WHITE, textSize, numberShape)
                d.elements.add(el)
                onDocumentChanged?.invoke()
            }
            Tool.TEXT -> { /* up 时触发输入 */ }
            Tool.ERASER -> { /* up 时判定点击删除；拖动擦除 */ }
            Tool.CROP -> {}
        }
        invalidate()
    }

    private fun fillPaint(): Int? = if (fillEnabled) (color and 0x00FFFFFF or 0x55000000) else null
    private fun mosaicBrush() = max(48f, strokeWidth * 8f)

    private fun handleMove(d: EditDocument, p: PointF) {
        when (tool) {
            Tool.SELECT -> {
                val sel = selected ?: return
                if (dragEndpoint > 0 && sel is ArrowElement) {
                    if (dragEndpoint == 1) sel.from = PointF(p.x, p.y) else sel.to = PointF(p.x, p.y)
                } else if (moved) {
                    sel.translate(p.x - lastX, p.y - lastY)
                }
            }
            Tool.MOSAIC_RECT, Tool.BLUR -> (work as? MosaicElement)?.let { it.rect.set(downX.coerceAtMost(p.x), downY.coerceAtMost(p.y), downX.coerceAtLeast(p.x), downY.coerceAtLeast(p.y)) }
            Tool.MOSAIC_CIRCLE -> (work as? MosaicElement)?.let {
                it.radius = hypot((p.x - it.center.x).toDouble(), (p.y - it.center.y).toDouble()).toFloat()
            }
            Tool.MOSAIC_FREE -> (work as? MosaicElement)?.points?.add(PointF(p.x, p.y))
            Tool.PEN, Tool.HIGHLIGHTER -> (work as? StrokeElement)?.points?.add(PointF(p.x, p.y))
            Tool.ARROW -> (work as? ArrowElement)?.to = PointF(p.x, p.y)
            Tool.SHAPE -> (work as? ShapeElement)?.let { s ->
                when (s.kind) {
                    ShapeKind.RECT, ShapeKind.ELLIPSE, ShapeKind.LINE -> {
                        s.points[1] = PointF(p.x, p.y)
                    }
                    ShapeKind.SQUARE, ShapeKind.CIRCLE -> {
                        // 方形/圆形：拖拽时等比锁定，保证四边等长
                        val dx = p.x - downX
                        val dy = p.y - downY
                        val side = max(abs(dx), abs(dy))
                        s.points[1] = PointF(
                            downX + if (dx < 0f) -side else side,
                            downY + if (dy < 0f) -side else side
                        )
                    }
                    ShapeKind.POLYGON -> { /* 多边形逐点添加，无拖动预览 */ }
                }
            }
            Tool.ERASER -> {
                if (moved) {
                    eraseAt(d, PointF(lastX, lastY), PointF(p.x, p.y))
                }
            }
            else -> {}
        }
        lastX = p.x; lastY = p.y
        invalidate()
    }

    private fun handleUp(d: EditDocument, p: PointF, ev: MotionEvent) {
        dragEndpoint = 0
        when (tool) {
            Tool.TEXT -> if (!moved) onTextRequested?.invoke(p.x, p.y)
            Tool.ERASER -> {
                if (!moved) {
                    val hit = d.elements.lastOrNull { it !is MosaicElement && it.hit(p.x, p.y) }
                    if (hit != null) {
                        d.pushHistory()
                        d.elements.remove(hit)
                        onDocumentChanged?.invoke()
                    }
                }
            }
            Tool.SHAPE -> {
                if (shapeKind == ShapeKind.POLYGON) {
                    finishOrAddPoint(d, p)
                    invalidate()
                    return
                }
                commitWork(d)
            }
            else -> commitWork(d)
        }
        invalidate()
    }

    private fun finishOrAddPoint(d: EditDocument, p: PointF) {
        if (polyPoints.size >= 3) {
            val first = polyPoints[0]
            if (hypot((p.x - first.x).toDouble(), (p.y - first.y).toDouble()) < 40) {
                d.pushHistory()
                d.elements.add(ShapeElement(ShapeKind.POLYGON, polyPoints.map { PointF(it.x, it.y) }.toMutableList(), color, strokeWidth, fillPaint()))
                polyPoints.clear()
                onDocumentChanged?.invoke()
                return
            }
        }
        polyPoints.add(PointF(p.x, p.y))
    }

    private fun commitWork(d: EditDocument) {
        val w = work ?: return
        work = null
        val valid = when (w) {
            is MosaicElement -> w.bounds().width() > 4 && w.bounds().height() > 4
            is StrokeElement -> w.points.size >= 2
            is ArrowElement -> hypot(
                (w.to.x - w.from.x).toDouble(), (w.to.y - w.from.y).toDouble()
            ) > 12
            is ShapeElement -> if (w.kind == ShapeKind.LINE && w.points.size >= 2) {
                hypot(
                    (w.points[1].x - w.points[0].x).toDouble(),
                    (w.points[1].y - w.points[0].y).toDouble()
                ) > 12
            } else w.bounds().width() > 4 && w.bounds().height() > 4
            else -> true
        }
        if (valid) {
            d.pushHistory()
            d.elements.add(w)
            onDocumentChanged?.invoke()
        }
    }

    private fun eraseAt(d: EditDocument, from: PointF, to: PointF) {
        // 马赛克层局部恢复（一次拖动手势只记一次历史）
        var erased = false
        for (e in d.elements) {
            if (e is MosaicElement && e.bounds().contains(to.x, to.y)) {
                if (!erasePushed) {
                    d.pushHistory()
                    erasePushed = true
                }
                e.erasePaths.add(mutableListOf(PointF(from.x, from.y), PointF(to.x, to.y)) to eraserWidth)
                erased = true
            }
        }
        if (erased) onDocumentChanged?.invoke()
    }

    private fun onDoubleTap(el: EditElement) {
        when (el) {
            is TextElement -> onTextEditRequested?.invoke(el)
            is NumberElement -> onNumberEditRequested?.invoke(el)
            else -> {}
        }
    }

    /** 供 Fragment 在文字编辑确认后调用 */
    fun editExistingText(el: TextElement, newText: String) {
        val d = doc ?: return
        d.pushHistory()
        el.text = newText
        onDocumentChanged?.invoke()
        invalidate()
    }

    fun selectedElement(): EditElement? = selected
}
