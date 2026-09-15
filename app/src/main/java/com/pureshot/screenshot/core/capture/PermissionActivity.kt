package com.pureshot.screenshot.core.capture

import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.pureshot.screenshot.core.util.ErrorReporter

/**
 * 透明中转页：按需申请媒体投影授权（单次授权，无常驻），授权结果交回 CaptureManager。
 *
 * 注意：本页必须与调用方处于同一任务栈（Manifest 中为标准 singleTop、同应用亲和性），
 * 否则系统会在用户尚未点击前就把 startActivityForResult 结果置为 RESULT_CANCELED，
 * 导致投影令牌永远拿不到、截图无响应。
 */
class PermissionActivity : AppCompatActivity() {

    private val consentLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            CaptureManager.onConsent(result.resultCode, result.data!!)
            // 授权已拿到：把主任务退到后台，让捕获到的是用户平时用的应用而不是净截自身
            try {
                moveTaskToBack(true)
            } catch (e: Throwable) { /* 容错 */ }
        } else {
            CaptureManager.queueAbandon()
            ErrorReporter.toastRes(this, com.pureshot.screenshot.R.string.capture_cancelled)
        }
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Android14 强制要求授权页在 mediaProjection 前台服务 startForeground 完成后才可发起
        CaptureManager.whenPrepared { launchConsent() }
    }

    private fun launchConsent() {
        if (isFinishing) return
        try {
            val mgr = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            consentLauncher.launch(mgr.createScreenCaptureIntent())
        } catch (e: Throwable) {
            ErrorReporter.dialog(this, e)
            CaptureManager.queueAbandon()
            finish()
        }
    }
}