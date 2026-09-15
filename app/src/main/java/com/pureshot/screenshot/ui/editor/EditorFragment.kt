package com.pureshot.screenshot.ui.editor

import android.app.Dialog
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.pureshot.screenshot.R
import com.pureshot.screenshot.core.Prefs
import com.pureshot.screenshot.core.editor.ArrowKind
import com.pureshot.screenshot.core.editor.EditDocument
import com.pureshot.screenshot.core.editor.NumberElement
import com.pureshot.screenshot.core.editor.NumberShape
import com.pureshot.screenshot.core.editor.ShapeKind
import com.pureshot.screenshot.core.editor.TextElement
import com.pureshot.screenshot.core.export.ExportManager
import com.pureshot.screenshot.core.util.BitmapUtil
import com.pureshot.screenshot.core.util.ErrorReporter
import kotlinx.coroutines.launch

/**
 * 专业编辑工具栏（Issue8-13 统一入口）。
 * 工具栏固定排序：撤销/重做 → 裁剪 → 马赛克 → 数字标注 → 箭头 → 形状 → 文字 → 画笔 → 荧光笔 → 橡皮擦 → 导出/保存
 */
class EditorFragment : Fragment() {

    companion object {
        const val ARG_PATH = "path"
    }

    private lateinit var canvas: LayerEditorView
    private lateinit var toolStrip: LinearLayout
    private lateinit var optionsPanel: LinearLayout
    private lateinit var cropLayer: FrameLayout
    private lateinit var cropOverlay: CropOverlayView
    private var btnUndo: ImageButton? = null
    private var btnRedo: ImageButton? = null

    private val vm: EditorViewModel by viewModels()
    private var boundDoc: EditDocument? = null

    private val toolCells = mutableMapOf<Tool, ToolCell>()

    private data class ToolCell(val root: LinearLayout, val icon: ImageButton, val label: TextView)

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View =
        inflater.inflate(R.layout.fragment_editor, container, false)

    override fun onViewCreated(view: View, s: Bundle?) {
        canvas = view.findViewById(R.id.editor_canvas)
        toolStrip = view.findViewById(R.id.tool_strip)
        optionsPanel = view.findViewById(R.id.options_panel)
        cropLayer = view.findViewById(R.id.crop_layer)
        cropOverlay = view.findViewById(R.id.crop_overlay)

        view.findViewById<ImageButton>(R.id.btn_editor_close).setOnClickListener {
            parentFragmentManager.popBackStack()
        }
        view.findViewById<View>(R.id.btn_editor_save).setOnClickListener { quickSave() }

        canvas.onTextRequested = { x, y -> showTextInput(null, x, y) }
        canvas.onTextEditRequested = { el -> showTextInput(el, 0f, 0f) }
        canvas.onNumberEditRequested = { el -> showNumberEdit(el) }
        canvas.onSelectionChanged = { el ->
            if (canvas.tool == Tool.SELECT) showOptions(Tool.SELECT, el != null)
        }
        canvas.onDocumentChanged = { updateUndoState() }

        buildToolbar(view)

        // ViewModel 状态驱动：后台协程解码大图，杜绝 ANR
        viewLifecycleOwner.lifecycleScope.launch {
            vm.document.collect { d ->
                if (d != null && d !== boundDoc) {
                    boundDoc = d
                    canvas.setDocument(d)
                    cropOverlay.doc = d
                    showOptions(canvas.tool, false)
                    updateUndoState()
                }
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            vm.loadFailed.collect { failed ->
                if (failed && isAdded) {
                    ErrorReporter.toastRes(requireContext(), R.string.capture_fail)
                    vm.consumeLoadFailure()
                }
            }
        }
        arguments?.getString(ARG_PATH)?.let { vm.load(requireContext().applicationContext, it) }
    }

    // ---------- 工具栏（固定排序） ----------

    private fun buildToolbar(root: View) {
        val undoCell = addToolCell(R.drawable.ic_undo, getString(R.string.undo)) {
            if (!canvas.undo()) ErrorReporter.toastRes(requireContext(), R.string.undo_empty)
            updateUndoState()
        }
        btnUndo = undoCell.icon
        toolStrip.addView(undoCell.root)

        val redoCell = addToolCell(R.drawable.ic_redo, getString(R.string.redo)) {
            if (!canvas.redo()) ErrorReporter.toastRes(requireContext(), R.string.redo_empty)
            updateUndoState()
        }
        btnRedo = redoCell.icon
        toolStrip.addView(redoCell.root)

        val entries = listOf(
            Tool.SELECT to R.drawable.ic_select,
            Tool.CROP to R.drawable.ic_crop,
            Tool.MOSAIC_RECT to R.drawable.ic_mosaic,
            Tool.NUMBER to R.drawable.ic_number,
            Tool.ARROW to R.drawable.ic_arrow,
            Tool.SHAPE to R.drawable.ic_shape,
            Tool.TEXT to R.drawable.ic_text,
            Tool.PEN to R.drawable.ic_pen,
            Tool.HIGHLIGHTER to R.drawable.ic_highlighter,
            Tool.ERASER to R.drawable.ic_eraser
        )
        for ((tool, icon) in entries) {
            val cell = addToolCell(icon, toolName(tool)) { selectTool(tool) }
            toolCells[tool] = cell
            toolStrip.addView(cell.root)
        }
        toolStrip.addView(addToolCell(R.drawable.ic_export, getString(R.string.export)) { showExportDialog() }.root)
        selectTool(Tool.SELECT)
    }

    /** 图标 + 文字标签的工具栏单元，避免用户只能靠猜图标含义 */
    private fun addToolCell(iconRes: Int, labelText: String, onClick: () -> Unit): ToolCell {
        val icon = ImageButton(requireContext()).apply {
            setImageResource(iconRes)
            contentDescription = labelText
            background = null
            setPadding(dp(6), dp(6), dp(6), dp(6))
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            layoutParams = LinearLayout.LayoutParams(dp(40), dp(40))
            setOnClickListener { onClick() }
        }
        val label = TextView(requireContext()).apply {
            text = labelText
            textSize = 10f
            gravity = Gravity.CENTER
            maxLines = 1
            setTextColor(muted())
        }
        val root = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(2), dp(2), dp(2), dp(2))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = dp(2); marginEnd = dp(2) }
            addView(icon)
            addView(label)
        }
        return ToolCell(root, icon, label)
    }

    private fun toolName(t: Tool) = getString(
        when (t) {
            Tool.SELECT -> R.string.tool_select
            Tool.CROP -> R.string.tool_crop
            Tool.MOSAIC_RECT, Tool.MOSAIC_CIRCLE, Tool.MOSAIC_FREE, Tool.BLUR -> R.string.tool_mosaic
            Tool.NUMBER -> R.string.tool_number
            Tool.ARROW -> R.string.tool_arrow
            Tool.SHAPE -> R.string.tool_shape
            Tool.TEXT -> R.string.tool_text
            Tool.PEN -> R.string.tool_pen
            Tool.HIGHLIGHTER -> R.string.tool_highlighter
            Tool.ERASER -> R.string.tool_eraser
        }
    )

    private fun selectTool(tool: Tool) {
        cropLayer.visibility = if (tool == Tool.CROP) View.VISIBLE else View.GONE
        if (tool == Tool.CROP) {
            cropOverlay.bind()
            highlightTool(Tool.CROP)
            showOptions(Tool.CROP, false)
            return
        }
        val mosaicFamily = setOf(Tool.MOSAIC_RECT, Tool.MOSAIC_CIRCLE, Tool.MOSAIC_FREE, Tool.BLUR)
        val active = if (tool == Tool.MOSAIC_RECT && canvas.tool in mosaicFamily) canvas.tool else tool
        canvas.tool = active
        highlightTool(tool)
        showOptions(active, false)
    }

    private fun highlightTool(tool: Tool) {
        for ((t, cell) in toolCells) {
            val on = if (tool == Tool.MOSAIC_RECT) t == Tool.MOSAIC_RECT else t == tool
            cell.icon.setColorFilter(if (on) brand() else Color.TRANSPARENT)
            cell.label.setTextColor(if (on) brand() else muted())
            cell.root.background = if (on) selectedCellDrawable() else null
        }
    }

    private fun selectedCellDrawable(): android.graphics.drawable.Drawable =
        android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = dp(12).toFloat()
            setColor(androidx.core.graphics.ColorUtils.setAlphaComponent(brand(), 0x22))
        }

    private fun brand(): Int =
        com.google.android.material.color.MaterialColors.getColor(
            requireView(), com.google.android.material.R.attr.colorPrimary
        )

    private fun updateUndoState() {
        val d = canvas.doc ?: return
        btnUndo?.alpha = if (d.canUndo()) 1f else 0.35f
        btnRedo?.alpha = if (d.canRedo()) 1f else 0.35f
    }

    // ---------- 选项面板 ----------

    private fun showOptions(tool: Tool, hasSelection: Boolean) {
        optionsPanel.removeAllViews()
        optionsPanel.visibility = View.VISIBLE
        when (tool) {
            Tool.CROP -> buildCropOptions()
            Tool.MOSAIC_RECT, Tool.MOSAIC_CIRCLE, Tool.MOSAIC_FREE, Tool.BLUR -> buildMosaicOptions()
            Tool.NUMBER -> buildNumberOptions()
            Tool.ARROW -> buildArrowOptions()
            Tool.SHAPE -> buildShapeOptions()
            Tool.TEXT -> buildOptionsRow(sizeSeek = true, colorRow = true)
            Tool.PEN -> buildOptionsRow(widthSeek = true, colorRow = true)
            Tool.HIGHLIGHTER -> buildOptionsRow(widthSeek = true, colorRow = true)
            Tool.ERASER -> buildOptionsRow(widthSeek = true, colorRow = false, eraser = true)
            Tool.SELECT -> buildSelectOptions(hasSelection)
        }
    }

    private fun buildSelectOptions(hasSelection: Boolean) {
        if (!hasSelection) {
            optionsPanel.addView(smallText(getString(R.string.tool_select) + "：点选元素可拖动；箭头拖端点；序号双指缩放；双击文字/序号可编辑"))
            return
        }
        val row = horizontalRow()
        row.addView(actionButton(R.drawable.ic_delete, getString(R.string.delete)) {
            canvas.deleteSelected()
            showOptions(Tool.SELECT, false)
        })
        optionsPanel.addView(row)
    }

    private fun buildMosaicOptions() {
        val row = horizontalRow()
        row.addView(chipButton(R.string.mosaic_rect, canvas.tool == Tool.MOSAIC_RECT) { setMosaic(Tool.MOSAIC_RECT) })
        row.addView(chipButton(R.string.mosaic_circle, canvas.tool == Tool.MOSAIC_CIRCLE) { setMosaic(Tool.MOSAIC_CIRCLE) })
        row.addView(chipButton(R.string.mosaic_free, canvas.tool == Tool.MOSAIC_FREE) { setMosaic(Tool.MOSAIC_FREE) })
        row.addView(chipButton(R.string.mosaic_blur, canvas.tool == Tool.BLUR) { setMosaic(Tool.BLUR) })
        optionsPanel.addView(row)
        val seg = horizontalRow()
        if (canvas.tool == Tool.BLUR) {
            seg.addView(chipButton(R.string.blur_low, canvas.blurRadius == LayerEditorView.blurOf(0)) { canvas.blurRadius = LayerEditorView.blurOf(0); canvas.rememberParams(); showOptions(Tool.BLUR, false) })
            seg.addView(chipButton(R.string.blur_mid, canvas.blurRadius == LayerEditorView.blurOf(1)) { canvas.blurRadius = LayerEditorView.blurOf(1); canvas.rememberParams(); showOptions(Tool.BLUR, false) })
            seg.addView(chipButton(R.string.blur_high, canvas.blurRadius == LayerEditorView.blurOf(2)) { canvas.blurRadius = LayerEditorView.blurOf(2); canvas.rememberParams(); showOptions(Tool.BLUR, false) })
        } else {
            seg.addView(chipButton(R.string.size_small, canvas.mosaicBlock == LayerEditorView.blockOf(0)) { canvas.mosaicBlock = LayerEditorView.blockOf(0); canvas.rememberParams(); showOptions(canvas.tool, false) })
            seg.addView(chipButton(R.string.size_medium, canvas.mosaicBlock == LayerEditorView.blockOf(1)) { canvas.mosaicBlock = LayerEditorView.blockOf(1); canvas.rememberParams(); showOptions(canvas.tool, false) })
            seg.addView(chipButton(R.string.size_large, canvas.mosaicBlock == LayerEditorView.blockOf(2)) { canvas.mosaicBlock = LayerEditorView.blockOf(2); canvas.rememberParams(); showOptions(canvas.tool, false) })
        }
        optionsPanel.addView(seg)
    }

    private fun setMosaic(t: Tool) {
        canvas.tool = t
        showOptions(t, false)
    }

    private fun buildArrowOptions() {
        val row = horizontalRow()
        row.addView(chipButton(R.string.arrow_single, canvas.arrowKind == ArrowKind.SINGLE) { canvas.arrowKind = ArrowKind.SINGLE; showOptions(Tool.ARROW, false) })
        row.addView(chipButton(R.string.arrow_double, canvas.arrowKind == ArrowKind.DOUBLE) { canvas.arrowKind = ArrowKind.DOUBLE; showOptions(Tool.ARROW, false) })
        optionsPanel.addView(row)
        buildOptionsRow(widthSeek = true, colorRow = true, headSeek = true)
    }

    private fun buildShapeOptions() {
        val row = linearRow()
        row.addView(chipButton(R.string.shape_rect, canvas.shapeKind == ShapeKind.RECT) { canvas.shapeKind = ShapeKind.RECT; showOptions(Tool.SHAPE, false) })
        row.addView(chipButton(R.string.shape_square, canvas.shapeKind == ShapeKind.SQUARE) { canvas.shapeKind = ShapeKind.SQUARE; showOptions(Tool.SHAPE, false) })
        row.addView(chipButton(R.string.shape_ellipse, canvas.shapeKind == ShapeKind.ELLIPSE) { canvas.shapeKind = ShapeKind.ELLIPSE; showOptions(Tool.SHAPE, false) })
        row.addView(chipButton(R.string.shape_circle, canvas.shapeKind == ShapeKind.CIRCLE) { canvas.shapeKind = ShapeKind.CIRCLE; showOptions(Tool.SHAPE, false) })
        row.addView(chipButton(R.string.shape_line, canvas.shapeKind == ShapeKind.LINE) { canvas.shapeKind = ShapeKind.LINE; showOptions(Tool.SHAPE, false) })
        row.addView(chipButton(R.string.shape_polygon, canvas.shapeKind == ShapeKind.POLYGON) { canvas.shapeKind = ShapeKind.POLYGON; showOptions(Tool.SHAPE, false) })
        row.addView(chipButton(R.string.fill_on, canvas.fillEnabled) { canvas.fillEnabled = !canvas.fillEnabled; showOptions(Tool.SHAPE, false) })
        optionsPanel.addView(horizontalScroll(row))
        if (canvas.shapeKind == ShapeKind.POLYGON) {
            optionsPanel.addView(smallText(getString(R.string.polygon_finish)))
        }
        buildOptionsRow(widthSeek = true, colorRow = true)
    }

    private fun buildNumberOptions() {
        val row = linearRow()
        row.addView(chipButton(R.string.number_shape_round, canvas.numberShape == NumberShape.ROUND_RECT) {
            canvas.numberShape = NumberShape.ROUND_RECT; showOptions(Tool.NUMBER, false)
        })
        row.addView(chipButton(R.string.number_shape_circle, canvas.numberShape == NumberShape.CIRCLE) {
            canvas.numberShape = NumberShape.CIRCLE; showOptions(Tool.NUMBER, false)
        })
        row.addView(chipButton(R.string.number_shape_square, canvas.numberShape == NumberShape.SQUARE) {
            canvas.numberShape = NumberShape.SQUARE; showOptions(Tool.NUMBER, false)
        })
        optionsPanel.addView(horizontalScroll(row))
        buildOptionsRow(sizeSeek = true, colorRow = true)
    }

    private fun buildOptionsRow(
        widthSeek: Boolean = false,
        sizeSeek: Boolean = false,
        headSeek: Boolean = false,
        colorRow: Boolean = false,
        eraser: Boolean = false
    ) {
        val row = horizontalRow()
        if (colorRow) {
            val ctx = requireContext()
            val palette = intArrayOf(
                androidx.core.content.ContextCompat.getColor(ctx, R.color.anno_red),
                androidx.core.content.ContextCompat.getColor(ctx, R.color.anno_orange),
                androidx.core.content.ContextCompat.getColor(ctx, R.color.anno_yellow),
                androidx.core.content.ContextCompat.getColor(ctx, R.color.anno_green),
                androidx.core.content.ContextCompat.getColor(ctx, R.color.anno_blue),
                androidx.core.content.ContextCompat.getColor(ctx, R.color.anno_black),
                androidx.core.content.ContextCompat.getColor(ctx, R.color.anno_white)
            )
            for (c in palette) {
                val swatch = View(requireContext()).apply {
                    layoutParams = LinearLayout.LayoutParams(dp(30), dp(30)).apply { marginEnd = dp(8) }
                    background = colorDrawable(c, canvas.color == c)
                    setOnClickListener {
                        canvas.color = c
                        canvas.rememberParams()
                        showOptions(canvas.tool, false)
                    }
                }
                row.addView(swatch)
            }
        }
        optionsPanel.addView(row)
        if (widthSeek || eraser) {
            if (eraser) {
                optionsPanel.addView(seekRow(getString(R.string.stroke_width), 16f, 160f, canvas.eraserWidth) {
                    canvas.eraserWidth = it
                })
            } else {
                optionsPanel.addView(seekRow(getString(R.string.stroke_width), 2f, 40f, canvas.strokeWidth) {
                    canvas.strokeWidth = it
                    canvas.rememberParams()
                })
            }
        }
        if (sizeSeek) {
            optionsPanel.addView(seekRow(getString(R.string.text_size), 24f, 160f, canvas.textSize) {
                canvas.textSize = it
                canvas.rememberParams()
            })
        }
        if (headSeek) {
            optionsPanel.addView(seekRow(getString(R.string.head_size), 12f, 80f, canvas.arrowHead) {
                canvas.arrowHead = it
                canvas.rememberParams()
            })
        }
    }

    private fun buildCropOptions() {
        val ratios = horizontalScroll(
            linearRow().apply {
                addView(chipButton(R.string.ratio_free, cropOverlay.ratio == 0f) { cropOverlay.ratio = 0f; showOptions(Tool.CROP, false) })
                val doc = canvas.doc
                if (doc != null) {
                    addView(chipButton(R.string.ratio_original, false) {
                        cropOverlay.ratio = doc.base.width.toFloat() / doc.base.height
                        showOptions(Tool.CROP, false)
                    })
                }
                val presets = listOf(
                    "1:1" to 1f, "4:3" to 4f / 3f, "3:4" to 3f / 4f,
                    "16:9" to 16f / 9f, "9:16" to 9f / 16f, "2:1" to 2f,
                    "3:2" to 3f / 2f, "5:4" to 5f / 4f
                )
                for ((label, r) in presets) {
                    addView(textChip(label, cropOverlay.ratio == r) {
                        cropOverlay.ratio = r
                        showOptions(Tool.CROP, false)
                    })
                }
            }
        )
        optionsPanel.addView(ratios)
        val tools = horizontalScroll(
            linearRow().apply {
                addView(actionButton(R.drawable.ic_rotate, getString(R.string.rotate_90)) { cropOverlay.rotate90() })
                addView(actionButton(R.drawable.ic_flip_h, getString(R.string.flip_h)) { cropOverlay.flip(true) })
                addView(actionButton(R.drawable.ic_flip_v, getString(R.string.flip_v)) { cropOverlay.flip(false) })
                addView(actionButton(R.drawable.ic_grid, getString(R.string.grid_line), cropOverlay.showGrid) {
                    cropOverlay.showGrid = !cropOverlay.showGrid
                    showOptions(Tool.CROP, false)
                })
                addView(actionButton(R.drawable.ic_trim, getString(R.string.trim_black)) { cropOverlay.autoTrimBlack() })
                addView(actionButton(R.drawable.ic_level, getString(R.string.crop_reset)) { cropOverlay.reset() })
                addView(actionButton(R.drawable.ic_check, getString(R.string.done)) {
                    cropOverlay.confirm()
                    selectTool(Tool.SELECT)
                })
                addView(actionButton(R.drawable.ic_close, getString(R.string.cancel)) {
                    selectTool(Tool.SELECT)
                })
            }
        )
        optionsPanel.addView(tools)
        optionsPanel.addView(seekRow(getString(R.string.level_correct), -10f, 10f, cropOverlay.fineAngle) {
            cropOverlay.fineAngle = it
        })
    }

    // ---------- 对话框 ----------

    private fun showTextInput(existing: TextElement?, x: Float, y: Float) {
        val input = EditText(requireContext()).apply {
            hint = getString(R.string.text_input_title)
            if (existing != null) setText(existing.text)
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.text_input_title)
            .setView(input)
            .setPositiveButton(R.string.ok) { _, _ ->
                val t = input.text.toString()
                if (t.isNotBlank()) {
                    if (existing != null) canvas.editExistingText(existing, t)
                    else canvas.addText(x, y, t)
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showNumberEdit(el: NumberElement) {
        val input = EditText(requireContext()).apply {
            setText(el.value.toString())
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.number_edit_title)
            .setView(input)
            .setPositiveButton(R.string.ok) { _, _ ->
                val v = input.text.toString().toIntOrNull()
                if (v != null) canvas.updateNumber(el, v)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** 顶部“保存”按钮：按当前默认设置一键保存，无需再进导出弹窗 */
    private fun quickSave() {
        val doc = canvas.doc ?: return
        val fmt = Prefs.defaultFormat
        if (fmt == "svg" && doc.annotationOnly().isEmpty()) {
            ErrorReporter.toastRes(requireContext(), R.string.svg_empty)
            return
        }
        val quality = when (fmt) {
            "jpg", "jpeg" -> Prefs.jpgQuality
            "webp" -> Prefs.webpQuality
            else -> 100
        }
        ExportManager.saveDocument(
            requireContext().applicationContext, doc, fmt, quality, Prefs.exportScope, emptyList()
        ) { uri ->
            if (isAdded) {
                if (uri != null) ErrorReporter.toast(requireContext(), getString(R.string.saved_to, uri))
                else ErrorReporter.toastRes(requireContext(), R.string.save_fail)
            }
        }
    }

    private fun showExportDialog() {
        val doc = canvas.doc ?: return
        val fmts = ExportManager.FORMATS.toTypedArray()
        val fmtLabels = fmts.map { getString(
            when (it) {
                "webp" -> R.string.fmt_webp
                "png" -> R.string.fmt_png
                "jpg" -> R.string.fmt_jpg
                "jpeg" -> R.string.fmt_jpeg
                "svg" -> R.string.fmt_svg
                else -> R.string.fmt_ico
            }
        ) }.toTypedArray()
        val scopes = arrayOf(getString(R.string.scope_full), getString(R.string.scope_original), getString(R.string.scope_annotation))
        val icoLabels = arrayOf("16px", "32px", "48px", "64px")
        val icoPicked = booleanArrayOf(false, true, false, true)
        var fmtIndex = fmts.indexOf(Prefs.defaultFormat).coerceAtLeast(0)
        var scopeIndex = Prefs.exportScope
        var quality = if (fmts[fmtIndex] == "webp") Prefs.webpQuality else Prefs.jpgQuality
        var scaleVal = Prefs.exportScale

        val container = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(12), dp(24), 0)
        }
        val fmtView = android.widget.RadioGroup(requireContext())
        fmtLabels.forEachIndexed { i, label ->
            fmtView.addView(android.widget.RadioButton(requireContext()).apply {
                text = label; id = 1 + i; isChecked = i == fmtIndex
                setOnClickListener { fmtIndex = i }
            })
        }
        container.addView(fmtView)
        val qualityView = TextView(requireContext()).apply { text = getString(R.string.quality_label, quality) }
        val qualitySeek = SeekBar(requireContext()).apply {
            max = 2
            progress = when (quality) { 60 -> 0; 100 -> 2; else -> 1 }
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                    quality = when (p) { 0 -> 60; 2 -> 100; else -> 80 }
                    qualityView.text = getString(R.string.quality_label, quality)
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }
        container.addView(qualityView)
        container.addView(qualitySeek)
        // 高级导出参数：导出缩放
        val scaleRow = horizontalRow().apply {
            addView(TextView(requireContext()).apply {
                text = getString(R.string.export_scale); textSize = 13f; setTextColor(muted())
            })
            for (s in intArrayOf(100, 75, 50)) {
                addView(textChip("$s%", scaleVal == s) { scaleVal = s })
            }
        }
        container.addView(scaleRow)
        val scopeView = android.widget.RadioGroup(requireContext())
        scopes.forEachIndexed { i, label ->
            scopeView.addView(android.widget.RadioButton(requireContext()).apply {
                text = label; id = 100 + i; isChecked = i == scopeIndex
                setOnClickListener { scopeIndex = i }
            })
        }
        container.addView(scopeView)
        val icoGroup = android.widget.LinearLayout(requireContext()).apply { orientation = android.widget.LinearLayout.HORIZONTAL }
        icoLabels.forEachIndexed { i, label ->
            icoGroup.addView(android.widget.CheckBox(requireContext()).apply {
                text = label; isChecked = icoPicked[i]
                setOnCheckedChangeListener { _, checked -> icoPicked[i] = checked }
            })
        }
        container.addView(icoGroup)

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.export_title)
            .setView(container)
            .setPositiveButton(R.string.export) { _, _ ->
                val fmt = fmts[fmtIndex]
                Prefs.defaultFormat = fmt
                when (fmt) {
                    "jpg", "jpeg" -> Prefs.jpgQuality = quality
                    "webp" -> Prefs.webpQuality = quality
                }
                Prefs.exportScale = scaleVal
                Prefs.exportScope = scopeIndex
                val sizes = icoLabels.indices.filter { icoPicked[it] }.map { intArrayOf(16, 32, 48, 64)[it] }
                if (fmt == "svg" && doc.annotationOnly().isEmpty()) {
                    ErrorReporter.toastRes(requireContext(), R.string.svg_empty)
                } else {
                    ExportManager.saveDocument(
                        requireContext().applicationContext, doc, fmt, quality, scopeIndex, sizes
                    ) { uri ->
                        if (isAdded) {
                            if (uri != null) ErrorReporter.toast(requireContext(), getString(R.string.saved_to, uri))
                            else ErrorReporter.toastRes(requireContext(), R.string.save_fail)
                        }
                    }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // ---------- 小工具 ----------

    private fun brandColor(): Int = androidx.core.content.ContextCompat.getColor(requireContext(), R.color.brand_primary)

    private fun muted(): Int = com.google.android.material.color.MaterialColors.getColor(
        requireView(), com.google.android.material.R.attr.colorOnSurfaceVariant
    )

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun horizontalRow() = LinearLayout(requireContext()).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    private fun linearRow() = LinearLayout(requireContext()).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    private fun horizontalScroll(inner: View) = HorizontalScrollView(requireContext()).apply {
        addView(inner)
        isHorizontalScrollBarEnabled = false
    }

    private fun smallText(s: String) = TextView(requireContext()).apply {
        text = s
        textSize = 12f
        setTextColor(muted())
        setPadding(0, dp(6), 0, dp(6))
    }

    private fun chipButton(labelRes: Int, selected: Boolean, onClick: () -> Unit): View {
        val tv = TextView(requireContext()).apply {
            text = getString(labelRes)
            textSize = 13f
            setPadding(dp(14), dp(8), dp(14), dp(8))
            background = chipDrawable(selected)
            setOnClickListener { onClick() }
        }
        tv.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { marginEnd = dp(8) }
        return tv
    }

    private fun textChip(label: String, selected: Boolean, onClick: () -> Unit): View {
        val tv = TextView(requireContext()).apply {
            text = label
            textSize = 13f
            setPadding(dp(14), dp(8), dp(14), dp(8))
            background = chipDrawable(selected)
            setOnClickListener { onClick() }
        }
        tv.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { marginEnd = dp(8) }
        return tv
    }

    private fun actionButton(iconRes: Int, desc: String, selected: Boolean = true, onClick: () -> Unit): View {
        val b = ImageButton(requireContext()).apply {
            setImageResource(iconRes)
            contentDescription = desc
            setPadding(dp(10), dp(10), dp(10), dp(10))
            background = chipDrawable(selected)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setOnClickListener { onClick() }
        }
        b.layoutParams = LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginEnd = dp(8) }
        return b
    }

    private fun seekRow(label: String, min: Float, max: Float, value: Float, onChange: (Float) -> Unit): View {
        val row = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(4), 0, dp(4))
        }
        row.addView(TextView(requireContext()).apply {
            text = label; textSize = 12f
            setTextColor(muted())
            val lp = LinearLayout.LayoutParams(dp(80), ViewGroup.LayoutParams.WRAP_CONTENT)
            layoutParams = lp
        })
        val seek = SeekBar(requireContext()).apply {
            this.max = 100
            progress = (((value - min) / (max - min)) * 100).toInt().coerceIn(0, 100)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                    onChange(min + (max - min) * p / 100f)
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }
        row.addView(seek, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        return row
    }

    private fun chipDrawable(selected: Boolean): android.graphics.drawable.Drawable =
        android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = dp(16).toFloat()
            setColor(androidx.core.graphics.ColorUtils.setAlphaComponent(brandColor(), if (selected) 0x33 else 0x22))
            setStroke(dp(1), if (selected) brandColor() else 0x00000000)
        }

    private fun colorDrawable(color: Int, selected: Boolean): android.graphics.drawable.Drawable =
        android.graphics.drawable.LayerDrawable(arrayOf(
            android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(color)
            },
            android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setStroke(dp(2), if (selected) brandColor() else 0x00000000)
            }
        ))
}
