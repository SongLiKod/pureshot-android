package com.pureshot.screenshot.core.util

import android.content.Context
import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions

/**
 * OCR 文字提取（Phase3）：基于端侧中文识别模型，全程离线、无网络权限。
 */
object OcrHelper {

    fun recognize(bmp: Bitmap, onResult: (String) -> Unit, onError: (String) -> Unit) {
        try {
            val recognizer = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
            val input = InputImage.fromBitmap(bmp, 0)
            recognizer.process(input)
                .addOnSuccessListener { text ->
                    recognizer.close()
                    onResult(text.text ?: "")
                }
                .addOnFailureListener { e ->
                    recognizer.close()
                    onError(e.message ?: e.javaClass.simpleName)
                }
                .addOnCanceledListener {
                    recognizer.close()
                    onError("cancelled")
                }
        } catch (e: Throwable) {
            onError(e.message ?: "OCR unavailable")
        }
    }
}
