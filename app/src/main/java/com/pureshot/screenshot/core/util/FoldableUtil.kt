package com.pureshot.screenshot.core.util

import android.content.Context
import android.view.WindowManager
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowInfoTracker
import androidx.window.layout.WindowMetrics
import androidx.window.layout.WindowMetricsCalculator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * 折叠屏适配（Phase3）：基于官方 Window API 读取窗口度量与铰链状态，
 * 驱动首页/图库动态网格列数与画布重排，展开/折叠切换无内容错乱。
 */
object FoldableUtil {

    fun currentMetrics(ctx: Context): WindowMetrics =
        WindowMetricsCalculator.getOrCreate().computeCurrentWindowMetrics(ctx)

    /** 当前窗口宽度 dp */
    fun windowWidthDp(ctx: Context): Int {
        val w = currentMetrics(ctx).bounds.width()
        return (w / ctx.resources.displayMetrics.density).toInt()
    }

    /** 图库列数：手机3列，展开/平板5列，大折叠6列 */
    fun gallerySpan(ctx: Context): Int = when {
        windowWidthDp(ctx) >= 840 -> 6
        windowWidthDp(ctx) >= 600 -> 5
        else -> 3
    }

    /** 首页模式卡片列数：展开态双列 */
    fun homeColumns(ctx: Context): Int = if (windowWidthDp(ctx) >= 600) 2 else 1

    /** 监听折叠形态变化（铰链半开/折叠切换），变化时回调 */
    fun observeFolding(activity: android.app.Activity, scope: CoroutineScope, onChange: (FoldingFeature?) -> Unit): Job =
        scope.launch(Dispatchers.Main) {
            try {
                WindowInfoTracker.getOrCreate(activity)
                    .windowLayoutInfo(activity)
                    .collectLatest { info ->
                        onChange(info.displayFeatures.firstOrNull { it is FoldingFeature } as? FoldingFeature)
                    }
            } catch (e: Throwable) { /* 非折叠设备忽略 */ }
        }

    /** 设备是否具备折叠能力（用于设置页说明） */
    fun isFoldableDevice(ctx: Context): Boolean = try {
        ctx.packageManager.hasSystemFeature("android.software.folding") ||
            ctx.packageManager.hasSystemFeature("android.hardware.sensor.hinge_angle")
    } catch (e: Throwable) {
        false
    }
}
