package com.pureshot.screenshot.core.capture

import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Bundle
import android.view.View
import android.widget.ImageButton
import android.widget.ImageView
import androidx.appcompat.app.AppCompatActivity
import com.pureshot.screenshot.R
import com.pureshot.screenshot.core.util.BitmapUtil
import com.pureshot.screenshot.core.util.ErrorReporter

/**
 * 区域截图（Issue4）：以刚捕获的全屏画面为静态底图，自由框选后精准局部捕获。
 */
class RegionCaptureActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_PATH = "path"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_region)
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            )
        val path = intent.getStringExtra(EXTRA_PATH)
        val full = CacheStore.load(this, path, 8192)
        if (full == null) {
            ErrorReporter.toastRes(this, R.string.capture_fail)
            finish()
            return
        }
        findViewById<ImageView>(R.id.region_background).setImageBitmap(full)
        findViewById<ImageButton>(R.id.btn_region_cancel).setOnClickListener {
            finishAndCleanup(path)
        }
        findViewById<ImageButton>(R.id.btn_region_ok).setOnClickListener {
            try {
                val selector = findViewById<RegionSelectorView>(R.id.region_selector)
                val sel = selector.selection()
                // 以选区视图实际尺寸换算（折叠屏/分屏下与屏幕像素可能不同）
                val sx = full.width.toFloat() / selector.width.coerceAtLeast(1)
                val sy = full.height.toFloat() / selector.height.coerceAtLeast(1)
                val rect = Rect(
                    (sel.left * sx).toInt().coerceIn(0, full.width - 1),
                    (sel.top * sy).toInt().coerceIn(0, full.height - 1),
                    (sel.right * sx).toInt().coerceIn(1, full.width),
                    (sel.bottom * sy).toInt().coerceIn(1, full.height)
                )
                val piece = BitmapUtil.crop(full, rect)
                ResultDispatcher.dispatch(this, piece, CaptureMode.REGION)
            } catch (e: Throwable) {
                ErrorReporter.dialog(this, e)
            }
            finishAndCleanup(path)
        }
    }

    private fun finishAndCleanup(path: String?) {
        try {
            path?.let { java.io.File(it).delete() }
        } catch (e: Throwable) { /* 容错 */ }
        finish()
    }
}
