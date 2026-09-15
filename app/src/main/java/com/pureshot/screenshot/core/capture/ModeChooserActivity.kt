package com.pureshot.screenshot.core.capture

import android.os.Bundle
import android.view.LayoutInflater
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.card.MaterialCardView
import com.pureshot.screenshot.R
import com.pureshot.screenshot.core.Prefs
import com.pureshot.screenshot.ui.settings.Dialogs

/**
 * 截图模式选择弹窗（Issue19）：磁贴单击/悬浮球点击触发，弹窗内选择截图模式。
 */
class ModeChooserActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_mode_chooser)
        setFinishOnTouchOutside(true)
        val container = findViewById<LinearLayout>(R.id.chooser_container)
        val modes = listOf(
            Triple(CaptureMode.FULL, R.string.mode_full, R.drawable.ic_mode_full),
            Triple(CaptureMode.APP, R.string.mode_app, R.drawable.ic_mode_app),
            Triple(CaptureMode.REGION, R.string.mode_region, R.drawable.ic_mode_region),
            Triple(CaptureMode.DELAY, R.string.mode_delay, R.drawable.ic_mode_delay),
            Triple(CaptureMode.LONG, R.string.mode_long, R.drawable.ic_mode_long)
        )
        for ((mode, titleRes, iconRes) in modes) {
            val row = LayoutInflater.from(this).inflate(R.layout.item_mode, container, false)
            row.findViewById<android.widget.ImageView>(R.id.mode_icon).setImageResource(iconRes)
            row.findViewById<android.widget.TextView>(R.id.mode_title).setText(titleRes)
            row.findViewById<android.widget.TextView>(R.id.mode_desc).visibility = android.view.View.GONE
            (row as MaterialCardView).setOnClickListener {
                if (mode != CaptureMode.DELAY && Dialogs.maybePromptFastCapture(this)) return@setOnClickListener
                CaptureManager.request(applicationContext, mode)
                finish()
            }
            if (mode == CaptureMode.DELAY) {
                row.setOnLongClickListener {
                    Dialogs.delayPicker(this) { /* 结果已写入 Prefs.delaySeconds */ }
                    true
                }
            }
            container.addView(row)
        }
        findViewById<com.google.android.material.button.MaterialButton>(R.id.btn_chooser_cancel)
            .setOnClickListener { finish() }
        // 提示当前延迟时长
        container.getChildAt(3).findViewById<android.widget.TextView>(R.id.mode_desc).apply {
            visibility = android.view.View.VISIBLE
            text = getString(R.string.delay_fmt, Prefs.delaySeconds)
        }
    }
}
