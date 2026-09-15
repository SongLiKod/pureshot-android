package com.pureshot.screenshot.core

import android.content.Context
import android.content.SharedPreferences

/**
 * 轻量配置存储（SharedPreferences），所有默认参数按文档最优适配大众使用习惯。
 */
object Prefs {
    private const val NAME = "pureshot_prefs"
    private lateinit var sp: SharedPreferences

    fun init(ctx: Context) {
        sp = ctx.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)
    }

    // ---------- 主题 ----------
    /** 0=跟随系统(默认初始) 1=浅色 2=深色 */
    var themeMode: Int
        get() = sp.getInt("theme_mode", 0)
        set(v) = sp.edit().putInt("theme_mode", v).apply()

    // ---------- 截图设置 ----------
    var delaySeconds: Int
        get() = sp.getInt("delay_seconds", 3)
        set(v) = sp.edit().putInt("delay_seconds", v).apply()

    /** 全界面增强截取开关，默认关闭 */
    var romEnhanced: Boolean
        get() = sp.getBoolean("rom_enhanced", false)
        set(v) = sp.edit().putBoolean("rom_enhanced", v).apply()

    /** 无障碍自动滚动长截图（可选，默认关闭） */
    var accAutoScroll: Boolean
        get() = sp.getBoolean("acc_auto_scroll", false)
        set(v) = sp.edit().putBoolean("acc_auto_scroll", v).apply()

    /** 极速截图：无障碍可用时直接成像，免每次「共享屏幕」授权弹窗（默认开启，需无障碍服务已连接） */
    var fastCapture: Boolean
        get() = sp.getBoolean("fast_capture", true)
        set(v) = sp.edit().putBoolean("fast_capture", v).apply()

    /** 是否已提示过开启无障碍极速截图 */
    var fastCapturePrompted: Boolean
        get() = sp.getBoolean("fast_capture_prompted", false)
        set(v) = sp.edit().putBoolean("fast_capture_prompted", v).apply()

    var floatingBall: Boolean
        get() = sp.getBoolean("floating_ball", false)
        set(v) = sp.edit().putBoolean("floating_ball", v).apply()

    var floatingPreview: Boolean
        get() = sp.getBoolean("floating_preview", true)
        set(v) = sp.edit().putBoolean("floating_preview", v).apply()

    // ---------- 编辑设置 ----------
    /** 0=小 1=中(默认) 2=大 */
    var mosaicSize: Int
        get() = sp.getInt("mosaic_size", 1)
        set(v) = sp.edit().putInt("mosaic_size", v).apply()

    /** 0=低 1=中(默认) 2=高 */
    var blurStrength: Int
        get() = sp.getInt("blur_strength", 1)
        set(v) = sp.edit().putInt("blur_strength", v).apply()

    var rememberParams: Boolean
        get() = sp.getBoolean("remember_params", true)
        set(v) = sp.edit().putBoolean("remember_params", v).apply()

    var lastColor: Int
        get() = sp.getInt("last_color", com.pureshot.screenshot.core.editor.ANNO_DEFAULT_COLOR)
        set(v) = sp.edit().putInt("last_color", v).apply()

    var lastStrokeWidth: Float
        get() = sp.getFloat("last_stroke_width", 8f)
        set(v) = sp.edit().putFloat("last_stroke_width", v).apply()

    var lastTextSize: Float
        get() = sp.getFloat("last_text_size", 48f)
        set(v) = sp.edit().putFloat("last_text_size", v).apply()

    var lastArrowHead: Float
        get() = sp.getFloat("last_arrow_head", 28f)
        set(v) = sp.edit().putFloat("last_arrow_head", v).apply()

    // ---------- 导出设置 ----------
    /** 默认导出格式：WebP（平衡画质与体积） */
    var defaultFormat: String
        get() = sp.getString("default_format", "webp") ?: "webp"
        set(v) = sp.edit().putString("default_format", v).apply()

    /** JPG/JPEG 质量：60/80/100，默认 80 */
    var jpgQuality: Int
        get() = sp.getInt("jpg_quality", 80)
        set(v) = sp.edit().putInt("jpg_quality", v).apply()

    /** 高级导出参数：WebP 质量，默认 90 */
    var webpQuality: Int
        get() = sp.getInt("webp_quality", 90)
        set(v) = sp.edit().putInt("webp_quality", v).apply()

    /** 高级导出参数：导出缩放 50/75/100，默认 100 */
    var exportScale: Int
        get() = sp.getInt("export_scale", 100)
        set(v) = sp.edit().putInt("export_scale", v).apply()

    /** 自定义保存目录（SAF tree uri），空=默认 Pictures/PureShot */
    var saveTreeUri: String
        get() = sp.getString("save_tree_uri", "") ?: ""
        set(v) = sp.edit().putString("save_tree_uri", v).apply()

    var nameTemplate: String
        get() = sp.getString("name_template", "PureShot_{date}_{time}") ?: "PureShot_{date}_{time}"
        set(v) = sp.edit().putString("name_template", v).apply()

    var showInGallery: Boolean
        get() = sp.getBoolean("show_in_gallery", true)
        set(v) = sp.edit().putBoolean("show_in_gallery", v).apply()

    var autoSaveOriginal: Boolean
        get() = sp.getBoolean("auto_save_original", false)
        set(v) = sp.edit().putBoolean("auto_save_original", v).apply()

    var exportScope: Int
        /** 0=全图(含裁剪) 1=未裁剪原图 2=仅标注图层 */
        get() = sp.getInt("export_scope", 0)
        set(v) = sp.edit().putInt("export_scope", v).apply()
}
