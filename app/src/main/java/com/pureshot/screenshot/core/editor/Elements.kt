package com.pureshot.screenshot.core.editor

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * 图层化元素模型（Issue8 四层画布：原图层 / 马赛克层 / 标注层 / 裁剪蒙版层）。
 * 所有元素独立存储、独立绘制、可单元素删除，原图永久不被破坏。
 */

enum class MosaicKind { RECT, CIRCLE, FREE, BLUR }
enum class ShapeKind { RECT, SQUARE, ELLIPSE, CIRCLE, LINE, POLYGON }
enum class ArrowKind { SINGLE, DOUBLE }
enum class NumberShape { ROUND_RECT, CIRCLE, SQUARE }

/** 标注默认强调色（全局唯一常量，对应 @color/anno_red） */
const val ANNO_DEFAULT_COLOR: Int = 0xFFE53935.toInt()

sealed class EditElement {
    abstract fun draw(canvas: Canvas, doc: EditDocument, cache: MosaicCache = doc.mosaicCache)
    abstract fun bounds(): RectF
    abstract fun translate(dx: Float, dy: Float)
    abstract fun map(m: Matrix)
    abstract fun hit(x: Float, y: Float): Boolean
    abstract fun deepCopy(): EditElement
    open fun toSvg(): String = ""

    protected fun mapPoint(m: Matrix, p: PointF) {
        val arr = floatArrayOf(p.x, p.y)
        m.mapPoints(arr)
        p.x = arr[0]; p.y = arr[1]
    }

    protected fun scaleOf(m: Matrix): Float {
        val v = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f)
        m.mapPoints(v)
        return hypot(v[0], v[1])
    }
}

/** 马赛克/高斯模糊元素：独立图层，支持橡皮擦局部恢复、单图层撤销 */
class MosaicElement(
    var kind: MosaicKind,
    val rect: RectF = RectF(),          // RECT/BLUR
    var center: PointF = PointF(),      // CIRCLE
    var radius: Float = 0f,             // CIRCLE
    val points: MutableList<PointF> = mutableListOf(), // FREE 涂抹轨迹
    var brushWidth: Float = 64f,
    var block: Int = 16,                // 马赛克像素块
    var blurRadius: Int = 20,           // 模糊强度
    val erasePaths: MutableList<Pair<MutableList<PointF>, Float>> = mutableListOf() // 局部恢复
) : EditElement() {

    override fun draw(canvas: Canvas, doc: EditDocument, cache: MosaicCache) {
        val layerBmp = when (kind) {
            MosaicKind.BLUR -> cache.blurFor(blurRadius)
            else -> cache.mosaicFor(block)
        } ?: return
        val bounds = bounds()
        val sc = canvas.saveLayer(bounds, null)
        when (kind) {
            MosaicKind.RECT, MosaicKind.BLUR -> {
                canvas.clipRect(rect)
                canvas.drawBitmap(layerBmp, null, cache.baseRect(), null)
            }
            MosaicKind.CIRCLE -> {
                val path = Path().apply { addCircle(center.x, center.y, radius, Path.Direction.CW) }
                canvas.clipPath(path)
                canvas.drawBitmap(layerBmp, null, cache.baseRect(), null)
            }
            MosaicKind.FREE -> {
                val clip = Path()
                if (points.size >= 2) {
                    val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        style = Paint.Style.STROKE
                        strokeWidth = brushWidth
                        strokeCap = Paint.Cap.ROUND
                        strokeJoin = Paint.Join.ROUND
                    }
                    val path = smoothPath(points)
                    val measure = android.graphics.PathMeasure(path, false)
                    val pos = FloatArray(2)
                    var d = 0f
                    while (d <= measure.length) {
                        measure.getPosTan(d, pos, null)
                        clip.addCircle(pos[0], pos[1], brushWidth / 2f, Path.Direction.CW)
                        d += brushWidth / 3f
                    }
                }
                canvas.clipPath(clip)
                canvas.drawBitmap(layerBmp, null, cache.baseRect(), null)
            }
        }
        // 橡皮擦局部恢复：在临时图层上擦除，原图零损坏
        if (erasePaths.isNotEmpty()) {
            val clear = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR) }
            for ((pts, w) in erasePaths) {
                if (pts.size >= 2) {
                    clear.strokeWidth = w
                    clear.style = Paint.Style.STROKE
                    clear.strokeCap = Paint.Cap.ROUND
                    canvas.drawPath(smoothPath(pts), clear)
                } else if (pts.size == 1) {
                    clear.style = Paint.Style.FILL
                    canvas.drawCircle(pts[0].x, pts[0].y, w / 2f, clear)
                }
            }
        }
        canvas.restoreToCount(sc)
    }

    override fun bounds(): RectF = when (kind) {
        MosaicKind.RECT, MosaicKind.BLUR -> RectF(rect)
        MosaicKind.CIRCLE -> RectF(center.x - radius, center.y - radius, center.x + radius, center.y + radius)
        MosaicKind.FREE -> RectF().apply {
            if (points.isEmpty()) set(0f, 0f, 0f, 0f)
            else {
                var l = Float.MAX_VALUE; var t = Float.MAX_VALUE
                var r = -Float.MAX_VALUE; var b = -Float.MAX_VALUE
                for (p in points) {
                    l = min(l, p.x); t = min(t, p.y); r = max(r, p.x); b = max(b, p.y)
                }
                val pad = brushWidth / 2f
                set(l - pad, t - pad, r + pad, b + pad)
            }
        }
    }

    override fun translate(dx: Float, dy: Float) {
        rect.offset(dx, dy)
        center.offset(dx, dy)
        for (p in points) p.offset(dx, dy)
        for ((pts, _) in erasePaths) for (p in pts) p.offset(dx, dy)
    }

    override fun map(m: Matrix) {
        m.mapRect(rect)
        val c = floatArrayOf(center.x, center.y)
        m.mapPoints(c)
        center = PointF(c[0], c[1])
        val s = scaleOf(m)
        radius *= s
        brushWidth *= s
        for (p in points) mapPoint(m, p)
        for ((pts, w) in erasePaths) {
            for (p in pts) mapPoint(m, p)
        }
    }

    override fun hit(x: Float, y: Float): Boolean = bounds().contains(x, y)

    override fun deepCopy(): EditElement = MosaicElement(
        kind, RectF(rect), PointF(center.x, center.y), radius,
        points.map { PointF(it.x, it.y) }.toMutableList(), brushWidth, block, blurRadius,
        erasePaths.map { (pts, w) -> pts.map { PointF(it.x, it.y) }.toMutableList() to w }.toMutableList()
    )
}

/** 数字序号标注（Issue11）：自动递增、圆角底色标签、拖拽/双指缩放、单序号独立修改删除、自定义颜色大小 */
class NumberElement(
    var center: PointF,
    var value: Int,
    var radius: Float = 36f,
    var fillColor: Int = ANNO_DEFAULT_COLOR,
    var textColor: Int = Color.WHITE,
    var textSize: Float = 40f,
    var shape: NumberShape = NumberShape.ROUND_RECT
) : EditElement() {

    private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val tx = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }

    override fun draw(canvas: Canvas, doc: EditDocument, cache: MosaicCache) {
        bg.color = fillColor
        val r = radius
        val rect = RectF(center.x - r, center.y - r, center.x + r, center.y + r)
        when (shape) {
            NumberShape.CIRCLE -> canvas.drawCircle(center.x, center.y, r, bg)
            NumberShape.SQUARE -> canvas.drawRect(rect, bg)
            NumberShape.ROUND_RECT -> canvas.drawRoundRect(rect, r * 0.45f, r * 0.45f, bg)
        }
        tx.color = textColor
        tx.textSize = textSize
        val fm = tx.fontMetrics
        canvas.drawText(value.toString(), center.x, center.y - (fm.ascent + fm.descent) / 2f, tx)
    }

    override fun bounds(): RectF {
        val r = radius
        return RectF(center.x - r, center.y - r, center.x + r, center.y + r)
    }

    override fun translate(dx: Float, dy: Float) {
        center.offset(dx, dy)
    }

    override fun map(m: Matrix) {
        mapPoint(m, center)
        val s = scaleOf(m)
        radius *= s
        textSize *= s
    }

    override fun hit(x: Float, y: Float): Boolean =
        hypot((x - center.x).toDouble(), (y - center.y).toDouble()) <= radius * 1.3

    override fun deepCopy(): EditElement =
        NumberElement(PointF(center.x, center.y), value, radius, fillColor, textColor, textSize, shape)

    override fun toSvg(): String {
        val r = radius
        val badge = when (shape) {
            NumberShape.CIRCLE ->
                "<circle cx=\"${center.x}\" cy=\"${center.y}\" r=\"$r\" fill=\"${hex(fillColor)}\"/>"
            NumberShape.SQUARE ->
                "<rect x=\"${center.x - r}\" y=\"${center.y - r}\" width=\"${2 * r}\" height=\"${2 * r}\" fill=\"${hex(fillColor)}\"/>"
            NumberShape.ROUND_RECT ->
                "<rect x=\"${center.x - r}\" y=\"${center.y - r}\" width=\"${2 * r}\" height=\"${2 * r}\" rx=\"${r * 0.45}\" fill=\"${hex(fillColor)}\"/>"
        }
        return badge +
            "<text x=\"${center.x}\" y=\"${center.y + textSize * 0.35f}\" font-size=\"$textSize\" fill=\"${hex(textColor)}\" text-anchor=\"middle\">$value</text>"
    }
}

/** 箭头（Issue12）：单向/双向、粗细/颜色/箭头大小可调、锚点二次编辑 */
class ArrowElement(
    var from: PointF,
    var to: PointF,
    var color: Int = ANNO_DEFAULT_COLOR,
    var width: Float = 8f,
    var headSize: Float = 28f,
    var kind: ArrowKind = ArrowKind.SINGLE
) : EditElement() {

    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    override fun draw(canvas: Canvas, doc: EditDocument, cache: MosaicCache) {
        p.color = color; p.strokeWidth = width
        canvas.drawLine(from.x, from.y, to.x, to.y, p)
        drawHead(canvas, to, from)
        if (kind == ArrowKind.DOUBLE) drawHead(canvas, from, to)
    }

    private fun drawHead(canvas: Canvas, tip: PointF, tail: PointF) {
        val ang = Math.atan2((tip.y - tail.y).toDouble(), (tip.x - tail.x).toDouble())
        val a1 = ang + Math.toRadians(150.0)
        val a2 = ang - Math.toRadians(150.0)
        val path = Path()
        path.moveTo(tip.x, tip.y)
        path.lineTo(tip.x + headSize * cos(a1).toFloat(), tip.y + headSize * sin(a1).toFloat())
        path.lineTo(tip.x + headSize * cos(a2).toFloat(), tip.y + headSize * sin(a2).toFloat())
        path.close()
        fill.color = color
        canvas.drawPath(path, fill)
    }

    override fun bounds(): RectF = RectF(
        min(from.x, to.x) - headSize, min(from.y, to.y) - headSize,
        max(from.x, to.x) + headSize, max(from.y, to.y) + headSize
    )

    override fun translate(dx: Float, dy: Float) {
        from.offset(dx, dy); to.offset(dx, dy)
    }

    override fun map(m: Matrix) {
        mapPoint(m, from); mapPoint(m, to)
        width *= scaleOf(m); headSize *= scaleOf(m)
    }

    fun endpointNear(x: Float, y: Float): Int {
        if (hypot((x - from.x).toDouble(), (y - from.y).toDouble()) < 48) return 1
        if (hypot((x - to.x).toDouble(), (y - to.y).toDouble()) < 48) return 2
        return 0
    }

    override fun hit(x: Float, y: Float): Boolean {
        val dx = to.x - from.x
        val dy = to.y - from.y
        val len2 = dx * dx + dy * dy
        if (len2 == 0f) return hypot((x - from.x).toDouble(), (y - from.y).toDouble()) < 40
        var t = ((x - from.x) * dx + (y - from.y) * dy) / len2
        t = t.coerceIn(0f, 1f)
        val px = from.x + t * dx
        val py = from.y + t * dy
        return hypot((x - px).toDouble(), (y - py).toDouble()) < (width / 2 + 24)
    }

    override fun deepCopy(): EditElement =
        ArrowElement(PointF(from.x, from.y), PointF(to.x, to.y), color, width, headSize, kind)

    override fun toSvg(): String =
        "<line x1=\"${from.x}\" y1=\"${from.y}\" x2=\"${to.x}\" y2=\"${to.y}\" stroke=\"${hex(color)}\" stroke-width=\"$width\" stroke-linecap=\"round\"/>"
}

/** 形状（Issue12）：矩形/椭圆/直线/多边形，空心描边或半透明填充 */
class ShapeElement(
    var kind: ShapeKind,
    val points: MutableList<PointF>,
    var color: Int = ANNO_DEFAULT_COLOR,
    var width: Float = 6f,
    var fillColor: Int? = null // null=空心描边，非空=半透明填充
) : EditElement() {

    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val fillP = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    override fun draw(canvas: Canvas, doc: EditDocument, cache: MosaicCache) {
        stroke.color = color; stroke.strokeWidth = width
        fillColor?.let { fillP.color = it }
        when (kind) {
            ShapeKind.RECT, ShapeKind.SQUARE -> {
                val r = rectOf()
                if (fillColor != null) canvas.drawRect(r, fillP)
                canvas.drawRect(r, stroke)
            }
            ShapeKind.ELLIPSE -> {
                val r = rectOf()
                if (fillColor != null) canvas.drawOval(r, fillP)
                canvas.drawOval(r, stroke)
            }
            ShapeKind.CIRCLE -> {
                val r = rectOf()
                val cx = r.centerX()
                val cy = r.centerY()
                val radius = min(r.width(), r.height()) / 2f
                if (fillColor != null) canvas.drawCircle(cx, cy, radius, fillP)
                canvas.drawCircle(cx, cy, radius, stroke)
            }
            ShapeKind.LINE -> {
                if (points.size >= 2) canvas.drawLine(points[0].x, points[0].y, points[1].x, points[1].y, stroke)
            }
            ShapeKind.POLYGON -> {
                if (points.size >= 2) {
                    val path = Path()
                    path.moveTo(points[0].x, points[0].y)
                    for (i in 1 until points.size) path.lineTo(points[i].x, points[i].y)
                    path.close()
                    if (fillColor != null) canvas.drawPath(path, fillP)
                    canvas.drawPath(path, stroke)
                }
            }
        }
    }

    private fun rectOf(): RectF {
        val a = points[0]; val b = points[1]
        return RectF(min(a.x, b.x), min(a.y, b.y), max(a.x, b.x), max(a.y, b.y))
    }

    override fun bounds(): RectF {
        val b = RectF()
        if (points.isEmpty()) return RectF(0f, 0f, 0f, 0f)
        b.set(points[0].x, points[0].y, points[0].x, points[0].y)
        for (p in points) b.union(p.x, p.y)
        b.inset(-width, -width)
        return b
    }

    override fun translate(dx: Float, dy: Float) {
        for (p in points) p.offset(dx, dy)
    }

    override fun map(m: Matrix) {
        for (p in points) mapPoint(m, p)
        width *= scaleOf(m)
    }

    override fun hit(x: Float, y: Float): Boolean {
        val b = bounds()
        return b.contains(x, y)
    }

    override fun deepCopy(): EditElement =
        ShapeElement(kind, points.map { PointF(it.x, it.y) }.toMutableList(), color, width, fillColor)

    override fun toSvg(): String {
        val c = hex(color)
        val f = fillColor?.let { " fill=\"${hex(it)}\"" } ?: " fill=\"none\""
        return when (kind) {
            ShapeKind.RECT, ShapeKind.SQUARE -> {
                val r = rectOf()
                "<rect x=\"${r.left}\" y=\"${r.top}\" width=\"${r.width()}\" height=\"${r.height()}\"$f stroke=\"$c\" stroke-width=\"$width\"/>"
            }
            ShapeKind.ELLIPSE -> {
                val r = rectOf()
                "<ellipse cx=\"${r.centerX()}\" cy=\"${r.centerY()}\" rx=\"${r.width() / 2}\" ry=\"${r.height() / 2}\"$f stroke=\"$c\" stroke-width=\"$width\"/>"
            }
            ShapeKind.CIRCLE -> {
                val r = rectOf()
                val rad = min(r.width(), r.height()) / 2f
                "<circle cx=\"${r.centerX()}\" cy=\"${r.centerY()}\" r=\"$rad\"$f stroke=\"$c\" stroke-width=\"$width\"/>"
            }
            ShapeKind.LINE -> {
                val a = points[0]; val b = points[1]
                "<line x1=\"${a.x}\" y1=\"${a.y}\" x2=\"${b.x}\" y2=\"${b.y}\" stroke=\"$c\" stroke-width=\"$width\"/>"
            }
            ShapeKind.POLYGON -> {
                val pts = points.joinToString(" ") { "${it.x},${it.y}" }
                "<polygon points=\"$pts\"$f stroke=\"$c\" stroke-width=\"$width\"/>"
            }
        }
    }
}

/** 文字标注（Issue13） */
class TextElement(
    var pos: PointF,
    var text: String,
    var color: Int = ANNO_DEFAULT_COLOR,
    var size: Float = 48f
) : EditElement() {

    private val p = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun draw(canvas: Canvas, doc: EditDocument, cache: MosaicCache) {
        p.color = color; p.textSize = size
        val lines = text.split("\n")
        var y = pos.y + size
        for (line in lines) {
            canvas.drawText(line, pos.x, y, p)
            y += size * 1.2f
        }
    }

    override fun bounds(): RectF {
        val lines = text.split("\n")
        var w = 0f
        for (l in lines) w = max(w, p.measureText(l))
        return RectF(pos.x, pos.y, pos.x + w + size, pos.y + lines.size * size * 1.25f)
    }

    override fun translate(dx: Float, dy: Float) {
        pos.offset(dx, dy)
    }

    override fun map(m: Matrix) {
        mapPoint(m, pos)
        size *= scaleOf(m)
    }

    override fun hit(x: Float, y: Float): Boolean = bounds().contains(x, y)

    override fun deepCopy(): EditElement = TextElement(PointF(pos.x, pos.y), text, color, size)

    override fun toSvg(): String {
        val lines = text.split("\n")
        val sb = StringBuilder()
        var i = 0
        for (l in lines) {
            sb.append("<text x=\"${pos.x}\" y=\"${pos.y + size * (1 + i * 1.2f)}\" font-size=\"$size\" fill=\"${hex(color)}\">")
            sb.append(escape(l))
            sb.append("</text>")
            i++
        }
        return sb.toString()
    }

    private fun escape(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}

/** 自由画笔 / 半透明荧光笔（Issue13） */
class StrokeElement(
    val points: MutableList<PointF>,
    var color: Int = ANNO_DEFAULT_COLOR,
    var width: Float = 8f,
    var highlighter: Boolean = false
) : EditElement() {

    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }

    override fun draw(canvas: Canvas, doc: EditDocument, cache: MosaicCache) {
        p.color = if (highlighter) (color and 0x00FFFFFF or 0x55000000) else color
        p.strokeWidth = if (highlighter) width * 3f else width
        if (points.size >= 2) canvas.drawPath(smoothPath(points), p)
        else if (points.size == 1) {
            p.style = Paint.Style.FILL
            canvas.drawCircle(points[0].x, points[0].y, p.strokeWidth / 2, p)
            p.style = Paint.Style.STROKE
        }
    }

    override fun bounds(): RectF {
        if (points.isEmpty()) return RectF(0f, 0f, 0f, 0f)
        val b = RectF(points[0].x, points[0].y, points[0].x, points[0].y)
        for (pt in points) b.union(pt.x, pt.y)
        val pad = (if (highlighter) width * 3f else width) / 2 + 8
        b.inset(-pad, -pad)
        return b
    }

    override fun translate(dx: Float, dy: Float) {
        for (pt in points) pt.offset(dx, dy)
    }

    override fun map(m: Matrix) {
        for (pt in points) mapPoint(m, pt)
        width *= scaleOf(m)
    }

    override fun hit(x: Float, y: Float): Boolean {
        for (pt in points) if (abs(pt.x - x) < 24 && abs(pt.y - y) < 24) return true
        return false
    }

    override fun deepCopy(): EditElement = StrokeElement(
        points.map { PointF(it.x, it.y) }.toMutableList(), color, width, highlighter
    )

    override fun toSvg(): String {
        val pts = points.joinToString(" ") { "${it.x},${it.y}" }
        val op = if (highlighter) " stroke-opacity=\"0.33\"" else ""
        val w = if (highlighter) width * 3f else width
        return "<polyline points=\"$pts\" fill=\"none\" stroke=\"${hex(color)}\" stroke-width=\"$w\" stroke-linecap=\"round\" stroke-linejoin=\"round\"$op/>"
    }
}

fun hex(c: Int): String = String.format("#%06X", c and 0xFFFFFF)

fun smoothPath(pts: List<PointF>): Path {
    val path = Path()
    if (pts.isEmpty()) return path
    path.moveTo(pts[0].x, pts[0].y)
    if (pts.size == 1) return path
    for (i in 1 until pts.size - 1) {
        val mx = (pts[i].x + pts[i + 1].x) / 2
        val my = (pts[i].y + pts[i + 1].y) / 2
        path.quadTo(pts[i].x, pts[i].y, mx, my)
    }
    path.lineTo(pts.last().x, pts.last().y)
    return path
}
