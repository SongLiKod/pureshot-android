package com.pureshot.screenshot.core.capture

import android.content.Context
import android.graphics.Bitmap
import com.pureshot.screenshot.core.util.BitmapUtil
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/**
 * 截图缓存中转：屏幕捕获结果先落盘缓存，再经预览/编辑/分享链路传递，避免 Binder 大图限制。
 */
object CacheStore {

    fun dir(ctx: Context): File = File(ctx.cacheDir, "pending").apply { mkdirs() }

    fun save(ctx: Context, bmp: Bitmap): File {
        val f = File(dir(ctx), "shot_${UUID.randomUUID()}.png")
        FileOutputStream(f).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return f
    }

    fun load(ctx: Context, path: String?, maxSide: Int = 4096): Bitmap? {
        if (path == null) return null
        return BitmapUtil.decodeSampled(path, maxSide)
    }

    fun cleanup(ctx: Context) {
        try {
            val cutoff = System.currentTimeMillis() - 24 * 3600_000L
            dir(ctx).listFiles()?.forEach { if (it.lastModified() < cutoff) it.delete() }
        } catch (e: Throwable) { /* 忽略清理失败 */ }
    }
}
