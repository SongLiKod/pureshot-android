package com.pureshot.screenshot.core.capture

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
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
 *
 * 调度规则：
 * - 极速截图（无障碍）可用时优先直接成像，需等待触发界面退场后再捕获；
 * - 授权路径必须立即拉起授权页（应用尚在前景，规避后台 Activity 启动限制），
 *   授权弹窗本身覆盖桌面，捕获发生在用户完成授权之后，无需额外等待；
 * - 极速截图失败自动回退授权路径；
 * - 队列串行消费，全部完成后才释放投影。
 */
object CaptureManager {

    @Volatile
    var projection: MediaProjection? = null
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    /** 待执行队列：模式 + 该条目捕获前的额外等待（延迟截图场景） */
    private val queue = ArrayDeque<Pair<CaptureMode, Long>>()

    /** 无障碍截图等待上限，超时回退媒体投影 */
    private const val ACCESSIBILITY_TIMEOUT_MS = 3000L

    /** 授权完成后等待「共享屏幕」弹窗关闭与系统 Toast 消散，避免残影入画 */
    private const val CONSENT_SETTLE_MS = 1200L

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

    /** 极速截图连续失败计数：达到阈值后本次进程内停用极速路径，强制走可靠的授权路径 */
    @Volatile
    private var fastFailures = 0

    /**
     * 请求截图。
     * @param settleDelayMs 触发截图的界面（模式选择弹窗/提示对话框）关闭后的等待时间，
     *   仅作用于极速截图路径；授权路径会立即拉起授权页（应用仍在前景）。
     */
    fun request(ctx: Context, mode: CaptureMode, settleDelayMs: Long = 0L) {
        if (mode == CaptureMode.DELAY) {
            startDelayed(ctx)
            return
        }
        if (busy) {
            ErrorReporter.toastRes(ctx, com.pureshot.screenshot.R.string.capture_running)
            return
        }
        // 极速截图：无障碍可用时直接调用官方截图接口，无需每次确认「共享屏幕」弹窗。
        // LONG 需要持续帧采样（媒体投影会话）；REGION 捕获后需从后台拉起选区页，
        // 极速路径无前台服务、Activity 启动会被系统后台限制拦截，故两者不走极速路径。
        if (canCaptureByAccessibility() && mode != CaptureMode.LONG && mode != CaptureMode.REGION) {
            if (settleDelayMs > 0L) {
                // 立即占位，防止延迟窗口内重复触发并发捕获
                busy = true
                Handler(Looper.getMainLooper()).postDelayed({ runCapture(ctx, mode) }, settleDelayMs)
            } else {
                runCapture(ctx, mode)
            }
            return
        }
        // 授权路径：立即拉起（此刻应用仍在前景，避免后台启动 Activity 被系统拦截）
        if (projection == null) {
            startConsentFlow(ctx, mode)
        } else {
            runCapture(ctx, mode)
        }
    }

    /**
     * 延迟截图（Issue4）：倒计时结束后以全屏模式捕获。
     * 授权/前台服务必须在应用仍处前景时发起，因此：
     * - 有悬浮窗：倒计时悬浮提示，结束后再请求（SAW 豁免后台启动限制）；
     * - 无悬浮窗 + 极速可用：直接延迟捕获（无需任何服务/Activity）；
     * - 无悬浮窗 + 需授权：立即拉起授权，授权完成后再等待倒计时结束才捕获
     *   （用户可在倒计时期间切换到目标界面）。
     */
    private fun startDelayed(ctx: Context) {
        val seconds = Prefs.delaySeconds.coerceIn(1, 30)
        if (android.provider.Settings.canDrawOverlays(ctx)) {
            CountdownOverlay.start(ctx, seconds)
            return
        }
        if (busy) {
            ErrorReporter.toastRes(ctx, com.pureshot.screenshot.R.string.capture_running)
            return
        }
        val delayMs = seconds * 1000L
        if (canCaptureByAccessibility()) {
            busy = true
            Handler(Looper.getMainLooper()).postDelayed(
                { runCapture(ctx, CaptureMode.FULL) }, delayMs
            )
        } else {
            startConsentFlow(ctx, CaptureMode.FULL, delayMs)
        }
    }

    /** 极速截图是否可用：开关开启、系统 Android11+、无障碍服务已连接、且未连续失败 */
    fun canCaptureByAccessibility(): Boolean =
        Prefs.fastCapture &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            fastFailures < 2 &&
            LongShotAccessibilityService.instance?.canScreenshot() == true

    private fun startConsentFlow(ctx: Context, mode: CaptureMode, extraSettleMs: Long = 0L) {
        val item = mode to extraSettleMs
        queue.addLast(item)
        // Android14+ 要求：发起授权前必须先启动 mediaProjection 类型前台服务。
        // 后台启动前台服务在 Android12+ 会抛异常（无豁免时），必须捕获避免崩溃。
        try {
            ctx.startForegroundService(
                Intent(ctx, CaptureService::class.java).setAction(CaptureService.ACTION_PREPARE)
            )
        } catch (e: Throwable) {
            queue.remove(item)
            ErrorReporter.toastRes(ctx, com.pureshot.screenshot.R.string.capture_fail)
            return
        }
        try {
            ctx.startActivity(
                Intent(ctx, PermissionActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (e: Throwable) {
            queue.remove(item)
            stopService(ctx)
            ErrorReporter.toastRes(ctx, com.pureshot.screenshot.R.string.capture_fail)
        }
    }

    fun onConsent(resultCode: Int, data: Intent) {
        val ctx = AppContext.get() ?: return
        try {
            ctx.startForegroundService(
                Intent(ctx, CaptureService::class.java)
                    .setAction(CaptureService.ACTION_CONSENT)
                    .putExtra(CaptureService.EXTRA_CODE, resultCode)
                    .putExtra(CaptureService.EXTRA_DATA, data)
            )
        } catch (e: Throwable) {
            queueAbandon()
            ErrorReporter.toastRes(ctx, com.pureshot.screenshot.R.string.capture_fail)
        }
    }

    fun onProjectionReady(p: MediaProjection) {
        projection = p
        val ctx = AppContext.get() ?: return
        // 队列为空（进程重启/用户已取消）：不保留投影，立即释放
        if (queue.isEmpty()) {
            release()
            return
        }
        runQueued(ctx)
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

    /** 单次捕获（极速截图/已有投影场景），失败自动回退授权路径 */
    private fun runCapture(ctx: Context, mode: CaptureMode) {
        busy = true
        scope.launch {
            val ballShown = FloatingBallService.isBallShown()
            val previewShown = FloatingPreview.isShown()
            if (ballShown) FloatingBallService.hideForCapture()
            if (previewShown) FloatingPreview.hideForCapture()
            if (ballShown || previewShown) overlaysHidden = true
            var fallbackToConsent = false
            try {
                if (ballShown || previewShown) delay(OVERLAY_HIDE_SETTLE_MS)
                val full = captureWithEnhancement(ctx, mode)
                if (full == null) {
                    // 极速截图失败且无可用投影：记失败次数并回退授权路径（不在此释放，交由授权流程接管）
                    if (canCaptureByAccessibility()) fastFailures++
                    fallbackToConsent = true
                    startConsentFlow(ctx, mode)
                    return@launch
                }
                fastFailures = 0
                handleResult(ctx, full, mode)
            } catch (e: Throwable) {
                ErrorReporter.dialog(ctx, e)
                ErrorReporter.toastRes(ctx, com.pureshot.screenshot.R.string.capture_fail)
            } finally {
                busy = false
                if (!keepSession) {
                    // LONG 会话期间保持叠加窗隐藏，由 LongShotActivity 结束时恢复
                    restoreOverlays()
                    if (!fallbackToConsent) release()
                }
            }
        }
    }

    /** 投影就绪后串行消费队列，全部完成后统一释放 */
    private fun runQueued(ctx: Context) {
        busy = true
        scope.launch {
            try {
                // 等待授权弹窗关闭动画与系统「正在共享屏幕」Toast 消散，避免残影入画
                delay(CONSENT_SETTLE_MS)
                while (queue.isNotEmpty()) {
                    val (mode, extraSettle) = queue.removeFirst()
                    try {
                        // 延迟截图：授权完成后继续等待倒计时结束（期间用户可切换到目标界面）
                        if (extraSettle > 0L) delay(extraSettle)
                        val ballShown = FloatingBallService.isBallShown()
                        val previewShown = FloatingPreview.isShown()
                        if (ballShown) FloatingBallService.hideForCapture()
                        if (previewShown) FloatingPreview.hideForCapture()
                        if (ballShown || previewShown) overlaysHidden = true
                        if (ballShown || previewShown) delay(OVERLAY_HIDE_SETTLE_MS)
                        val bmp = captureWithEnhancement(ctx, mode)
                        if (bmp != null) {
                            fastFailures = 0
                            handleResult(ctx, bmp, mode)
                        }
                    } catch (e: Throwable) {
                        ErrorReporter.dialog(ctx, e)
                        ErrorReporter.toastRes(ctx, com.pureshot.screenshot.R.string.capture_fail)
                    } finally {
                        if (!keepSession) restoreOverlays()
                    }
                }
            } finally {
                busy = false
                if (!keepSession) {
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

    private fun handleResult(ctx: Context, full: Bitmap, mode: CaptureMode) {
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
    }

    /**
     * Issue5：全界面增强截取 —— 小米/鸿蒙走系统底层接口尝试，其他设备自动回退标准捕获。
     * @return null 表示当前无法捕获（极速截图失败且无投影），调用方应回退授权路径
     */
    private suspend fun captureWithEnhancement(ctx: Context, mode: CaptureMode): Bitmap? {
        if (Prefs.romEnhanced) {
            RomEnhancedCapture.tryCapture(ctx)?.let { return it }
        }
        // 极速截图：优先走官方无障碍截图接口，免「共享屏幕」授权弹窗
        if (mode != CaptureMode.LONG && canCaptureByAccessibility()) {
            withTimeoutOrNull(ACCESSIBILITY_TIMEOUT_MS) {
                LongShotAccessibilityService.instance?.captureScreen()
            }?.let { return it }
        }
        if (projection == null) return null
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
