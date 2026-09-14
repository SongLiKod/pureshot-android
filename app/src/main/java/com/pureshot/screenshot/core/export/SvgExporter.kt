package com.pureshot.screenshot.core.export

import com.pureshot.screenshot.core.editor.EditDocument
import com.pureshot.screenshot.core.editor.MosaicElement

/**
 * SVG 矢量导出（Issue15）：单独导出矢量标注图层，支持二次编辑。
 */
object SvgExporter {

    fun build(doc: EditDocument): String? {
        val parts = doc.annotationOnly().map { it.toSvg() }.filter { it.isNotBlank() }
        if (parts.isEmpty()) return null
        val w = doc.base.width
        val h = doc.base.height
        return buildString {
            append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
            append("<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"$w\" height=\"$h\" viewBox=\"0 0 $w $h\">\n")
            for (p in parts) append(p).append('\n')
            append("</svg>\n")
        }
    }
}
