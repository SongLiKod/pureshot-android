package com.pureshot.screenshot

import android.content.Intent
import android.os.Bundle
import android.service.quicksettings.TileService
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import com.pureshot.screenshot.core.capture.CaptureManager
import com.pureshot.screenshot.core.util.ErrorReporter
import com.pureshot.screenshot.ui.editor.EditorFragment
import com.pureshot.screenshot.ui.gallery.GalleryFragment
import com.pureshot.screenshot.ui.home.HomeFragment
import com.pureshot.screenshot.ui.settings.SettingsFragment
import com.google.android.material.bottomnavigation.BottomNavigationView

/**
 * 单 Activity 多 Fragment 架构（MVVM 分层：View → ViewModel/Fragment → Repository/Manager → Utils → Core）。
 * 同时作为媒体投影授权的发起页：授权由真实前台 Activity 发起并就地创建 MediaProjection，
 * 避免透明中转页与跨组件传令牌在 MIUI 等 ROM 上的兼容问题。
 */
class MainActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_EDITOR = "editor_path"
        const val EXTRA_OPEN_SETTINGS = "open_settings"
        const val ACTION_REQUEST_CONSENT = "com.pureshot.screenshot.action.REQUEST_CONSENT"
    }

    private var editorShown = false

    /** 媒体投影授权结果：就地创建投影并开始捕获 */
    private val consentLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            CaptureManager.onConsent(this, result.resultCode, result.data!!)
        } else {
            CaptureManager.queueAbandon()
            ErrorReporter.toastRes(this, R.string.capture_cancelled)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        val nav = findViewById<BottomNavigationView>(R.id.bottom_nav)
        nav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_home -> show(HomeFragment::class.java)
                R.id.nav_gallery -> show(GalleryFragment::class.java)
                R.id.nav_settings -> show(SettingsFragment::class.java)
            }
            true
        }
        if (savedInstanceState == null && !handleIntent()) {
            nav.selectedItemId = R.id.nav_home
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent()
    }

    private fun handleIntent(): Boolean {
        if (intent?.action == ACTION_REQUEST_CONSENT) {
            CaptureManager.launchConsent(this, consentLauncher)
            return true
        }
        val path = intent?.getStringExtra(EXTRA_EDITOR)
        if (path != null) {
            val editor = EditorFragment().apply {
                arguments = Bundle().apply { putString(EditorFragment.ARG_PATH, path) }
            }
            supportFragmentManager.beginTransaction()
                .replace(R.id.fragment_container, editor, "editor")
                .addToBackStack("editor")
                .commit()
            editorShown = true
            return true
        }
        val openSettings = intent?.getBooleanExtra(EXTRA_OPEN_SETTINGS, false) == true ||
            intent?.action == TileService.ACTION_QS_TILE_PREFERENCES
        if (openSettings) {
            findViewById<BottomNavigationView>(R.id.bottom_nav).selectedItemId = R.id.nav_settings
            return true
        }
        return false
    }

    private fun <T : Fragment> show(clazz: Class<T>) {
        if (editorShown) {
            editorShown = false
        }
        val tag = clazz.simpleName
        val existing = supportFragmentManager.findFragmentByTag(tag)
        supportFragmentManager.beginTransaction()
            .setReorderingAllowed(true)
            .replace(R.id.fragment_container, existing ?: clazz.getDeclaredConstructor().newInstance(), tag)
            .commit()
    }
}