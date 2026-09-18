package com.pureshot.screenshot.core.capture

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.pureshot.screenshot.core.Prefs
import com.pureshot.screenshot.core.util.ErrorReporter
import com.pureshot.screenshot.ui.floating.FloatingBallService
import com.pureshot.screenshot.ui.preview.FloatingPreview
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentLinkedDeque

enum class CaptureMode { FULL, APP, REGION, DELAY, LONG }

/**
 * 捕获调度核心（Issue1）：按需申请媒体投影授权，单一 MediaProjection 引擎。
 *
 * 会话复用策略（一次点击即截图）：
 * - 首次截图弹出系统授权（由独立的真实前台页 ConsentActivity 承载，结果就地创建投影）；
 * - 之后投影保活复用，后续截图一点即截、无需再次授权；
 * - 空闲超过保活时长（设置可自定义，默认 5 分钟）自动释放；用户在系统托盘点「停止共享」立即释放；
 * - 长截图会话期间保持叠加窗隐藏，结束后继续保活至空闲超时。
 */
object CaptureManager {

    private const val TAG = "PureShot"

    @Volatile
    var projection: MediaProjection? = null
        private set

    /** 常驻捕获会话（同一授权内唯一 VirtualDisplay，避免重建显示触发部分 ROM 终止会话） */
    private var session: CaptureSession? = null

    /** 捕获协程兜底：任何未捕获异常只提示、不崩进程（协程未捕获异常默认会杀死应用） */
    private val captureGuard = CoroutineExceptionHandler { _, e ->
        Log.e(TAG, "capture coroutine uncaught", e)
        AppContext.get()?.let { ErrorReporter.toastRes(it, com.pureshot.screenshot.R.string.capture_fail) }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + captureGuard)
    /** 待执行队列：模式 + 该条目捕获前的额外等待（延迟截图/界面退场）。
     *  线程安全队列：主线程（授权回调/磁贴/悬浮球入队、投影终止清队）与捕获协程并发访问 */
    private val queue = ConcurrentLinkedDeque<Pair<CaptureMode, Long>>()

    /** 授权完成后等待「共享屏幕」弹窗关闭与系统 Toast 消散，避免残影入画 */
    private const val CONSENT_SETTLE_MS = 1200L

    /** 悬浮球隐藏后等待窗口刷新的时间，避免悬浮窗被截入画面 */
    private const val OVERLAY_HIDE_SETTLE_MS = 150L

    /** UI 层（模式选择弹窗/提示对话框）关闭的默认等待时间 */
    const val UI_DISMISS_SETTLE_MS = 350L

    /** 投影空闲保活时长：超时自动释放，避免常驻（用户可在设置中自定义，默认 5 分钟） */
    private fun idleReleaseMs(): Long = Prefs.keepAliveMinutes.coerceIn(1, 30) * 60_000L

    @Volatile
    private var busy = false

    /** 应用自身叠加窗（悬浮球/悬浮预览）当前是否处于“为截图而隐藏”的状态 */
    @Volatile
    private var overlaysHidden = false

    /** 长截图期间保持会话，不释放、不恢复叠加窗 */
    @Volatile
    var keepSession = false

    private val idleHandler = Handler(Looper.getMainLooper())
    private val idleRelease = Runnable {
        if (!busy && !keepSession) release()
    }

    /** 投影生命周期回调（用户从系统托盘停止共享时触发释放） */
    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            Log.d(TAG, "projection onStop")
            onProjectionStopped()
        }
    }

    /**
     * 请求截图。
     * @param settleDelayMs 触发截图的界面（模式选择弹窗/提示对话框）关闭后的等待时间。
     * @return true 表示已进入授权流程（调用方应保持前台，授权完成后由授权页自行退后台）；
     *         false 表示已进入捕获流程（调用方可将自身任务退到后台，露出目标界面再截入画面）。
     */
    fun request(ctx: Context, mode: CaptureMode, settleDelayMs: Long = 0L): Boolean {
        Log.d(TAG, "request mode=$mode, busy=$busy, hasProjection=${projection != null}")
        if (mode == CaptureMode.DELAY) {
            return startDelayed(ctx)
        }
        if (busy) {
            ErrorReporter.toastRes(ctx, com.pureshot.screenshot.R.string.capture_running)
            return false
        }
        queue.addLast(mode to settleDelayMs)
        if (projection == null) {
            return startConsentFlow(ctx)
        }
        // 会话复用：一点即截
        idleHandler.removeCallbacks(idleRelease)
        pump(ctx)
        return false
    }

    /**
     * 延迟截图（Issue4）：倒计时结束后以全屏模式捕获。
     * - 有悬浮窗：倒计时悬浮提示（桌面级窗口，退后台不影响），结束后请求；
     * - 无悬浮窗：立即拉起授权（或复用会话），授权完成后再等待倒计时结束才捕获，
     *   期间用户可切换到目标界面。
     */
    private fun startDelayed(ctx: Context): Boolean {
        val seconds = Prefs.delaySeconds.coerceIn(1, 30)
        if (android.provider.Settings.canDrawOverlays(ctx)) {
            CountdownOverlay.start(ctx, seconds)
            return false
        }
        if (busy) {
            ErrorReporter.toastRes(ctx, com.pureshot.screenshot.R.string.capture_running)
            return false
        }
        queue.addLast(CaptureMode.FULL to seconds * 1000L)
        if (projection == null) {
            return startConsentFlow(ctx)
        }
        idleHandler.removeCallbacks(idleRelease)
        pump(ctx)
        return false
    }

    /**
     * 拉起授权流程（调用方已入队）。返回是否成功进入授权。
     * 前台服务启动失败不阻断授权（Android<14 无需 FGS；Android14 会在创建虚拟屏时报错并可感知）。
     */
    private fun startConsentFlow(ctx: Context): Boolean {
        try {
            // Android14+ 要求：发起授权前启动 mediaProjection 类型前台服务
            ctx.startForegroundService(
                Intent(ctx, CaptureService::class.java).setAction(CaptureService.ACTION_PREPARE)
            )
        } catch (e: Throwable) {
            Log.e(TAG, "start foreground service failed (non-fatal)", e)
        }
        try {
            // 独立真实前台页承载授权：立即拉起，不依赖任何就绪信号，杜绝“点了没反应”
            ctx.startActivity(
                Intent(ctx, ConsentActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            Log.d(TAG, "consent activity launched")
            return true
        } catch (e: Throwable) {
            Log.e(TAG, "launch consent activity failed", e)
            queue.clear()
            stopService(ctx)
            ErrorReporter.toastRes(ctx, com.pureshot.screenshot.R.string.capture_fail)
            return false
        }
    }

    /**
     * 由 ConsentActivity 在授权结果回调中调用：
     * 就地创建 MediaProjection（不跨组件嵌套传递授权令牌，规避 MIUI 等 ROM 兼容问题），
     * 并把主任务退到后台，露出目标界面后开始捕获。
     */
    fun onConsent(activity: Activity, resultCode: Int, data: Intent) {
        try {
            val mgr = activity.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val p = mgr.getMediaProjection(resultCode, data)
            Log.d(TAG, "getMediaProjection ok: ${p != null}")
            p.registerCallback(projectionCallback, null)
            projection = p
            // 建立常驻捕获会话（VirtualDisplay 仅创建一次，后续抓取复用，不再重建）
            try {
                val (w, h, dpi) = ScreenCapturer.displaySize(activity)
                session = CaptureSession(p, w, h, dpi).also { it.open() }
            } catch (e: Throwable) {
                Log.e(TAG, "open capture session failed", e)
            }
            // 授权已拿到：主任务退后台，让捕获到的是用户平时用的应用而不是净截自身
            try {
                activity.moveTaskToBack(true)
            } catch (e: Throwable) { /* 容错 */ }
            onProjectionReady(p)
        } catch (e: Throwable) {
            Log.e(TAG, "getMediaProjection failed", e)
            stopService(activity)
            queueAbandon()
            ErrorReporter.toastRes(activity, com.pureshot.screenshot.R.string.capture_fail)
        }
    }

    private fun onProjectionReady(p: MediaProjection) {
        projection = p
        val ctx = AppContext.get() ?: return
        // 队列为空（进程重启/用户已取消）：不保留投影，立即释放
        if (queue.isEmpty()) {
            release()
            return
        }
        idleHandler.removeCallbacks(idleRelease)
        pump(ctx, afterConsent = true)
    }

    fun onProjectionStopped() {
        projection = null
        session?.close()
        session = null
        queue.clear()
        keepSession = false
        idleHandler.removeCallbacks(idleRelease)
        AppContext.get()?.let { stopService(it) }
    }

    /** 用户取消授权：清空待执行队列并停止准备中的服务 */
    fun queueAbandon() {
        queue.clear()
        AppContext.get()?.let { stopService(it) }
    }

    /** 唯一捕获执行器：串行消费队列；投影保活至空闲超时（LONG 会话除外） */
    private fun pump(ctx: Context, afterConsent: Boolean = false) {
        if (busy) return
        busy = true
        scope.launch {
            try {
                if (afterConsent) delay(CONSENT_SETTLE_MS)
                while (true) {
                    // 投影被 ROM 中途终止时主线程会 clear 队列，用 poll 原子取用避免竞态
                    val (mode, waitMs) = queue.pollFirst() ?: break
                    if (waitMs > 0L) delay(waitMs)
                    val ballShown = FloatingBallService.isBallShown()
                    val previewShown = FloatingPreview.isShown()
                    if (ballShown) FloatingBallService.hideForCapture()
                    if (previewShown) FloatingPreview.hideForCapture()
                    if (ballShown || previewShown) overlaysHidden = true
                    if (ballShown || previewShown) delay(OVERLAY_HIDE_SETTLE_MS)
                    try {
                        Log.d(TAG, "capturing mode=$mode")
                        val full = captureWithEnhancement(ctx)
                        Log.d(TAG, "captured ${full.width}x${full.height}")
                        handleResult(ctx, full, mode)
                    } catch (e: Throwable) {
                        Log.e(TAG, "capture failed", e)
                        ErrorReporter.dialog(ctx, e)
                        ErrorReporter.toastRes(ctx, com.pureshot.screenshot.R.string.capture_fail)
                    } finally {
                        if (!keepSession) restoreOverlays()
                    }
                }
            } catch (e: Throwable) {
                // 循环级异常兜底（如投影被 ROM 中途终止引发的连锁状态变化），只提示不崩进程
                Log.e(TAG, "capture loop failed", e)
                ErrorReporter.toastRes(ctx, com.pureshot.screenshot.R.string.capture_fail)
            } finally {
                busy = false
                if (!keepSession) {
                    restoreOverlays()
                    scheduleIdleRelease()
                }
            }
        }
    }

    private fun scheduleIdleRelease() {
        idleHandler.removeCallbacks(idleRelease)
        idleHandler.postDelayed(idleRelease, idleReleaseMs())
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
            CaptureMode.DELAY -> { /* 延迟已折算为 FULL 入队 */ }
        }
    }

    /** Issue5：全界面增强截取 —— 小米/鸿蒙走系统底层接口尝试，失败自动回退标准捕获 */
    private suspend fun captureWithEnhancement(ctx: Context): Bitmap {
        if (Prefs.romEnhanced) {
            RomEnhancedCapture.tryCapture(ctx)?.let { return it }
        }
        val s = session
        if (s != null) {
            // 屏幕旋转等导致尺寸变化时重建显示
            try {
                val (w, h, dpi) = ScreenCapturer.displaySize(ctx)
                if (s.isSizeChanged(w, h)) s.recreate(w, h)
            } catch (e: Throwable) { /* 尺寸读取失败忽略 */ }
            return s.snapshot()
        }
        // 会话未就绪（理论不应发生）时回退旧逐帧方案
        return ScreenCapturer.capture(ctx)
    }

    /** 长截图结束：恢复叠加窗，投影继续保活至空闲超时 */
    fun endLongSession() {
        keepSession = false
        restoreOverlays()
        if (projection != null) scheduleIdleRelease()
    }

    fun release() {
        if (keepSession) return
        Log.d(TAG, "release projection")
        idleHandler.removeCallbacks(idleRelease)
        try {
            projection?.stop()
        } catch (e: Throwable) { /* 容错 */ }
        projection = null
        session?.close()
        session = null
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