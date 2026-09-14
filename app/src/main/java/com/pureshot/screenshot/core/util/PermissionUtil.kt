package com.pureshot.screenshot.core.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings

/**
 * 权限工具：最小权限原则。必选=媒体投影(按需)+媒体存储；可选=悬浮窗/无障碍/通知。
 */
object PermissionUtil {

    fun hasOverlay(ctx: Context): Boolean = Settings.canDrawOverlays(ctx)

    fun hasNotification(ctx: Context): Boolean =
        if (Build.VERSION >= Build.VERSION_CODES.TIRAMISU) {
            ctx.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        } else {
            true
        }

    fun hasMediaRead(ctx: Context): Boolean {
        val perm = if (Build.VERSION >= Build.VERSION_CODES.TIRAMISU)
            android.Manifest.permission.READ_MEDIA_IMAGES
        else android.Manifest.permission.READ_EXTERNAL_STORAGE
        return ctx.checkSelfPermission(perm) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    fun isAccessibilityEnabled(ctx: Context): Boolean {
        val expected = "com.pureshot.screenshot/.core.longshot.LongShotAccessibilityService"
        val enabled = Settings.Secure.getString(
            ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabled.split(':').any { it.equals(expected, true) || it.endsWith("LongShotAccessibilityService") }
    }

    fun overlayIntent(ctx: Context): Intent =
        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + ctx.packageName))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun notificationIntent(ctx: Context): Intent =
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun accessibilityIntent(): Intent =
        Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun batteryOptimizeIntent(ctx: Context): Intent =
        Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun isIgnoringBatteryOptimizations(ctx: Context): Boolean = try {
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
        pm.isIgnoringBatteryOptimizations(ctx.packageName)
    } catch (e: Throwable) {
        false
    }
}
