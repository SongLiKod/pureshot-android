package com.pureshot.screenshot.ui.preview

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import androidx.core.content.FileProvider
import com.pureshot.screenshot.R
import kotlin.math.abs

/**
 * 截图后悬浮预览（Issue20）：屏幕角落显示缩略图，点击进入预览/编辑，横滑关闭，6 秒自动消失。
 */
object FloatingPreview {

    private val main = Handler(Looper.getMainLooper())

    @Volatile
    private var activeView: ImageView? = null

    /** 悬浮预览当前是否显示 */
    fun isShown(): Boolean = activeView != null

    /** 截图前隐藏悬浮预览，避免上一张缩略图被截入当前画面 */
    fun hideForCapture() {
        val v = activeView ?: return
        main.post { v.visibility = View.INVISIBLE }
    }

    /** 截图结束后恢复悬浮预览显示 */
    fun restoreAfterCapture() {
        val v = activeView ?: return
        main.post { v.visibility = View.VISIBLE }
    }

    fun show(ctx: Context, thumb: Bitmap, path: String, mode: Int) {
        if (!Settings.canDrawOverlays(ctx)) return
        main.post {
            try {
                val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
                val size = (72 * ctx.resources.displayMetrics.density).toInt()
                val iv = ImageView(ctx).apply {
                    setImageBitmap(Bitmap.createScaledBitmap(thumb, size / 2, size, true))
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    setBackgroundColor(
                        androidx.core.content.ContextCompat.getColor(ctx, com.pureshot.screenshot.R.color.preview_thumb_bg)
                    )
                    elevation = 12f
                }
                val params = WindowManager.LayoutParams(
                    size, (size * 1.4f).toInt(),
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                    android.graphics.PixelFormat.TRANSLUCENT
                ).apply {
                    gravity = Gravity.START or Gravity.CENTER_VERTICAL
                    x = (12 * ctx.resources.displayMetrics.density).toInt()
                }
                val remove: () -> Unit = {
                    try {
                        wm.removeView(iv)
                    } catch (e: Throwable) { /* 容错 */ }
                    if (activeView === iv) activeView = null
                }
                var startX = 0f
                var moved = false
                iv.setOnTouchListener { _, e ->
                    when (e.actionMasked) {
                        MotionEvent.ACTION_DOWN -> { startX = e.rawX; moved = false }
                        MotionEvent.ACTION_MOVE -> if (abs(e.rawX - startX) > 60) moved = true
                        MotionEvent.ACTION_UP -> {
                            remove()
                            if (!moved) {
                                ctx.startActivity(
                                    Intent(ctx, PreviewActivity::class.java)
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                        .putExtra(PreviewActivity.EXTRA_PATH, path)
                                        .putExtra(PreviewActivity.EXTRA_MODE, mode)
                                )
                            }
                            true
                        }
                    }
                    true
                }
                wm.addView(iv, params)
                activeView = iv
                main.postDelayed({ remove() }, 6000)
            } catch (e: Throwable) { /* 悬浮预览失败不影响主流程 */ }
        }
    }

    fun shareUri(ctx: Context, file: java.io.File): Uri =
        FileProvider.getUriForFile(ctx, ctx.packageName + ".fileprovider", file)
}
