package com.pureshot.screenshot.core.capture

import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjection
import com.pureshot.screenshot.core.Prefs
import com.pureshot.screenshot.core.util.ErrorReporter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

enum class CaptureMode { FULL, APP, REGION, DELAY, LONG }

/**
 * 捕获调度核心（Issue1）：按需申请媒体投影权限、单次捕获、用完立即释放，无常驻后台。
 */
object CaptureManager {

    @Volatile
    var projection: MediaProjection? = null
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val queue = ArrayDeque<CaptureMode>()

    @Volatile
    private var busy = false

    /** 长截图期间保持会话，不立即释放 */
    @Volatile
    var keepSession = false

    fun request(ctx: Context, mode: CaptureMode) {
        if (mode == CaptureMode.DELAY) {
            CountdownOverlay.start(ctx, Prefs.delaySeconds)
            return
        }
        if (busy) {
            ErrorReporter.toastRes(ctx, com.pureshot.screenshot.R.string.capture_running)
            return
        }
        if (projection == null) {
            queue.addLast(mode)
            // Android14+ 要求：发起授权前必须先启动 mediaProjection 类型前台服务
            ctx.startForegroundService(Intent(ctx, CaptureService::class.java)
                .setAction(CaptureService.ACTION_PREPARE))
            ctx.startActivity(
                Intent(ctx, PermissionActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } else {
            runMode(ctx, mode)
        }
    }

    fun onConsent(resultCode: Int, data: Intent) {
        val ctx = AppContext.get() ?: return
        ctx.startForegroundService(
            Intent(ctx, CaptureService::class.java)
                .setAction(CaptureService.ACTION_CONSENT)
                .putExtra(CaptureService.EXTRA_CODE, resultCode)
                .putExtra(CaptureService.EXTRA_DATA, data)
        )
    }

    fun onProjectionReady(p: MediaProjection) {
        projection = p
        val ctx = AppContext.get() ?: return
        while (queue.isNotEmpty()) {
            runMode(ctx, queue.removeFirst())
        }
    }

    fun onProjectionStopped() {
        projection = null
        queue.clear()
        keepSession = false
        AppContext.get()?.let { stopService(it) }
    }

    /** 用户取消授权：清空待执行队列并停止准备中的服务 */
    fun queueAbandon() {
        queue.clear()
        AppContext.get()?.let { stopService(it) }
    }

    private fun runMode(ctx: Context, mode: CaptureMode) {
        busy = true
        scope.launch {
            try {
                val full = captureWithEnhancement(ctx)
                when (mode) {
                    CaptureMode.FULL -> ResultDispatcher.dispatch(ctx, full, CaptureMode.FULL)
                    CaptureMode.APP -> {
                        val cropped = AppCaptureHelper.appCapture(ctx, full)
                        ResultDispatcher.dispatch(ctx, cropped, CaptureMode.APP)
                    }
                    CaptureMode.REGION -> {
                        val f = CacheStore.save(ctx, full)
                        ctx.startActivity(
                            Intent(ctx, RegionCaptureActivity::class.java)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                .putExtra(RegionCaptureActivity.EXTRA_PATH, f.absolutePath)
                        )
                    }
                    CaptureMode.LONG -> {
                        keepSession = true
                        ctx.startActivity(
                            Intent(ctx, com.pureshot.screenshot.ui.longshot.LongShotActivity::class.java)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    }
                    CaptureMode.DELAY -> { /* 倒计时结束后走 FULL */ }
                }
            } catch (e: Throwable) {
                ErrorReporter.dialog(ctx, e)
                ErrorReporter.toastRes(ctx, com.pureshot.screenshot.R.string.capture_fail)
            } finally {
                busy = false
                if (!keepSession) release()
            }
        }
    }

    /** Issue5：全界面增强截取 —— 小米/鸿蒙走系统底层接口尝试，其他设备自动回退标准捕获 */
    private suspend fun captureWithEnhancement(ctx: Context): android.graphics.Bitmap {
        if (Prefs.romEnhanced) {
            RomEnhancedCapture.tryCapture(ctx)?.let { return it }
        }
        return ScreenCapturer.capture(ctx)
    }

    fun release() {
        if (keepSession) return
        try {
            projection?.stop()
        } catch (e: Throwable) { /* 容错 */ }
        projection = null
        AppContext.get()?.let { stopService(it) }
    }

    private fun stopService(ctx: Context) {
        try {
            ctx.stopService(Intent(ctx, CaptureService::class.java))
        } catch (e: Throwable) { /* 容错 */ }
    }
}

/** 全局应用上下文持有（服务/磁贴无 Activity 场景使用） */
object AppContext {
    @Volatile
    private var ref: Context? = null
    fun get(): Context? = ref
    fun init(ctx: Context) {
        ref = ctx.applicationContext
    }
}
