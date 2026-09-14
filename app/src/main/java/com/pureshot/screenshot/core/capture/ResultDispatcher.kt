package com.pureshot.screenshot.core.capture

import android.content.Context
import android.graphics.Bitmap
import com.pureshot.screenshot.core.Prefs
import com.pureshot.screenshot.core.export.ExportManager
import com.pureshot.screenshot.core.util.ErrorReporter
import com.pureshot.screenshot.ui.preview.FloatingPreview
import com.pureshot.screenshot.ui.preview.ResultNotifier

/**
 * 截图结果统一分发（Issue20）：所有截图模式统一预览入口。
 * 悬浮预览 + 结果通知（编辑/保存/分享/删除快捷操作）+ 原图自动保存。
 */
object ResultDispatcher {

    fun dispatch(ctx: Context, bitmap: Bitmap, mode: CaptureMode) {
        val app = ctx.applicationContext
        try {
            if (Prefs.autoSaveOriginal) {
                ExportManager.saveBitmap(
                    app, bitmap, Prefs.defaultFormat, Prefs.jpgQuality,
                    withAnnotations = false
                ) { uri ->
                    uri?.let { ErrorReporter.toast(app, app.getString(com.pureshot.screenshot.R.string.saved_to, it)) }
                }
            }
            val file = CacheStore.save(app, bitmap)
            if (Prefs.floatingPreview) {
                FloatingPreview.show(app, bitmap, file.absolutePath, mode.ordinal)
            }
            ResultNotifier.notify(app, file.absolutePath, mode)
        } catch (e: Throwable) {
            ErrorReporter.dialog(app, e)
        }
    }
}
