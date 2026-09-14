package com.pureshot.screenshot.core.util

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.pureshot.screenshot.R

/**
 * 全流程异常捕获与友好提示：截图、编辑、导出各环节统一走此入口，避免闪退。
 */
object ErrorReporter {
    private val main = Handler(Looper.getMainLooper())

    fun toast(ctx: Context, msg: String) {
        main.post {
            android.widget.Toast.makeText(ctx, msg, android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    fun toastRes(ctx: Context, resId: Int) = toast(ctx, ctx.getString(resId))

    /** 弹窗友好提示错误信息 */
    fun dialog(ctx: Context, err: Throwable?) {
        main.post {
            val msg = err?.message ?: err?.javaClass?.simpleName ?: "Unknown"
            if (ctx is Activity && !ctx.isFinishing && !ctx.isDestroyed) {
                MaterialAlertDialogBuilder(ctx)
                    .setTitle(R.string.err_title)
                    .setMessage(ctx.getString(R.string.err_msg, msg))
                    .setPositiveButton(R.string.ok, null)
                    .show()
            } else {
                toast(ctx, ctx.getString(R.string.err_msg, msg))
            }
        }
    }

    /** 安全执行：捕获异常并友好提示，返回是否成功 */
    inline fun safe(onError: (Throwable) -> Unit, block: () -> Unit): Boolean = try {
        block(); true
    } catch (e: Throwable) {
        onError(e); false
    }
}
