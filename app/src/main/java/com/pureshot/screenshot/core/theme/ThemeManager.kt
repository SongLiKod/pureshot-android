package com.pureshot.screenshot.core.theme

import androidx.appcompat.app.AppCompatDelegate
import com.pureshot.screenshot.core.Prefs

/**
 * 三主题模式：浅色/深色/跟随系统（默认初始=跟随系统）。
 * 全局硬编码品牌色值，仅明暗适配；主题状态持久化保存。
 */
object ThemeManager {

    const val MODE_SYSTEM = 0
    const val MODE_LIGHT = 1
    const val MODE_DARK = 2

    fun applyFromPrefs() {
        AppCompatDelegate.setDefaultNightMode(
            when (Prefs.themeMode) {
                MODE_LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
                MODE_DARK -> AppCompatDelegate.MODE_NIGHT_YES
                else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            }
        )
    }

    fun setMode(mode: Int) {
        Prefs.themeMode = mode
        applyFromPrefs()
    }
}
