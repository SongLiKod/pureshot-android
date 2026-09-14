package com.pureshot.screenshot.api

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import com.pureshot.screenshot.core.capture.CaptureManager
import com.pureshot.screenshot.core.capture.CaptureMode

/**
 * 自动化 API（Phase3）：供系统快捷指令/自动化任务（Tasker 等）通过标准 Intent 触发截图。
 *
 * 用法：
 *   am start -a com.pureshot.screenshot.action.CAPTURE --es mode full|app|region|delay|long
 *   或直接发送对应 action：com.pureshot.screenshot.action.CAPTURE_FULL / _APP / _REGION / _DELAY / _LONG
 * 仅按需拉起截图链路，不导出任何界面数据，保持纯净合规。
 */
class CaptureApiActivity : Activity() {

    companion object {
        const val ACTION_CAPTURE = "com.pureshot.screenshot.action.CAPTURE"
        const val ACTION_CAPTURE_FULL = "com.pureshot.screenshot.action.CAPTURE_FULL"
        const val ACTION_CAPTURE_APP = "com.pureshot.screenshot.action.CAPTURE_APP"
        const val ACTION_CAPTURE_REGION = "com.pureshot.screenshot.action.CAPTURE_REGION"
        const val ACTION_CAPTURE_DELAY = "com.pureshot.screenshot.action.CAPTURE_DELAY"
        const val ACTION_CAPTURE_LONG = "com.pureshot.screenshot.action.CAPTURE_LONG"
        const val EXTRA_MODE = "mode"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val mode = parseMode()
        CaptureManager.request(applicationContext, mode)
        setResult(RESULT_OK)
        finish()
    }

    private fun parseMode(): CaptureMode {
        val fromExtra = intent.getStringExtra(EXTRA_MODE)?.trim()?.lowercase()
        if (!fromExtra.isNullOrEmpty()) {
            try {
                return CaptureMode.valueOf(fromExtra.uppercase())
            } catch (e: Throwable) { /* 非法模式名回退全屏 */ }
        }
        return when (intent.action) {
            ACTION_CAPTURE_APP -> CaptureMode.APP
            ACTION_CAPTURE_REGION -> CaptureMode.REGION
            ACTION_CAPTURE_DELAY -> CaptureMode.DELAY
            ACTION_CAPTURE_LONG -> CaptureMode.LONG
            else -> CaptureMode.FULL
        }
    }
}
