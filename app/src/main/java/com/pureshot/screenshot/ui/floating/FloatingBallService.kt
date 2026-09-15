package com.pureshot.screenshot.ui.floating

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import androidx.core.app.NotificationCompat
import com.pureshot.screenshot.R
import com.pureshot.screenshot.core.capture.ModeChooserActivity

/**
 * 悬浮球快捷触发（Issue19，可选）：拖动自由定位，单击弹出截图模式选择。
 */
class FloatingBallService : Service() {

    companion object {
        private const val CHANNEL_ID = "floating_ball"
        private const val NOTIF_ID = 1002

        /** 静止时半透明，触摸/拖动时恢复不透明，避免遮挡屏幕内容 */
        private const val IDLE_ALPHA = 0.72f
        private const val ACTIVE_ALPHA = 1f

        fun ensureRunning(ctx: Context) {
            if (!Settings.canDrawOverlays(ctx)) return
            try {
                ctx.startForegroundService(Intent(ctx, FloatingBallService::class.java))
            } catch (e: Throwable) { /* 容错 */ }
        }

        fun stop(ctx: Context) {
            try {
                ctx.stopService(Intent(ctx, FloatingBallService::class.java))
            } catch (e: Throwable) { /* 容错 */ }
        }

        /** 悬浮球是否正在显示（截图前用于判断是否需要隐藏） */
        fun isBallShown(): Boolean = instance?.ball != null

        /** 截图前隐藏悬浮球，避免应用自身叠加窗被截入画面 */
        fun hideForCapture() {
            val s = instance ?: return
            s.main.post { s.ball?.visibility = View.INVISIBLE }
        }

        /** 截图结束后恢复悬浮球显示 */
        fun restoreAfterCapture() {
            val s = instance ?: return
            s.main.post { s.ball?.visibility = View.VISIBLE }
        }

        @Volatile
        private var instance: FloatingBallService? = null
    }

    private val main = Handler(Looper.getMainLooper())
    private var wm: WindowManager? = null
    private var ball: ImageView? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createChannel()
        startForeground(NOTIF_ID, buildNotification())
        instance = this
        if (ball == null) addBall()
        return START_STICKY
    }

    private fun addBall() {
        try {
            wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val size = (36 * resources.displayMetrics.density).toInt()
            val view = ImageView(this).apply {
                setImageResource(R.drawable.ic_ball)
                setBackgroundResource(R.drawable.bg_floating_ball)
                setPadding(size / 4, size / 4, size / 4, size / 4)
                adjustViewBounds = true
                alpha = IDLE_ALPHA
            }
            val params = WindowManager.LayoutParams(
                size, size,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.START or Gravity.TOP
                x = resources.displayMetrics.widthPixels - size - 24
                y = (resources.displayMetrics.heightPixels * 0.55f).toInt()
            }
            var downX = 0f
            var downY = 0f
            var startX = 0
            var startY = 0
            var moved = false
            view.setOnTouchListener { v, e ->
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downX = e.rawX; downY = e.rawY
                        startX = params.x; startY = params.y
                        moved = false
                        v.animate().alpha(ACTIVE_ALPHA).setDuration(120).start()
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = e.rawX - downX
                        val dy = e.rawY - downY
                        if (Math.abs(dx) > 20 || Math.abs(dy) > 20) moved = true
                        if (moved) {
                            params.x = startX + dx.toInt()
                            params.y = startY + dy.toInt()
                            wm?.updateViewLayout(view, params)
                        }
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        v.animate().alpha(IDLE_ALPHA).setDuration(180).start()
                        if (e.actionMasked == MotionEvent.ACTION_UP && !moved) {
                            startActivity(
                                Intent(this, ModeChooserActivity::class.java)
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        }
                    }
                }
                true
            }
            wm?.addView(view, params)
            ball = view
        } catch (e: Throwable) { /* 悬浮窗异常容错 */ }
    }

    private fun buildNotification(): Notification = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_tile)
        .setContentTitle(getString(R.string.app_name))
        .setContentText(getString(R.string.ball_switch_desc))
        .setOngoing(true)
        .setPriority(NotificationCompat.PRIORITY_MIN)
        .build()

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, getString(R.string.ball_switch_title), NotificationManager.IMPORTANCE_MIN)
                )
            }
        }
    }

    override fun onDestroy() {
        try {
            ball?.let { wm?.removeView(it) }
        } catch (e: Throwable) { /* 容错 */ }
        ball = null
        if (instance === this) instance = null
        super.onDestroy()
    }
}
