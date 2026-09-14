package com.pureshot.screenshot.core.util

import android.content.Context

/**
 * 系统栏高度读取：应用截图降级方案（Android10-13 自动裁切状态栏/导航栏）使用。
 */
object SystemBars {

    fun statusBarHeight(ctx: Context): Int = dimen(ctx, "status_bar_height", 24)

    fun navBarHeight(ctx: Context): Int = dimen(ctx, "navigation_bar_height", 0)

    private fun dimen(ctx: Context, name: String, defDp: Int): Int {
        val id = ctx.resources.getIdentifier(name, "dimen", "android")
        return if (id > 0) ctx.resources.getDimensionPixelSize(id) else (defDp * ctx.resources.displayMetrics.density).toInt()
    }
}
