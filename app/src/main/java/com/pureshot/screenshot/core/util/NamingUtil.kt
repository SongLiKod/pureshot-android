package com.pureshot.screenshot.core.util

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 文件命名规则：模板占位符 {date} {time} {ts} {w} {h}。
 */
object NamingUtil {

    fun render(template: String, width: Int, height: Int, ext: String): String {
        val now = System.currentTimeMillis()
        val date = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date(now))
        val time = SimpleDateFormat("HHmmss", Locale.US).format(Date(now))
        val base = template
            .replace("{date}", date)
            .replace("{time}", time)
            .replace("{ts}", now.toString())
            .replace("{w}", width.toString())
            .replace("{h}", height.toString())
            .ifBlank { "PureShot_$now" }
        return "$base.$ext"
    }
}
