package com.pureshot.screenshot.core.longshot

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.Looper
import com.pureshot.screenshot.core.capture.ScreenCapturer
import java.nio.ByteBuffer

/**
 * 长截图实时帧采样源：半分辨率 VirtualDisplay + ImageReader 持续取最新帧。
 */
class LongShotEngine(
    private val ctx: Context,
    private val projection: MediaProjection,
    private val scale: Float = 0.5f
) {

    val frameWidth: Int
    val frameHeight: Int

    private var reader: ImageReader? = null
    private var display: VirtualDisplay? = null
    private val handler = Handler(Looper.getMainLooper())

    @Volatile
    private var latest: IntArray? = null

    @Volatile
    private var latestHeight: Int = 0

    @Volatile
    var frameId: Long = 0
        private set

    init {
        val (w, h, dpi) = ScreenCapturer.displaySize(ctx)
        frameWidth = (w * scale).toInt().coerceAtLeast(1)
        frameHeight = (h * scale).toInt().coerceAtLeast(1)
    }

    fun start() {
        val r = ImageReader.newInstance(frameWidth, frameHeight, PixelFormat.RGBA_8888, 2)
        r.setOnImageAvailableListener({ ir ->
            val image = ir.acquireLatestImage() ?: return@setOnImageAvailableListener
            try {
                val plane = image.planes[0]
                val rowStride = plane.rowStride
                val pixelStride = plane.pixelStride
                val paddedWidth = rowStride / pixelStride
                val bytes = ByteArray(rowStride * image.height)
                plane.buffer.duplicate().let { buf ->
                    buf.get(bytes, 0, minOf(bytes.size, buf.remaining()))
                }
                var bmp = Bitmap.createBitmap(paddedWidth, image.height, Bitmap.Config.ARGB_8888)
                bmp.copyPixelsFromBuffer(ByteBuffer.wrap(bytes))
                if (paddedWidth != image.width) {
                    val cropped = Bitmap.createBitmap(bmp, 0, 0, image.width, image.height)
                    bmp.recycle()
                    bmp = cropped
                }
                val w = bmp.width
                val h = bmp.height
                val px = IntArray(w * h)
                bmp.getPixels(px, 0, w, 0, 0, w, h)
                bmp.recycle()
                latest = px
                latestHeight = h
                frameId++
            } catch (e: Throwable) { /* 单帧异常忽略 */ } finally {
                image.close()
            }
        }, handler)
        reader = r
        val (w, h, dpi) = ScreenCapturer.displaySize(ctx)
        display = projection.createVirtualDisplay(
            "pureshot-longshot", frameWidth, frameHeight, dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC,
            r.surface, null, null
        )
    }

    /** 取出当前最新帧（无新帧返回 null） */
    fun pollFrame(): Pair<IntArray, Int>? {
        val px = latest ?: return null
        latest = null
        return px to latestHeight
    }

    fun stop() {
        try {
            display?.release()
        } catch (e: Throwable) { /* 容错 */ }
        try {
            reader?.close()
        } catch (e: Throwable) { /* 容错 */ }
        display = null
        reader = null
        latest = null
    }
}
