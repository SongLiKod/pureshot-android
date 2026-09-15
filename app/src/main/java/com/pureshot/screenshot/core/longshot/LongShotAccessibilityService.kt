package com.pureshot.screenshot.core.longshot

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.Path
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * 无障碍服务（Issue7，可选开启）：
 * - 极速截图：调用系统官方 takeScreenshot 接口直接成像，选择模式即完成，无需每次弹「共享屏幕」授权。
 * - 备选长截图引擎：自动滚动。仅派发屏幕滑动手势，不读取任何界面内容（配置中已关闭内容获取）。
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

    /** 是否支持官方无障碍截图接口（Android 11+） */
    fun canScreenshot(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

    /**
     * 调用系统官方 takeScreenshot 接口捕获整屏，免去 MediaProjection 每次的「共享屏幕」授权弹窗。
     * 失败/不支持时返回 null，由调用方回退媒体投影方案。
     */
    suspend fun captureScreen(): Bitmap? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        return suspendCancellableCoroutine { cont ->
            try {
                takeScreenshot(
                    Display.DEFAULT_DISPLAY,
                    mainExecutor,
                    object : TakeScreenshotCallback {
                        override fun onSuccess(screenshot: ScreenshotResult) {
                            val bmp = toSoftwareBitmap(screenshot)
                            if (cont.isActive) cont.resume(bmp)
                        }

                        override fun onFailure(errorCode: Int) {
                            if (cont.isActive) cont.resume(null)
                        }
                    }
                )
            } catch (e: Throwable) {
                if (cont.isActive) cont.resume(null)
            }
        }
    }

    private fun toSoftwareBitmap(screenshot: ScreenshotResult): Bitmap? = try {
        val buffer = screenshot.hardwareBuffer
        val wrapped = Bitmap.wrapHardwareBuffer(buffer, screenshot.colorSpace)
        val copy = wrapped?.copy(Bitmap.Config.ARGB_8888, false)
        wrapped?.recycle()
        buffer.close()
        copy
    } catch (e: Throwable) {
        null
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
