package com.pureshot.screenshot.ui.settings

import android.content.Context
import android.os.Build
import android.text.InputType
import android.widget.EditText
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.pureshot.screenshot.R
import com.pureshot.screenshot.core.Prefs

/**
 * 通用对话框：低版本应用截图适配提示、延迟时长选择、ROM 增强合规说明。
 */
object Dialogs {

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
