package com.pureshot.screenshot

import android.app.Application
import com.pureshot.screenshot.core.Prefs
import com.pureshot.screenshot.core.capture.AppContext
import com.pureshot.screenshot.core.capture.CacheStore
import com.pureshot.screenshot.core.theme.ThemeManager
import com.pureshot.screenshot.ui.floating.FloatingBallService
import java.util.concurrent.Executors

class PureShotApp : Application() {

    override fun onCreate() {
        super.onCreate()
        Prefs.init(this)
        AppContext.init(this)
        ThemeManager.applyFromPrefs()
        Executors.newSingleThreadExecutor().execute { CacheStore.cleanup(this) }
        if (Prefs.floatingBall) {
            try {
                FloatingBallService.ensureRunning(this)
            } catch (e: Throwable) { /* 未授予悬浮窗权限时静默 */ }
        }
    }
}
