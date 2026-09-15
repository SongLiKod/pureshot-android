package com.pureshot.screenshot.ui.settings

import android.content.Context
import android.os.Build
import android.text.InputType
import android.widget.EditText
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.pureshot.screenshot.R
import com.pureshot.screenshot.core.Prefs
import com.pureshot.screenshot.core.util.PermissionUtil

/**
 * 通用对话框：低版本应用截图适配提示、延迟时长选择、ROM 增强合规说明、极速截图引导。
 */
object Dialogs {

    /**
     * 首次截图时引导开启无障碍极速截图（Android11+）。
     * 返回 true 表示本次已弹出引导，调用方应跳过截图流程；返回 false 表示可继续正常截图。
     */
    fun maybePromptFastCapture(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false
        if (!Prefs.fastCapture || Prefs.fastCapturePrompted) return false
        if (PermissionUtil.isAccessibilityEnabled(ctx)) return false
        Prefs.fastCapturePrompted = true
        MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.fast_capture_notice_title)
            .setMessage(R.string.fast_capture_notice_msg)
            .setPositiveButton(R.string.acc_go_settings) { _, _ ->
                try {
                    ctx.startActivity(PermissionUtil.accessibilityIntent())
                } catch (e: Throwable) { /* 容错 */ }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
        return true
    }

    /** Issue3：低版本（Android10-13）应用截图降级方案提示弹窗 */
    fun appModeNotice(ctx: Context, onGo: () -> Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            onGo()
            return
        }
        MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.app_low_version_notice)
            .setMessage(R.string.app_low_version_msg)
            .setPositiveButton(R.string.ok) { _, _ -> onGo() }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    fun delayPicker(ctx: Context, onDone: () -> Unit) {
        val items = arrayOf(
            ctx.getString(R.string.delay_3),
            ctx.getString(R.string.delay_5),
            ctx.getString(R.string.delay_custom)
        )
        MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.delay_dialog_title)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> { Prefs.delaySeconds = 3; onDone() }
                    1 -> { Prefs.delaySeconds = 5; onDone() }
                    else -> customDelay(ctx, onDone)
                }
            }
            .show()
    }

    private fun customDelay(ctx: Context, onDone: () -> Unit) {
        val input = EditText(ctx).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            hint = ctx.getString(R.string.delay_custom_hint)
            setText(Prefs.delaySeconds.toString())
        }
        MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.delay_custom)
            .setView(input)
            .setPositiveButton(R.string.ok) { _, _ ->
                val v = input.text.toString().toIntOrNull()?.coerceIn(1, 30)
                if (v != null) Prefs.delaySeconds = v
                onDone()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** Issue5：全界面增强截取合规限制提示 */
    fun romNotice(ctx: Context, onAccept: () -> Unit) {
        MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.rom_notice_title)
            .setMessage(R.string.rom_notice_msg)
            .setPositiveButton(R.string.ok) { _, _ -> onAccept() }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }
}
