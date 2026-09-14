package com.pureshot.screenshot.core.capture

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.pureshot.screenshot.R

/**
 * 捕获前台服务：承载 MediaProjection 会话（Android14 要求授权前 FGS 已运行），
 * 单次捕获用完立即 stopSelf，无常驻后台。
 */
class CaptureService : Service() {

    companion object {
        const val ACTION_PREPARE = "com.pureshot.screenshot.PREPARE"
        const val ACTION_CONSENT = "com.pureshot.screenshot.CONSENT"
        const val EXTRA_CODE = "code"
        const val EXTRA_DATA = "data"
        private const val CHANNEL_ID = "capture_service"
        private const val NOTIF_ID = 1001
    }

    private var projection: MediaProjection? = null

    private val callback = object : MediaProjection.Callback() {
        override fun onStop() {
            projection = null
            CaptureManager.onProjectionStopped()
            stopSelf()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startInForeground()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startInForeground()
        if (intent?.action == ACTION_CONSENT) {
            val code = intent.getIntExtra(EXTRA_CODE, 0)
            val data: Intent? = if (Build.VERSION >= Build.VERSION_CODES.TIRAMISU)
                intent.getParcelableExtra(EXTRA_DATA, Intent::class.java)
            else @Suppress("DEPRECATION") intent.getParcelableExtra(EXTRA_DATA)
            if (data != null) createProjection(code, data)
        }
        return START_NOT_STICKY
    }

    private fun createProjection(resultCode: Int, data: Intent) {
        try {
            val mgr = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            projection?.unregisterCallback(callback)
            projection?.stop()
            val p = mgr.getMediaProjection(resultCode, data) ?: return
            // API34 要求：createVirtualDisplay 前必须注册 Callback
            p.registerCallback(callback, null)
            projection = p
            CaptureManager.onProjectionReady(p)
        } catch (e: Throwable) {
            CaptureManager.onProjectionStopped()
            stopSelf()
        }
    }

    private fun startInForeground() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, getString(R.string.notif_channel_service), NotificationManager.IMPORTANCE_LOW)
            )
        }
        val notif: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_tile)
            .setContentTitle(getString(R.string.notif_capturing))
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        if (Build.VERSION >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    override fun onDestroy() {
        try {
            projection?.unregisterCallback(callback)
            projection?.stop()
        } catch (e: Throwable) { /* 容错 */ }
        projection = null
        super.onDestroy()
    }
}
