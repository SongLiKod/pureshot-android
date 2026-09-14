package com.pureshot.screenshot.ui.preview

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.ImageButton
import android.widget.ImageView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import com.pureshot.screenshot.MainActivity
import com.pureshot.screenshot.R
import com.pureshot.screenshot.core.Prefs
import com.pureshot.screenshot.core.export.ExportManager
import com.pureshot.screenshot.core.util.BitmapUtil
import com.pureshot.screenshot.core.util.ErrorReporter
import com.pureshot.screenshot.core.util.OcrHelper

/**
 * 截图预览页（Issue20）：统一所有截图模式预览入口，快捷编辑/保存/分享/删除。
 */
class PreviewActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_PATH = "path"
        const val EXTRA_MODE = "mode"
        const val EXTRA_NOTIF_ID = "notif_id"
        const val ACTION_OPEN = "open"
        const val ACTION_EDIT = "edit"
        const val ACTION_SAVE = "save"
        const val ACTION_SHARE = "share"
        const val ACTION_DELETE = "delete"
    }

    private var path: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        path = intent.getStringExtra(EXTRA_PATH)
        when (intent.action) {
            ACTION_EDIT -> { openEditor(); finishQuick(); return }
            ACTION_SAVE -> { quickSave(); finishQuick(); return }
            ACTION_SHARE -> { quickShare(); finishQuick(); return }
            ACTION_DELETE -> { quickDelete(); finishQuick(); return }
        }
        setContentView(R.layout.activity_preview)
        // 应用截图降级方案：提示可进入编辑二次微调裁切
        val mode = intent.getIntExtra(EXTRA_MODE, -1)
        if (mode == com.pureshot.screenshot.core.capture.CaptureMode.APP.ordinal) {
            findViewById<android.widget.TextView>(R.id.preview_hint).visibility = android.view.View.VISIBLE
        }
        val bmp = loadBitmap()
        if (bmp == null) {
            ErrorReporter.toastRes(this, R.string.capture_fail)
            finish()
            return
        }
        findViewById<ImageView>(R.id.preview_image).setImageBitmap(bmp)
        findViewById<ImageButton>(R.id.btn_preview_close).setOnClickListener { finish() }
        findViewById<com.google.android.material.button.MaterialButton>(R.id.btn_preview_edit)
            .setOnClickListener { openEditor() }
        findViewById<com.google.android.material.button.MaterialButton>(R.id.btn_preview_save)
            .setOnClickListener {
                bmp.let { b ->
                    ExportManager.saveBitmap(
                        this, b, Prefs.defaultFormat, Prefs.jpgQuality, withAnnotations = false
                    ) { uri ->
                        uri?.let { ErrorReporter.toast(this, getString(R.string.saved_to, it)) }
                            ?: ErrorReporter.toastRes(this, R.string.capture_fail)
                    }
                }
            }
        findViewById<com.google.android.material.button.MaterialButton>(R.id.btn_preview_share)
            .setOnClickListener { quickShare() }
        findViewById<com.google.android.material.button.MaterialButton>(R.id.btn_preview_ocr)
            .setOnClickListener { runOcr() }
        findViewById<com.google.android.material.button.MaterialButton>(R.id.btn_preview_delete)
            .setOnClickListener {
                quickDelete()
                finish()
            }
    }

    /** Phase3：OCR 文字提取，结果可复制 */
    private fun runOcr() {
        val bmp = BitmapUtil.decodeSampledAny(this, path, 4096) ?: return
        ErrorReporter.toast(this, getString(R.string.ocr_running))
        OcrHelper.recognize(
            bmp,
            onResult = { ocrText ->
                if (isFinishing) return@recognize
                if (ocrText.isBlank()) {
                    ErrorReporter.toastRes(this, R.string.ocr_empty)
                    return@recognize
                }
                val scroll = android.widget.ScrollView(this)
                val tv = android.widget.TextView(this).apply {
                    text = ocrText
                    setTextIsSelectable(true)
                    textSize = 14f
                    setPadding(dp(24), dp(12), dp(24), dp(12))
                }
                scroll.addView(tv)
                com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.ocr_result)
                    .setView(scroll)
                    .setPositiveButton(R.string.ocr_copy) { _, _ ->
                        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                        cm.setPrimaryClip(android.content.ClipData.newPlainText("ocr", ocrText))
                        ErrorReporter.toast(this, getString(R.string.ocr_copied))
                    }
                    .setNegativeButton(R.string.close, null)
                    .show()
            },
            onError = { msg ->
                if (!isFinishing) ErrorReporter.toast(this, getString(R.string.ocr_fail, msg))
            }
        )
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun loadBitmap() =
        BitmapUtil.decodeSampledAny(this, path, 4096)

    private fun finishQuick() {
        ResultNotifier.cancel(this, intent.getIntExtra(EXTRA_NOTIF_ID, -1))
        finish()
    }

    private fun openEditor() {
        val p = path ?: return
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra(MainActivity.EXTRA_EDITOR, p)
        )
        finish()
    }

    private fun quickSave() {
        val p = path ?: return
        val bmp = BitmapUtil.decodeSampledAny(this, p, 8192) ?: return
        ExportManager.saveBitmap(
            this, bmp, Prefs.defaultFormat, Prefs.jpgQuality, withAnnotations = false
        ) { uri ->
            uri?.let { ErrorReporter.toast(this, getString(R.string.saved_to, it)) }
        }
    }

    private fun quickShare() {
        try {
            val p = path ?: return
            val uri: Uri = if (p.startsWith("content://")) {
                Uri.parse(p)
            } else {
                val f = java.io.File(p)
                if (!f.exists()) return
                FileProvider.getUriForFile(this, "$packageName.fileprovider", f)
            }
            val send = Intent(Intent.ACTION_SEND)
                .setType("image/*")
                .putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(Intent.createChooser(send, getString(R.string.share_via)))
        } catch (e: Throwable) {
            ErrorReporter.dialog(this, e)
        }
    }

    private fun quickDelete() {
        try {
            path?.let { java.io.File(it).delete() }
        } catch (e: Throwable) { /* 容错 */ }
    }
}
