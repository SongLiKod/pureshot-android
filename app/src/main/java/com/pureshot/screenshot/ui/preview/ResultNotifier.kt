package com.pureshot.screenshot.ui.preview

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.pureshot.screenshot.R
import com.pureshot.screenshot.core.capture.CaptureMode

/**
 * 截图结果通知（Issue20）：快捷编辑/保存/分享/删除操作入口。
 */
object ResultNotifier {

    private const val CHANNEL_ID = "capture_result"
    private var notifId = 2000

    fun notify(ctx: Context, path: String, mode: CaptureMode) {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID, ctx.getString(R.string.notif_channel_capture),
                    NotificationManager.IMPORTANCE_DEFAULT
                )
            )
        }
        val id = ++notifId
        fun pi(action: String, code: Int): PendingIntent {
            val i = Intent(ctx, PreviewActivity::class.java)
                .setAction(action)
                .putExtra(PreviewActivity.EXTRA_PATH, path)
                .putExtra(PreviewActivity.EXTRA_MODE, mode.ordinal)
                .putExtra(PreviewActivity.EXTRA_NOTIF_ID, id)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            return PendingIntent.getActivity(
                ctx, code, i,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }
        val title = when (mode) {
            CaptureMode.FULL -> ctx.getString(R.string.mode_full)
            CaptureMode.APP -> ctx.getString(R.string.mode_app)
            CaptureMode.REGION -> ctx.getString(R.string.mode_region)
            CaptureMode.DELAY -> ctx.getString(R.string.mode_delay)
            CaptureMode.LONG -> ctx.getString(R.string.mode_long)
        }
        val n = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_tile)
            .setContentTitle("${title} · ${ctx.getString(R.string.app_name)}")
            .setContentText(ctx.getString(R.string.preview_title))
            .setAutoCancel(true)
            .setContentIntent(pi(PreviewActivity.ACTION_OPEN, 0))
            .addAction(0, ctx.getString(R.string.edit), pi(PreviewActivity.ACTION_EDIT, 1))
            .addAction(0, ctx.getString(R.string.save), pi(PreviewActivity.ACTION_SAVE, 2))
            .addAction(0, ctx.getString(R.string.share), pi(PreviewActivity.ACTION_SHARE, 3))
            .addAction(0, ctx.getString(R.string.delete), pi(PreviewActivity.ACTION_DELETE, 4))
            .build()
        try {
            nm.notify(id, n)
        } catch (e: Throwable) { /* 通知权限未授予时静默 */ }
    }

    fun cancel(ctx: Context, id: Int) {
        try {
            (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(id)
        } catch (e: Throwable) { /* 容错 */ }
    }
}
