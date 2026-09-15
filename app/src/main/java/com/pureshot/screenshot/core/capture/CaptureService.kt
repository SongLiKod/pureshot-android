package com.pureshot.screenshot.core.capture

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.pureshot.screenshot.R

/**
 * 捕获前台服务：仅承担 mediaProjection 类型前台服务的 FGS 占位
 * （Android14 要求创建虚拟屏时该 FGS 存活，且整个会话期间保持）。
 * MediaProjection 由 CaptureManager 在授权结果回调里就地创建，不跨组件传递令牌。
 */
class CaptureService : Service() {

    companion object {
        const val ACTION_PREPARE = "com.pureshot.screenshot.PREPARE"
        private const val TAG = "PureShot"
        private const val CHANNEL_ID = "capture_service"
        private const val NOTIF_ID = 1001
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startInForeground()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startInForeground()
        return START_NOT_STICKY
    }

    private fun startInForeground() {
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL_ID, getString(R.string.notif_channel_service),
                        NotificationManager.IMPORTANCE_LOW
                    )
                )
            }
            val notif: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_tile)
                .setContentTitle(getString(R.string.notif_capturing))
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
            } else {
                startForeground(NOTIF_ID, notif)
            }
            Log.d(TAG, "capture service foreground ok")
        } catch (e: Throwable) {
            // 前台服务启动失败不崩溃：授权流程照常进行（Android<14 无 FGS 也可截图）
            Log.e(TAG, "startForeground failed (non-fatal)", e)
        }
    }
}