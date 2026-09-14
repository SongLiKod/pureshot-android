package com.pureshot.screenshot.ui.longshot

import android.graphics.Bitmap
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.pureshot.screenshot.R
import com.pureshot.screenshot.core.Prefs
import com.pureshot.screenshot.core.capture.CaptureManager
import com.pureshot.screenshot.core.capture.CaptureMode
import com.pureshot.screenshot.core.capture.ResultDispatcher
import com.pureshot.screenshot.core.longshot.FrameStitcher
import com.pureshot.screenshot.core.longshot.LongShotAccessibilityService
import com.pureshot.screenshot.core.longshot.LongShotEngine
import com.pureshot.screenshot.core.util.ErrorReporter
import com.pureshot.screenshot.core.util.PermissionUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 长截图页（Issue6/7）：双引擎容错。
 * 主引擎=手动滑动+帧采样智能拼接（无需无障碍）；备选=无障碍自动滚动（用户手动授权后启用）。
 */
class LongShotActivity : AppCompatActivity() {

    private var engine: LongShotEngine? = null
    private val ui = Handler(Looper.getMainLooper())
    private var pollRunnable: Runnable? = null
    private var stopped = false
    private lateinit var stitcherBox: FrameStitcher

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_longshot)
        val projection = CaptureManager.projection
        if (projection == null) {
            ErrorReporter.toastRes(this, R.string.capture_fail)
            finish()
            return
        }
        try {
            engine = LongShotEngine(this, projection)
        } catch (e: Throwable) {
            ErrorReporter.dialog(this, e)
            finish()
            return
        }
        val real = engine!!
        stitcherInit(real.frameWidth)

        findViewById<ImageButton>(R.id.btn_long_close).setOnClickListener { finishSession() }
        findViewById<MaterialButton>(R.id.btn_seam_minus).setOnClickListener { adjustSeam(-1) }
        findViewById<MaterialButton>(R.id.btn_seam_plus).setOnClickListener { adjustSeam(1) }
        findViewById<MaterialButton>(R.id.btn_long_done).setOnClickListener { finishAndSave() }

        val accState = findViewById<TextView>(R.id.long_acc_state)
        val accOn = Prefs.accAutoScroll && PermissionUtil.isAccessibilityEnabled(this)
        if (accOn) {
            accState.text = getString(R.string.long_acc_running)
            LongShotAccessibilityService.instance?.startAutoScroll()
            findViewById<TextView>(R.id.long_hint).text = getString(R.string.long_hint)
        } else {
            accState.text = ""
            findViewById<TextView>(R.id.long_hint).text = getString(R.string.long_acc_need_enable)
        }

        try {
            real.start()
        } catch (e: Throwable) {
            ErrorReporter.dialog(this, e)
            finish()
            return
        }
        startPolling(real)
    }

    private fun stitcherInit(width: Int) {
        // FrameStitcher 宽度需与帧宽一致，重建实例
        stitcherBox = FrameStitcher(width, 14000)
        stitcherBox.onMaxReached = {
            runOnUiThread { ErrorReporter.toast(this, getString(R.string.long_max_reached)) }
        }
    }

    private fun startPolling(real: LongShotEngine) {
        val r = object : Runnable {
            override fun run() {
                if (stopped || isFinishing) return
                val frame = real.pollFrame()
                if (frame != null) {
                    val (px, h) = frame
                    try {
                        synchronized(stitcherBox) {
                            if (stitcherBox.isEmpty) stitcherBox.appendFirst(px, h)
                            else stitcherBox.stitch(px, h)
                        }
                        refreshPreview()
                    } catch (e: Throwable) { /* 单帧容错 */ }
                }
                ui.postDelayed(this, 350)
            }
        }
        pollRunnable = r
        ui.post(r)
    }

    private fun refreshPreview() {
        lifecycleScope.launch(Dispatchers.Default) {
            val bmp = synchronized(stitcherBox) { stitcherBox.previewBitmap(540, 1200) }
            withContext(Dispatchers.Main) {
                if (isFinishing || stopped) return@withContext
                findViewById<ImageView>(R.id.long_preview).setImageBitmap(bmp)
                findViewById<ScrollView>(R.id.long_scroll).fullScroll(ScrollView.FOCUS_DOWN)
            }
        }
    }

    private fun adjustSeam(delta: Int) {
        val ok = synchronized(stitcherBox) { stitcherBox.adjustSeam(delta) }
        if (ok) refreshPreview()
    }

    private fun finishAndSave() {
        val width = engine?.frameWidth ?: 1
        lifecycleScope.launch(Dispatchers.Default) {
            val full = synchronized(stitcherBox) { stitcherBox.toBitmap() }
            withContext(Dispatchers.Main) {
                if (full == null || full.width < 1) {
                    ErrorReporter.toastRes(this@LongShotActivity, R.string.long_empty)
                    finishSession()
                    return@withContext
                }
                try {
                    ResultDispatcher.dispatch(this@LongShotActivity, full, CaptureMode.LONG)
                } catch (e: Throwable) {
                    ErrorReporter.dialog(this@LongShotActivity, e)
                }
                finishSession()
            }
        }
    }

    private fun finishSession() {
        if (stopped) return
        stopped = true
        pollRunnable?.let { ui.removeCallbacks(it) }
        LongShotAccessibilityService.instance?.stopAutoScroll()
        try {
            engine?.stop()
        } catch (e: Throwable) { /* 容错 */ }
        engine = null
        CaptureManager.keepSession = false
        CaptureManager.release()
        finish()
    }

    override fun onBackPressed() {
        finishSession()
    }
}
