package com.pureshot.screenshot.ui.home

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.card.MaterialCardView
import com.google.android.material.chip.ChipGroup
import com.pureshot.screenshot.R
import com.pureshot.screenshot.core.Prefs
import com.pureshot.screenshot.core.capture.CaptureManager
import com.pureshot.screenshot.core.capture.CaptureMode
import com.pureshot.screenshot.core.theme.ThemeManager
import com.pureshot.screenshot.core.util.FoldableUtil
import com.pureshot.screenshot.core.util.RomUtils
import com.pureshot.screenshot.ui.settings.Dialogs

/**
 * 首页：五大截图模式入口 + 三主题切换 + ROM 识别信息。
 * 折叠屏适配：展开态双列网格，半开/折叠切换实时重排。
 */
class HomeFragment : Fragment() {

    private lateinit var modeContainer: LinearLayout
    private var columns = 1

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View =
        inflater.inflate(R.layout.fragment_home, container, false)

    override fun onViewCreated(view: View, s: Bundle?) {
        modeContainer = view.findViewById(R.id.mode_container)
        buildModes()

        val group = view.findViewById<ChipGroup>(R.id.theme_group)
        group.check(
            when (Prefs.themeMode) {
                ThemeManager.MODE_LIGHT -> R.id.chip_theme_light
                ThemeManager.MODE_DARK -> R.id.chip_theme_dark
                else -> R.id.chip_theme_system
            }
        )
        group.setOnCheckedStateChangeListener { _, ids ->
            val mode = when (ids.firstOrNull()) {
                R.id.chip_theme_light -> ThemeManager.MODE_LIGHT
                R.id.chip_theme_dark -> ThemeManager.MODE_DARK
                else -> ThemeManager.MODE_SYSTEM
            }
            ThemeManager.setMode(mode)
        }

        view.findViewById<TextView>(R.id.rom_info).text = RomUtils.romName()
        view.findViewById<TextView>(R.id.about_version).text =
            getString(R.string.version_value, "1.0.0")

        // 折叠形态变化 → 重排网格
        FoldableUtil.observeFolding(requireActivity(), viewLifecycleOwner.lifecycleScope) {
            val newCols = FoldableUtil.homeColumns(requireContext())
            if (newCols != columns) buildModes()
        }
    }

    private fun buildModes() {
        columns = FoldableUtil.homeColumns(requireContext())
        modeContainer.removeAllViews()
        val grid = GridLayout(requireContext()).apply {
            columnCount = columns
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        val modes = listOf(
            CaptureMode.FULL to Triple(R.string.mode_full, R.string.mode_full_desc, R.drawable.ic_mode_full),
            CaptureMode.APP to Triple(R.string.mode_app, R.string.mode_app_desc, R.drawable.ic_mode_app),
            CaptureMode.REGION to Triple(R.string.mode_region, R.string.mode_region_desc, R.drawable.ic_mode_region),
            CaptureMode.DELAY to Triple(R.string.mode_delay, R.string.mode_delay_desc, R.drawable.ic_mode_delay),
            CaptureMode.LONG to Triple(R.string.mode_long, R.string.mode_long_desc, R.drawable.ic_mode_long)
        )
        for ((mode, info) in modes) {
            val row = layoutInflater.inflate(R.layout.item_mode, grid, false)
            row.findViewById<ImageView>(R.id.mode_icon).setImageResource(info.third)
            row.findViewById<TextView>(R.id.mode_title).setText(info.first)
            row.findViewById<TextView>(R.id.mode_desc).setText(info.second)
            (row as MaterialCardView).setOnClickListener {
                if (mode == CaptureMode.APP) {
                    Dialogs.appModeNotice(requireContext()) { startFromApp(mode) }
                } else {
                    startFromApp(mode)
                }
            }
            val lp = GridLayout.LayoutParams().apply {
                width = 0
                height = ViewGroup.LayoutParams.WRAP_CONTENT
                columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                setMargins(0, 0, if (columns > 1) dp(8) else 0, dp(10))
            }
            grid.addView(row, lp)
        }
        modeContainer.addView(grid)
    }

    /**
     * 从应用自身界面发起截图：
     * - 授权路径：CaptureManager 会立即拉起授权页（此时应用仍在前景，规避后台启动限制），
     *   随后本应用退到后台，授权完成后露出上一层界面再捕获；
     * - 极速路径：CaptureManager 内部延迟到本应用退场后才真正捕获。
     */
    private fun startFromApp(mode: CaptureMode) {
        val ctx = requireContext().applicationContext
        if (mode == CaptureMode.LONG) {
            CaptureManager.request(ctx, mode)
            return
        }
        CaptureManager.request(ctx, mode, CaptureManager.UI_DISMISS_SETTLE_MS)
        // 延迟截图且无悬浮窗时保持前台：授权已在前景发起，倒计时期间用户可自行切换界面；
        // 若此时退后台，后续拉起会被系统限制
        if (mode != CaptureMode.DELAY || android.provider.Settings.canDrawOverlays(ctx)) {
            requireActivity().moveTaskToBack(true)
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
