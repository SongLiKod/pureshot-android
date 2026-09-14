package com.pureshot.screenshot.core.editor

import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.RectF

/**
 * 编辑文档（Issue8 多层级画布架构）：
 * 原图层(original/base) + 马赛克层 + 标注层 + 裁剪蒙版层（由 CropOverlay 承担）。
 * 全局撤销/重做基于元素快照；单元素独立删除；原图永久零损坏。
 */
class EditDocument(source: Bitmap) {

    /** 原始捕获图（永不破坏） */
    val original: Bitmap = source

    /** 当前工作底图（裁剪/旋转/翻转烘焙后） */
    @Volatile
    var base: Bitmap = original
        private set

    /** 累积变换矩阵：original 空间 → base 空间 */
    val cumulative = Matrix()

    /** 标注层 + 马赛克层元素（统一有序列表，绘制顺序即元素顺序） */
    val elements = mutableListOf<EditElement>()

    /** 马赛克层缓存 */
    val mosaicCache = MosaicCache().apply { bind(base) }

    var numberCounter = 0
        private set

    fun nextNumber(): Int = ++numberCounter

    private class Snapshot(
        val elements: List<EditElement>,
        val counter: Int,
        val base: Bitmap,
        val matrix: Matrix
    )

    private val undoStack = ArrayDeque<Snapshot>()
    private val redoStack = ArrayDeque<Snapshot>()

    fun baseRect(): RectF = RectF(0f, 0f, base.width.toFloat(), base.height.toFloat())

    fun pushHistory() {
        undoStack.addLast(
            Snapshot(elements.map { it.deepCopy() }, numberCounter, base, Matrix(cumulative))
        )
        if (undoStack.size > 60) undoStack.removeFirst()
        redoStack.clear()
    }

    fun canUndo(): Boolean = undoStack.isNotEmpty()
    fun canRedo(): Boolean = redoStack.isNotEmpty()

    fun undo(): Boolean {
        val snap = undoStack.removeLastOrNull() ?: return false
        redoStack.addLast(currentSnapshot())
        restore(snap)
        return true
    }

    fun redo(): Boolean {
        val snap = redoStack.removeLastOrNull() ?: return false
        undoStack.addLast(currentSnapshot())
        restore(snap)
        return true
    }

    private fun currentSnapshot() =
        Snapshot(elements.map { it.deepCopy() }, numberCounter, base, Matrix(cumulative))

    private fun restore(snap: Snapshot) {
        elements.clear()
        elements.addAll(snap.elements)
        numberCounter = snap.counter
        base = snap.base
        cumulative.set(snap.matrix)
        mosaicCache.bind(base)
    }

    /** 裁剪/旋转/翻转烘焙：生成新底图并同步映射所有元素坐标 */
    fun applyTransform(newBase: Bitmap, transform: Matrix) {
        pushHistory()
        for (e in elements) e.map(transform)
        base = newBase
        cumulative.postConcat(transform)
        mosaicCache.bind(newBase)
    }

    fun deleteElement(e: EditElement) {
        pushHistory()
        elements.remove(e)
    }

    /** 仅标注图层（不含马赛克隐私层与原图） */
    fun annotationOnly(): List<EditElement> =
        elements.filter { it !is MosaicElement }

    fun dispose() {
        mosaicCache.dispose()
    }
}
