package com.pureshot.screenshot.core.longshot

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent

/**
 * 备选长截图引擎（Issue7，可选开启）：无障碍自动滚动。
 * 用户手动授权后启用，仅派发屏幕滑动手势，不读取任何界面内容（配置中已关闭内容获取）。
 */
class LongShotAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: LongShotAccessibilityService? = null
            private set
    }

    private val handler = Handler(Looper.getMainLooper())
    @Volatile
    private var scrolling = false

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // 不读取、不存储任何界面内容，保持纯净
    }

    override fun onInterrupt() {
        stopAutoScroll()
    }

    fun startAutoScroll() {
        if (scrolling) return
        scrolling = true
        handler.post(scrollLoop)
    }

    fun stopAutoScroll() {
        scrolling = false
        handler.removeCallbacks(scrollLoop)
    }

    private val scrollLoop = object : Runnable {
        override fun run() {
            if (!scrolling) return
            dispatchSwipe()
            handler.postDelayed(this, 950)
        }
    }

    private fun dispatchSwipe() {
        try {
            val metrics = resources.displayMetrics
            val cx = metrics.widthPixels / 2f
            val path = Path().apply {
                moveTo(cx, metrics.heightPixels * 0.72f)
                lineTo(cx, metrics.heightPixels * 0.38f)
            }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, 500))
                .build()
            dispatchGesture(gesture, null, null)
        } catch (e: Throwable) { /* 手势派发失败静默容错 */ }
    }

    override fun onDestroy() {
        stopAutoScroll()
        instance = null
        super.onDestroy()
    }
}
