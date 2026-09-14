package com.pureshot.screenshot.core.util

import android.os.Build

/**
 * 厂商 ROM 识别：小米(MIUI/HyperOS)、鸿蒙(EMUI/HarmonyOS)，用于全界面增强截取。
 */
object RomUtils {

    val isMiui: Boolean by lazy {
        readProp("ro.miui.ui.version.name") != null ||
            Build.MANUFACTURER.equals("Xiaomi", true) ||
            Build.MANUFACTURER.equals("Redmi", true) ||
            Build.BRAND.equals("POCO", true) || Build.BRAND.equals("Blackshark", true)
    }

    val isHarmonyOrEmui: Boolean by lazy {
        readProp("ro.build.hw_emui_api_level") != null ||
            readProp("msc.config.api_level") != null ||
            Build.MANUFACTURER.equals("Huawei", true) ||
            Build.MANUFACTURER.equals("HONOR", true)
    }

    val isEnhanceSupported: Boolean get() = isMiui || isHarmonyOrEmui

    fun romName(): String {
        val miui = readProp("ro.miui.ui.version.name")
        val harmony = readProp("msc.config.api_level") ?: readProp("ro.build.hw_emui_api_level")
        return when {
            miui != null -> "小米 MIUI/HyperOS"
            harmony != null -> "华为/鸿蒙 EMUI/HarmonyOS"
            Build.MANUFACTURER.equals("Xiaomi", true) || Build.MANUFACTURER.equals("Redmi", true) -> "小米"
            Build.MANUFACTURER.equals("Huawei", true) -> "华为"
            Build.MANUFACTURER.equals("HONOR", true) -> "荣耀"
            else -> Build.MANUFACTURER.ifBlank { Build.BRAND }.ifBlank { "标准 Android" }
        }
    }

    private fun readProp(key: String): String? = try {
        val clazz = Class.forName("android.os.SystemProperties")
        val get = clazz.getMethod("get", String::class.java)
        (get.invoke(null, key) as? String)?.takeIf { it.isNotBlank() }
    } catch (e: Throwable) {
        null
    }
}
