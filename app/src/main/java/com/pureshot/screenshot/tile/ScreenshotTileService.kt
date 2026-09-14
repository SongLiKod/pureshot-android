package com.pureshot.screenshot.tile

import android.content.Intent
import android.os.Build
import android.service.quicksettings.TileService
import androidx.core.content.ContextCompat
import com.pureshot.screenshot.MainActivity
import com.pureshot.screenshot.core.capture.ModeChooserActivity

/**
 * 通知栏磁贴（Issue19）：单击弹出截图模式选择，长按进入应用设置中心。
 */
class ScreenshotTileService : TileService() {

    override fun onClick() {
        super.onClick()
        try {
            val intent = Intent(this, ModeChooserActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (Build.VERSION >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startActivityAndCollapse(
                    android.app.PendingIntent.getActivity(
                        this, 0, intent,
                        android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
                    )
                )
            } else {
                @Suppress("DEPRECATION")
                startActivityAndCollapse(intent)
            }
        } catch (e: Throwable) { /* 磁贴异常容错 */ }
    }

    override fun onLongClick(event: android.view.MotionEvent?): Boolean {
        try {
            val i = Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra(MainActivity.EXTRA_OPEN_SETTINGS, true)
            ContextCompat.startActivity(this, i, null)
        } catch (e: Throwable) { /* 容错 */ }
        return true
    }
}
