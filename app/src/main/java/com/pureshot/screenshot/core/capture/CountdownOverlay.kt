package com.pureshot.screenshot.core.capture

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView
import com.pureshot.screenshot.R
import com.pureshot.screenshot.core.util.ErrorReporter

/**
 * 延迟截图倒计时悬浮提示：倒计时结束瞬间隐藏自身再捕获，确保提示不出现在截图中。
 * 无悬浮窗权限时静默走纯延时捕获。
 */
object CountdownOverlay {

    private val main = Handler(Looper.getMainLooper())

    fun start(ctx: Context, seconds: Int) {
        val app = ctx.applicationContext
        if (!Settings.canDrawOverlays(app)) {
            main.postDelayed({ CaptureManager.request(app, CaptureMode.FULL) }, seconds * 1000L)
            return
        }
        main.post { show(app, seconds.coerceIn(1, 30)) }
    }

    private fun show(ctx: Context, total: Int) {
        try {
            val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val tv = TextView(ctx).apply {
                textSize = 64f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(Color.WHITE)
                setBackgroundColor(0xCC000000.toInt())
                setPadding(48, 24, 48, 24)
                gravity = Gravity.CENTER
            }
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                y = (ctx.resources.displayMetrics.heightPixels * 0.25f).toInt()
            }
            wm.addView(tv, params)
            var remain = total
            tv.text = ctx.getString(R.string.countdown_fmt, remain)
            object : Runnable {
                override fun run() {
                    remain--
                    if (remain > 0) {
                        tv.text = ctx.getString(R.string.countdown_fmt, remain)
                        main.postDelayed(this, 1000)
                    } else {
                        tv.text = ctx.getString(R.string.countdown_shooting)
                        main.postDelayed({
                            try {
                                wm.removeView(tv)
                            } catch (e: Throwable) { /* 容错 */ }
                            // 移除窗口后短暂等待，确保提示完全消失再捕获
                            main.postDelayed(
                                { CaptureManager.request(ctx, CaptureMode.FULL) }, 260
                            )
                        }, 500)
                    }
                }
            }.run { main.postDelayed(this, 1000) }
        } catch (e: Throwable) {
            ErrorReporter.dialog(ctx, e)
        }
    }
}
