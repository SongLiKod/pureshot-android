package com.pureshot.screenshot.core.export

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.documentfile.provider.DocumentFile
import com.pureshot.screenshot.core.Prefs
import com.pureshot.screenshot.core.editor.EditDocument
import com.pureshot.screenshot.core.editor.MosaicElement
import com.pureshot.screenshot.core.util.ErrorReporter
import com.pureshot.screenshot.core.util.NamingUtil
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream

/**
 * 全格式导出（Issue14-16）：PNG/JPG/JPEG/WebP/GIF/SVG/ICO；
 * 自定义保存目录、命名规则、相册显示开关、原图自动保存、局部/全图/仅标注图层导出。
 * WebP 为默认导出格式（平衡画质与体积）。
 */
object ExportManager {

    val FORMATS = listOf("webp", "png", "jpg", "jpeg", "svg", "ico")

    /** 直接保存一张位图（预览页快捷保存/原图自动保存） */
    fun saveBitmap(
        ctx: Context,
        bmp: Bitmap,
        format: String,
        quality: Int,
        withAnnotations: Boolean = false,
        callback: (String?) -> Unit
    ) {
        Thread {
            try {
                val uri = saveRasterBytes(ctx, bmp, format, quality)
                callbackOnMain(callback, uri)
            } catch (e: Throwable) {
                callbackOnMain(callback, null)
                ErrorReporter.dialog(ctx, e)
            }
        }.start()
    }

    /**
     * 从编辑文档导出（Issue16）：
     * scope: 0=全图(含裁剪) 1=未裁剪原图 2=仅标注图层
     */
    fun saveDocument(
        ctx: Context,
        doc: EditDocument,
        format: String,
        quality: Int,
        scope: Int,
        icoSizes: List<Int>,
        callback: (String?) -> Unit
    ) {
        Thread {
            try {
                val result = when (format) {
                    "svg" -> saveSvg(ctx, doc)
                    "ico" -> saveIco(ctx, doc, scope, icoSizes)
                    else -> {
                        val bmp = composite(doc, scope)
                        val r = saveRasterBytes(ctx, bmp, format, quality)
                        if (bmp !== doc.base && bmp !== doc.original) bmp.recycle()
                        r
                    }
                }
                callbackOnMain(callback, result)
            } catch (e: Throwable) {
                callbackOnMain(callback, null)
                ErrorReporter.dialog(ctx, e)
            }
        }.start()
    }

    /** GIF 多图合成动态演示图：可调帧率、循环播放 */
    fun saveGif(ctx: Context, frames: List<Bitmap>, fps: Int, loop: Int, callback: (String?) -> Unit) {
        Thread {
            try {
                if (frames.isEmpty()) {
                    callbackOnMain(callback, null)
                    return@Thread
                }
                val encoder = GifEncoder()
                encoder.setDelay(maxOf(10, 1000 / fps.coerceIn(1, 24)))
                encoder.setRepeat(loop.coerceIn(0, 65535))
                frames.forEach { encoder.addFrame(it) }
                val bytes = encoder.finish()
                val name = NamingUtil.render(Prefs.nameTemplate, frames[0].width, frames[0].height, "gif")
                val uri = writeBytes(ctx, bytes, name, "image/gif")
                callbackOnMain(callback, uri)
            } catch (e: Throwable) {
                callbackOnMain(callback, null)
                ErrorReporter.dialog(ctx, e)
            }
        }.start()
    }

    private fun callbackOnMain(callback: (String?) -> Unit, v: String?) {
        android.os.Handler(android.os.Looper.getMainLooper()).post { callback(v) }
    }

    // ---------- 图层合成 ----------

    fun composite(doc: EditDocument, scope: Int): Bitmap {
        return when (scope) {
            1 -> compositeOnOriginal(doc)
            2 -> {
                val out = Bitmap.createBitmap(doc.base.width, doc.base.height, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(out)
                for (e in doc.elements) if (e !is MosaicElement) e.draw(canvas, doc)
                out
            }
            else -> {
                val out = doc.base.copy(Bitmap.Config.ARGB_8888, true)
                val canvas = Canvas(out)
                for (e in doc.elements) e.draw(canvas, doc)
                out
            }
        }
    }

    /** 未裁剪原图：元素经累积变换矩阵的逆变换映射回原图空间绘制 */
    private fun compositeOnOriginal(doc: EditDocument): Bitmap {
        val out = doc.original.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(out)
        val inv = Matrix()
        if (!doc.cumulative.invert(inv)) return out
        val origCache = com.pureshot.screenshot.core.editor.MosaicCache().apply { bind(doc.original) }
        for (e in doc.elements) {
            val copy = e.deepCopy()
            copy.map(inv)
            copy.draw(canvas, doc, origCache)
        }
        origCache.dispose()
        return out
    }

    // ---------- 栅格 ----------

    @Suppress("DEPRECATION")
    private fun saveRasterBytes(ctx: Context, bmp: Bitmap, format: String, quality: Int): String? {
        val compressFormat = when (format) {
            "png" -> Bitmap.CompressFormat.PNG
            "jpg", "jpeg" -> Bitmap.CompressFormat.JPEG
            else -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
                Bitmap.CompressFormat.WEBP_LOSSY
            else Bitmap.CompressFormat.WEBP
        }
        // 高级导出参数：导出缩放（大图采样压缩，避免 OOM）
        val scale = Prefs.exportScale.coerceIn(50, 100)
        val target = if (scale < 100) com.pureshot.screenshot.core.util.BitmapUtil.scale(bmp, bmp.width * scale / 100) else bmp
        // 高级导出参数：WebP 独立质量档位
        val q = when (format) {
            "png" -> 100
            "jpg", "jpeg" -> quality
            else -> Prefs.webpQuality
        }
        val baos = ByteArrayOutputStream()
        target.compress(compressFormat, q.coerceIn(0, 100), baos)
        val ext = when (format) {
            "png" -> "png"
            "jpg", "jpeg" -> "jpg"
            else -> "webp"
        }
        val mime = when (ext) {
            "png" -> "image/png"
            "jpg" -> "image/jpeg"
            else -> "image/webp"
        }
        val name = NamingUtil.render(Prefs.nameTemplate, target.width, target.height, ext)
        if (target !== bmp) target.recycle()
        return writeBytes(ctx, baos.toByteArray(), name, mime)
    }

    private fun saveSvg(ctx: Context, doc: EditDocument): String? {
        val svg = SvgExporter.build(doc) ?: return null
        val name = NamingUtil.render(Prefs.nameTemplate, doc.base.width, doc.base.height, "svg")
        return writeBytes(ctx, svg.toByteArray(), name, "image/svg+xml")
    }

    private fun saveIco(ctx: Context, doc: EditDocument, scope: Int, sizes: List<Int>): String? {
        val bmp = composite(doc, scope)
        val useSizes = sizes.ifEmpty { listOf(32, 64) }
        val bytes = IcoWriter.write(bmp, useSizes)
        if (bmp !== doc.base && bmp !== doc.original) bmp.recycle()
        val side = useSizes.maxOrNull() ?: 64
        val name = NamingUtil.render(Prefs.nameTemplate, side, side, "ico")
        return writeBytes(ctx, bytes, name, "image/x-icon")
    }

    // ---------- 存储写入（媒体存储 API / SAF 自定义目录 / 相册开关） ----------

    private fun writeBytes(ctx: Context, bytes: ByteArray, name: String, mime: String): String? {
        val tree = Prefs.saveTreeUri
        if (tree.isNotBlank()) {
            try {
                val treeUri = Uri.parse(tree)
                val dir = DocumentFile.fromTreeUri(ctx, treeUri)
                if (dir != null) {
                    val file = dir.createFile(mime, name.substringBeforeLast('.'))
                    if (file != null) {
                        ctx.contentResolver.openOutputStream(file.uri)?.use { it.write(bytes) }
                        return file.uri.toString()
                    }
                }
            } catch (e: Throwable) { /* 自定义目录失效：自动回退默认存储 */ }
        }
        return if (Prefs.showInGallery) {
            writeToMediaStore(ctx, bytes, name, mime)
        } else {
            writeToAppDir(ctx, bytes, name)
        }
    }

    private fun writeToMediaStore(ctx: Context, bytes: ByteArray, name: String, mime: String): String? {
        val values = android.content.ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, mime)
            put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/PureShot")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val resolver = ctx.contentResolver
        val uri: Uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return null
        resolver.openOutputStream(uri)?.use { it.write(bytes) }
        val done = android.content.ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }
        resolver.update(uri, done, null, null)
        return uri.toString()
    }

    /** 相册显示关闭：保存到应用专属目录（不进入系统媒体库） */
    private fun writeToAppDir(ctx: Context, bytes: ByteArray, name: String): String? {
        val dir = File(
            ctx.getExternalFilesDir(Environment.DIRECTORY_PICTURES) ?: ctx.filesDir,
            "PureShot"
        ).apply { mkdirs() }
        val f = File(dir, name)
        FileOutputStream(f).use { it.write(bytes) }
        return f.absolutePath
    }
}
