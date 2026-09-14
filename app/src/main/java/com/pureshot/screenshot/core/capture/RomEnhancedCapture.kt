package com.pureshot.screenshot.core.capture

import android.content.Context
import android.graphics.Bitmap
import com.pureshot.screenshot.core.Prefs
import com.pureshot.screenshot.core.util.RomUtils

/**
 * 全界面增强截取（Issue5）：仅小米/鸿蒙设备尝试厂商系统底层截图接口，
 * 捕获普通 API 无法截取的系统弹窗/输入法界面；其他品牌自动回退标准捕获。
 * 严格合规：不破解 FLAG_SECURE，任何异常静默回退，不崩溃。
 */
object RomEnhancedCapture {

    fun tryCapture(ctx: Context): Bitmap? {
        if (!Prefs.romEnhanced) return null
        if (!RomUtils.isEnhanceSupported) return null
        return try {
            invokeRomCaptureApi()
        } catch (e: Throwable) {
            null
        }
    }

    /**
     * 反射调用厂商 ROM 开放的底层截图接口（不同 ROM/版本接口签名差异大，
     * 任一环节不可用即返回 null，由调用方回退标准 MediaProjection 捕获）。
     */
    private fun invokeRomCaptureApi(): Bitmap? {
        val candidates = listOf(
            "android.view.SurfaceControl" to "captureScreen",
            "miui.util.ScreenshotHelper" to "capture",
            "com.huawei.android.view.ScreenCapture" to "capture"
        )
        for ((clsName, method) in candidates) {
            try {
                val cls = Class.forName(clsName)
                val m = cls.getMethod(method)
                val result = m.invoke(null)
                if (result is Bitmap && !result.isRecycled) return result
            } catch (e: Throwable) {
                continue
            }
        }
        return null
    }
}
