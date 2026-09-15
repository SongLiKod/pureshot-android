package com.pureshot.screenshot.core.capture

import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.suspendCancellableCoroutine
import java.nio.ByteBuffer
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * 会话级捕获引擎。
 *
 * 问题：逐次「建显示→抓帧→释放→再重建」在 MIUI 等 ROM 会终止整个媒体投影会话
 * （表现为第二次点击时"屏幕共享"结束、截图失败）。
 *
 * 方案：一次授权只创建【一个】常驻 VirtualDisplay；每次抓取通过 setSurface()
 * 换绑新 Surface 强制推送当前帧（静态画面同样能取到最新一帧）。会话结束才释放显示。
 */
class CaptureSession(
    private val projection: MediaProjection,
    private var width: Int,
    private var height: Int,
    private val dpi: Int
) {

    private var display: VirtualDisplay? = null
    private val handler = Handler(Looper.getMainLooper())

    /** 尺寸变化（屏幕旋转等）时重建显示 */
    fun isSizeChanged(w: Int, h: Int): Boolean = display == null || w != width || h != height

    fun recreate(w: Int, h: Int) {
        width = w
        height = h
        close()
        open()
    }

    fun open() {
        if (display != null) return
        display = projection.createVirtualDisplay(
            "pureshot-session", width, height, dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC,
            null, null, null
        )
        Log.d("PureShot", "capture session open: ${width}x${height} dpi=$dpi")
    }

    /** 抓取一帧当前屏幕：换绑新 Surface 强制推送，等待首帧，读位图后释放 ImageReader（不释放显示） */
    suspend fun snapshot(): Bitmap = suspendCancellableCoroutine { cont ->
        val d = display ?: run {
            cont.resumeWithException(IllegalStateException("capture session closed"))
            return@suspendCancellableCoroutine
        }
        val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        var resumed = false
        val timeout = Runnable {
            if (!resumed) {
                resumed = true
                Log.e("PureShot", "snapshot timeout")
                reader.close()
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
                Log.d("PureShot", "snapshot ok: ${bmp.width}x${bmp.height}")
                handler.removeCallbacks(timeout)
                r.close()
                cont.resume(bmp)
            } catch (e: Throwable) {
                Log.e("PureShot", "snapshot parse failed", e)
                image.close()
                resumed = true
                handler.removeCallbacks(timeout)
                r.close()
                cont.resumeWithException(e)
            }
        }, handler)
        try {
            d.setSurface(reader.surface)
            Log.d("PureShot", "snapshot: surface rebound")
        } catch (e: Throwable) {
            Log.e("PureShot", "snapshot setSurface failed", e)
            handler.removeCallbacks(timeout)
            reader.close()
            if (!resumed) {
                resumed = true
                cont.resumeWithException(e)
            }
            return@suspendCancellableCoroutine
        }
        cont.invokeOnCancellation {
            if (!resumed) {
                resumed = true
                handler.removeCallbacks(timeout)
            }
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

    fun close() {
        try {
            display?.release()
        } catch (e: Throwable) { /* 容错 */ }
        display = null
    }
}