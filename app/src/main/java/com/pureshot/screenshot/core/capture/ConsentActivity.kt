package com.pureshot.screenshot.core.capture

import android.content.Context
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.util.Log
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.pureshot.screenshot.R
import com.pureshot.screenshot.core.util.ErrorReporter

/**
 * 媒体投影授权页。
 *
 * 设计要点（针对 MIUI 等 ROM 的兼容）：
 * 1. 普通不透明 Activity、有实际界面 —— 不会被系统当作"空壳/透明页"回收；
 * 2. 与主任务同栈，授权结果可靠回传；
 * 3. 创建后立即拉起授权，不依赖任何前台服务就绪信号，杜绝"点了没反应"；
 * 4. 授权结果在本页就地创建 MediaProjection，授权令牌不跨组件传递。
 */
class ConsentActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "PureShot"
    }

    private val consentLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val ok = result.resultCode == RESULT_OK && result.data != null
        Log.d(TAG, "consent result: code=${result.resultCode}, hasData=${result.data != null}")
        if (ok) {
            CaptureManager.onConsent(this, result.resultCode, result.data!!)
        } else {
            CaptureManager.queueAbandon()
            ErrorReporter.toastRes(this, R.string.capture_cancelled)
        }
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_consent)
        // 仅首次创建时拉起，配置变更重建不重复弹窗
        if (savedInstanceState == null) {
            try {
                val mgr = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                consentLauncher.launch(mgr.createScreenCaptureIntent())
                Log.d(TAG, "consent intent launched")
            } catch (e: Throwable) {
                Log.e(TAG, "launch consent failed", e)
                CaptureManager.queueAbandon()
                ErrorReporter.toastRes(this, R.string.capture_fail)
                finish()
            }
        }
    }
}