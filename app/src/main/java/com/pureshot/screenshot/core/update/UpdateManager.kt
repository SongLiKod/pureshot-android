package com.pureshot.screenshot.core.update

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.core.content.FileProvider
import com.pureshot.screenshot.BuildConfig
import com.pureshot.screenshot.core.util.ErrorReporter
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * 软件内版本更新（关于模块）：检查远端版本 → 应用内下载 APK → 直接在应用内调起安装。
 * 网络仅用于版本检查与安装包下载，不采集、不上传任何用户数据。
 */
object UpdateManager {

    /** 更新来源：GitHub Releases（由 .github/workflows/release.yml 发布产物） */
    private const val API_LATEST = "https://api.github.com/repos/SongLiKod/pureshot-android/releases/latest"
    private const val UA = "PureShot-Android-Updater"
    private const val UPDATE_DIR = "update"
    private const val APK_NAME = "pureshot-update.apk"

    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    data class UpdateInfo(
        val version: String,
        val notes: String,
        val apkUrl: String,
        val sizeBytes: Long
    )

    /** 当前已安装版本号，唯一来源为 build.gradle.kts 的 versionName */
    fun currentVersion(): String = BuildConfig.VERSION_NAME

    fun isOnline(ctx: Context): Boolean {
        return try {
            val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val net = cm.activeNetwork ?: return false
            val cap = cm.getNetworkCapabilities(net) ?: return false
            cap.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } catch (t: Throwable) {
            true
        }
    }

    /** 检查更新：成功时 null=已是最新，非 null=存在新版本；失败携带异常原因。 */
    fun check(ctx: Context, onResult: (Result<UpdateInfo?>) -> Unit) {
        val app = ctx.applicationContext
        io.execute {
            val r = runCatching { fetchLatest(app) }
            main.post { onResult(r) }
        }
    }

    private fun fetchLatest(ctx: Context): UpdateInfo? {
        val obj = JSONObject(httpGet(API_LATEST))
        val tag = obj.optString("tag_name").trim()
        val latestVersion = tag.removePrefix("v").removePrefix("V").trim()
        if (latestVersion.isBlank() || compareVersion(latestVersion, currentVersion()) <= 0) return null

        val notes = obj.optString("body", "").trim()
        val assets = obj.optJSONArray("assets")
        var url: String? = null
        var size = 0L
        if (assets != null) {
            var fallbackUrl: String? = null
            var fallbackSize = 0L
            for (i in 0 until assets.length()) {
                val a = assets.optJSONObject(i) ?: continue
                val name = a.optString("name")
                if (!name.endsWith(".apk", true)) continue
                val candidate = a.optString("browser_download_url")
                if (candidate.isBlank()) continue
                if (name.contains("debug", true)) {
                    fallbackUrl = candidate
                    fallbackSize = a.optLong("size")
                } else {
                    url = candidate
                    size = a.optLong("size")
                    break
                }
            }
            if (url == null) {
                url = fallbackUrl
                size = fallbackSize
            }
        }
        val apkUrl = url ?: return null
        return UpdateInfo(latestVersion, notes, apkUrl, size)
    }

    /**
     * 应用内下载更新包到私有缓存目录。下载与进度回调全程不离开应用。
     * 通过 [isCancelled] 支持中途取消，取消走 [onCancelled]。
     */
    fun download(
        ctx: Context,
        info: UpdateInfo,
        onProgress: (downloaded: Long, total: Long) -> Unit,
        isCancelled: () -> Boolean,
        onDone: (File) -> Unit,
        onCancelled: () -> Unit,
        onError: (Throwable) -> Unit
    ) {
        val app = ctx.applicationContext
        io.execute {
            try {
                val apk = performDownload(app, info, onProgress, isCancelled)
                if (apk == null) main.post { onCancelled() } else main.post { onDone(apk) }
            } catch (t: Throwable) {
                main.post { onError(t) }
            }
        }
    }

    /** 执行下载，返回 null 表示被取消。 */
    private fun performDownload(
        app: Context,
        info: UpdateInfo,
        onProgress: (downloaded: Long, total: Long) -> Unit,
        isCancelled: () -> Boolean
    ): File? {
        val dir = File(app.cacheDir, UPDATE_DIR)
        if (dir.exists()) dir.listFiles()?.forEach { it.delete() } else dir.mkdirs()
        val tmp = File(dir, "$APK_NAME.part")
        val out = File(dir, APK_NAME)

        val conn = (URL(info.apkUrl).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            requestMethod = "GET"
            setRequestProperty("User-Agent", UA)
            instanceFollowRedirects = true
        }
        try {
            val code = conn.responseCode
            if (code !in 200..299) throw IllegalStateException("HTTP $code")
            val total = if (info.sizeBytes > 0) info.sizeBytes else conn.contentLength.toLong()
            var read = 0L
            conn.inputStream.use { input ->
                FileOutputStream(tmp).use { fos ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        if (isCancelled()) {
                            fos.flush()
                            tmp.delete()
                            return null
                        }
                        val n = input.read(buf)
                        if (n < 0) break
                        fos.write(buf, 0, n)
                        read += n
                        val d = read
                        main.post { onProgress(d, total) }
                    }
                    fos.flush()
                }
            }
            if (tmp.length() <= 0L) throw IllegalStateException("下载内容为空")
            if (out.exists()) out.delete()
            if (!tmp.renameTo(out)) {
                tmp.copyTo(out, overwrite = true)
                tmp.delete()
            }
            return out
        } finally {
            conn.disconnect()
        }
    }

    /** 是否已获得「安装未知应用」授权（Android 8.0+ 需要） */
    fun canRequestInstall(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || ctx.packageManager.canRequestPackageInstalls()

    /** 跳转本应用的「安装未知应用」授权页 */
    fun installPermissionIntent(ctx: Context): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** 调起系统安装器安装已下载好的 APK（不经过浏览器/文件管理器）。 */
    fun install(ctx: Context, apk: File): Boolean = try {
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", apk)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        ctx.startActivity(intent)
        true
    } catch (t: Throwable) {
        ErrorReporter.dialog(ctx, t)
        false
    }

    private fun httpGet(url: String): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 15_000
            requestMethod = "GET"
            setRequestProperty("User-Agent", UA)
            setRequestProperty("Accept", "application/vnd.github+json")
            instanceFollowRedirects = true
        }
        try {
            val code = conn.responseCode
            if (code !in 200..299) throw IllegalStateException("HTTP $code")
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    /** 语义化版本比较：a>b 返回 1，a<b 返回 -1，相等返回 0。 */
    private fun compareVersion(a: String, b: String): Int {
        fun parts(s: String) = s.split(Regex("[.\\-_+]"))
            .map { it.takeWhile { c -> c.isDigit() }.toIntOrNull() ?: 0 }
        val pa = parts(a)
        val pb = parts(b)
        for (i in 0 until maxOf(pa.size, pb.size)) {
            val x = pa.getOrElse(i) { 0 }
            val y = pb.getOrElse(i) { 0 }
            if (x != y) return if (x > y) 1 else -1
        }
        return 0
    }
}
