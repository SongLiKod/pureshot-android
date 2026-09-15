package com.pureshot.screenshot.core.capture

import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjection
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.pureshot.screenshot.core.Prefs
import com.pureshot.screenshot.core.longshot.LongShotAccessibilityService
import com.pureshot.screenshot.core.util.ErrorReporter
import com.pureshot.screenshot.ui.floating.FloatingBallService
import com.pureshot.screenshot.ui.preview.FloatingPreview
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

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

    /** 无障碍截图等待上限，超时回退媒体投影 */
    private const val ACCESSIBILITY_TIMEOUT_MS = 3000L

    /** 悬浮球隐藏后等待窗口刷新的时间，避免悬浮窗被截入画面 */
    private const val OVERLAY_HIDE_SETTLE_MS = 150L

    /** UI 层（模式选择弹窗/提示对话框）关闭的默认等待时间 */
    const val UI_DISMISS_SETTLE_MS = 350L

    @Volatile
    private var busy = false

    /** 应用自身叠加窗（悬浮球/悬浮预览）当前是否处于“为截图而隐藏”的状态 */
    @Volatile
    private var overlaysHidden = false

    /** 长截图期间保持会话，不立即释放 */
    @Volatile
    var keepSession = false

    /**
     * 请求截图。
     * @param settleDelayMs 触发截图的界面（模式选择弹窗/提示对话框）关闭后的等待时间，
     *   避免自身悬浮层被截入画面；由应用自身 UI 触发时传入，磁贴/自动化 API 传 0 即可。
     */
    fun request(ctx: Context, mode: CaptureMode, settleDelayMs: Long = 0L) {
        if (settleDelayMs <= 0L) {
            performRequest(ctx, mode)
            return
        }
        Handler(Looper.getMainLooper()).postDelayed({ performRequest(ctx, mode) }, settleDelayMs)
    }

    private fun performRequest(ctx: Context, mode: CaptureMode) {
        if (mode == CaptureMode.DELAY) {
            CountdownOverlay.start(ctx, Prefs.delaySeconds)
            return
        }
        if (busy) {
            ErrorReporter.toastRes(ctx, com.pureshot.screenshot.R.string.capture_running)
            return
        }
        // 极速截图：无障碍可用时直接调用官方截图接口，无需每次确认「共享屏幕」弹窗
        if (canCaptureByAccessibility() && mode != CaptureMode.LONG) {
            runMode(ctx, mode)
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

    /** 无障碍极速截图是否可用：开关开启、系统 Android11+、且无障碍服务已连接 */
    fun canCaptureByAccessibility(): Boolean =
        Prefs.fastCapture &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            LongShotAccessibilityService.instance?.canScreenshot() == true

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
        // 悬浮球 / 悬浮预览均为应用自身叠加窗，截图前先隐藏，避免出现在画面中
        val ballShown = FloatingBallService.isBallShown()
        val previewShown = FloatingPreview.isShown()
        if (ballShown) FloatingBallService.hideForCapture()
        if (previewShown) FloatingPreview.hideForCapture()
        if (ballShown || previewShown) overlaysHidden = true
        scope.launch {
            try {
                if (ballShown || previewShown) delay(OVERLAY_HIDE_SETTLE_MS)
                val full = captureWithEnhancement(ctx, mode)
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
                if (keepSession) {
                    // 长截图会话期间保持叠加窗隐藏，直至 LongShotActivity 结束会话
                } else {
                    restoreOverlays()
                    release()
                }
            }
        }
    }

    /** 恢复为截图而隐藏的应用自身叠加窗 */
    fun restoreOverlays() {
        if (!overlaysHidden) return
        overlaysHidden = false
        FloatingBallService.restoreAfterCapture()
        FloatingPreview.restoreAfterCapture()
    }

    /** Issue5：全界面增强截取 —— 小米/鸿蒙走系统底层接口尝试，其他设备自动回退标准捕获 */
    private suspend fun captureWithEnhancement(ctx: Context, mode: CaptureMode): android.graphics.Bitmap {
        if (Prefs.romEnhanced) {
            RomEnhancedCapture.tryCapture(ctx)?.let { return it }
        }
        // 极速截图：优先走官方无障碍截图接口，免「共享屏幕」授权弹窗
        if (mode != CaptureMode.LONG && canCaptureByAccessibility()) {
            withTimeoutOrNull(ACCESSIBILITY_TIMEOUT_MS) {
                LongShotAccessibilityService.instance?.captureScreen()
            }?.let { return it }
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
