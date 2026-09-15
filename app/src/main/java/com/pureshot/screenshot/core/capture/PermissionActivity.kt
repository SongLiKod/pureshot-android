package com.pureshot.screenshot.core.capture

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import com.pureshot.screenshot.core.util.ErrorReporter

/**
 * 透明中转页：按需申请媒体投影授权（单次授权，无常驻），授权结果交回 CaptureManager。
 * Android14 强制要求授权页必须在 mediaProjection 前台服务 startForeground 完成后拉起，
 * 因此先等待服务就绪信号再发起授权（超时兜底防挂起）。
 */
class PermissionActivity : Activity() {

    companion object {
        private const val REQ = 0x5C01
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CaptureManager.whenPrepared { launchConsent() }
    }

    private fun launchConsent() {
        if (isFinishing) return
        try {
            val mgr = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            startActivityForResult(mgr.createScreenCaptureIntent(), REQ)
        } catch (e: Throwable) {
            ErrorReporter.dialog(this, e)
            CaptureManager.queueAbandon()
            finish()
        }
    }

    @Deprecated("Activity result API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ) {
            if (resultCode == RESULT_OK && data != null) {
                CaptureManager.onConsent(resultCode, data)
            } else {
                CaptureManager.queueAbandon()
                ErrorReporter.toastRes(this, com.pureshot.screenshot.R.string.capture_cancelled)
            }
        }
        finish()
    }
}
