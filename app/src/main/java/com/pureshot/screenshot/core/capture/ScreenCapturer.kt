package com.pureshot.screenshot.core.capture

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.ImageReader
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.view.WindowManager
import com.pureshot.screenshot.core.util.ErrorReporter
import kotlinx.coroutines.suspendCancellableCoroutine
import java.nio.ByteBuffer
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * 单次屏幕捕获：VirtualDisplay + ImageReader，捕获完成立即释放，无常驻。
 * 适配 API29-34，高版本 API 均做版本判断，低版本自动降级。
 */
object ScreenCapturer {

    fun displaySize(ctx: Context): Triple<Int, Int, Int> {
        val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        return if (android.os.Build.VERSION.SDK_INT >= 30) {
            val b = wm.currentWindowMetrics.bounds
            Triple(b.width(), b.height(), ctx.resources.displayMetrics.densityDpi)
        } else {
            @Suppress("DEPRECATION")
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(metrics)
            Triple(metrics.widthPixels, metrics.heightPixels, metrics.densityDpi)
        }
    }

    suspend fun capture(ctx: Context): Bitmap = suspendCancellableCoroutine { cont ->
        val (w, h, dpi) = displaySize(ctx)
        val projection = CaptureManager.projection
            ?: throw IllegalStateException("MediaProjection 未就绪")
        val reader = ImageReader.newInstance(w, h, android.graphics.PixelFormat.RGBA_8888, 2)
        var display: VirtualDisplay? = null
        val handler = Handler(Looper.getMainLooper())
        var resumed = false
        val timeout = Runnable {
            if (!resumed) {
                resumed = true
                reader.close()
                display?.release()
                cont.resumeWithException(IllegalStateException("捕获超时"))
            }
        }
        handler.postDelayed(timeout, 6000)
        reader.setOnImageAvailableListener({ r ->
            if (resumed) return@setOnImageAvailableListener
            val image = r.acquireLatestImage() ?: return@setOnImageAvailableListener
            try {
                val bmp = fromImage(image)
                image.close()
                resumed = true
                handler.removeCallbacks(timeout)
                display?.release()
                r.close()
                cont.resume(bmp)
            } catch (e: Throwable) {
                image.close()
                resumed = true
                handler.removeCallbacks(timeout)
                display?.release()
                r.close()
                cont.resumeWithException(e)
            }
        }, handler)
        display = projection.createVirtualDisplay(
            "pureshot-capture", w, h, dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC,
            reader.surface, null, null
        )
        cont.invokeOnCancellation {
            if (!resumed) {
                resumed = true
                handler.removeCallbacks(timeout)
            }
            display?.release()
            reader.close()
        }
    }

    private fun fromImage(image: android.media.Image): Bitmap {
        val plane = image.planes[0]
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        val buffer: ByteBuffer = plane.buffer.duplicate()
        val paddedWidth = rowStride / pixelStride
        val out = Bitmap.createBitmap(image.width, image.height, Bitmap.Config.ARGB_8888)
        if (paddedWidth == image.width) {
            val bytes = ByteArray(rowStride * image.height)
            buffer.get(bytes, 0, minOf(bytes.size, buffer.remaining()))
            out.copyPixelsFromBuffer(ByteBuffer.wrap(bytes))
        } else {
            val tmp = Bitmap.createBitmap(paddedWidth, image.height, Bitmap.Config.ARGB_8888)
            val bytes = ByteArray(rowStride * image.height)
            buffer.get(bytes, 0, minOf(bytes.size, buffer.remaining()))
            tmp.copyPixelsFromBuffer(ByteBuffer.wrap(bytes))
            val canvas = android.graphics.Canvas(out)
            canvas.drawBitmap(tmp, android.graphics.Rect(0, 0, image.width, image.height),
                android.graphics.Rect(0, 0, image.width, image.height), null)
            tmp.recycle()
        }
        return out
    }
}
