package com.pureshot.screenshot.tile

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.TileService
import com.pureshot.screenshot.core.capture.ModeChooserActivity

/**
 * 通知栏磁贴（Issue19）：单击弹出截图模式选择。
 * 长按进入应用设置中心：通过 QS_TILE_PREFERENCES intent-filter 声明于 MainActivity 实现
 * （onLongClick 为系统隐藏 API，公开 SDK 不可 override，此为官方推荐机制）。
 */
class ScreenshotTileService : TileService() {

    override fun onClick() {
        super.onClick()
        try {
            val intent = Intent(this, ModeChooserActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startActivityAndCollapse(
                    PendingIntent.getActivity(
                        this, 0, intent,
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                    )
                )
            } else {
                @Suppress("DEPRECATION")
                startActivityAndCollapse(intent)
            }
        } catch (e: Throwable) { /* 磁贴异常容错 */ }
    }
}
