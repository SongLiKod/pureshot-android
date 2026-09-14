package com.pureshot.screenshot.core.capture

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import com.pureshot.screenshot.core.util.BitmapUtil
import com.pureshot.screenshot.core.util.SystemBars

/**
 * 应用截图（Issue3，版本智能适配）：
 * - Android14+(API34)：尝试调用原生应用窗口捕获接口，精准截取单 APP 界面、剔除系统 UI；
 *   接口不可用时自动降级为全屏裁切方案，无闪退。
 * - Android10-13：降级方案，全屏截图后自动识别裁切状态栏、导航栏，支持手动二次微调。
 */
object AppCaptureHelper {

    /** 是否为降级裁切方案（决定预览页是否提示“二次微调”） */
    @Volatile
    var usedFallback = false
        private set

    fun appCapture(ctx: Context, full: Bitmap): Bitmap {
        if (Build.VERSION.SDK_INT >= 34) {
            nativeWindowCapture(ctx)?.let {
                usedFallback = false
                return it
            }
        }
        usedFallback = true
        return cropSystemBars(ctx, full)
    }

    /** API34+ 原生窗口捕获（官方开放接口反射尝试，失败自动回退，不破解安全机制） */
    private fun nativeWindowCapture(ctx: Context): Bitmap? = try {
        val cls = Class.forName("android.window.ScreenCapture")
        val capture = cls.getMethod("getMainDisplayScreenCapture").invoke(null)
        val method = capture.javaClass.methods.firstOrNull { it.name == "captureDisplayExcludingSecure" }
        (method?.invoke(capture, *emptyArray<Any?>()) as? Bitmap)?.takeIf { !it.isRecycled }
    } catch (e: Throwable) {
        null
    }

    /** 降级方案：自动识别并裁切状态栏与导航栏 */
    fun cropSystemBars(ctx: Context, full: Bitmap): Bitmap {
        val status = SystemBars.statusBarHeight(ctx)
        val nav = SystemBars.navBarHeight(ctx)
        val rect = android.graphics.Rect(0, status, full.width, (full.height - nav).coerceAtLeast(status + 1))
        return BitmapUtil.crop(full, rect)
    }
}
