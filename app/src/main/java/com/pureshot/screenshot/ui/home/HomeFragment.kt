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
import com.google.android.material.materialswitch.MaterialSwitch
import com.pureshot.screenshot.R
import com.pureshot.screenshot.core.Prefs
import com.pureshot.screenshot.core.capture.CaptureManager
import com.pureshot.screenshot.core.capture.CaptureMode
import com.pureshot.screenshot.core.util.FoldableUtil
import com.pureshot.screenshot.core.util.PermissionUtil
import com.pureshot.screenshot.core.util.RomUtils
import com.pureshot.screenshot.ui.floating.FloatingBallService
import com.pureshot.screenshot.ui.settings.Dialogs

/**
 * 首页：五大截图模式入口 + 悬浮球快捷开关。
 * 折叠屏适配：展开态双列网格，半开/折叠切换实时重排。
 */
class HomeFragment : Fragment() {

    private lateinit var modeContainer: LinearLayout
    private var ballSwitch: MaterialSwitch? = null
    private var columns = 1

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View =
        inflater.inflate(R.layout.fragment_home, container, false)

    override fun onViewCreated(view: View, s: Bundle?) {
        modeContainer = view.findViewById(R.id.mode_container)
        buildModes()
        buildShortcuts(view)

        // 折叠形态变化 → 重排网格
        FoldableUtil.observeFolding(requireActivity(), viewLifecycleOwner.lifecycleScope) {
            val newCols = FoldableUtil.homeColumns(requireContext())
            if (newCols != columns) buildModes()
        }
    }

    override fun onResume() {
        super.onResume()
        syncBallSwitch()
    }

    private fun buildShortcuts(view: View) {
        ballSwitch = view.findViewById<MaterialSwitch>(R.id.switch_floating_ball).apply {
            setOnCheckedChangeListener { _, checked -> onBallToggled(checked) }
        }
        syncBallSwitch()
    }

    private fun syncBallSwitch() {
        ballSwitch?.isChecked = Prefs.floatingBall && PermissionUtil.hasOverlay(requireContext())
    }

    private fun onBallToggled(checked: Boolean) {
        when {
            !checked -> {
                Prefs.floatingBall = false
                FloatingBallService.stop(requireContext())
            }
            !PermissionUtil.hasOverlay(requireContext()) -> {
                Prefs.floatingBall = false
                syncBallSwitch()
                try {
                    startActivity(PermissionUtil.overlayIntent(requireContext()))
                } catch (e: Throwable) { /* 容错 */ }
            }
            else -> {
                Prefs.floatingBall = true
                FloatingBallService.ensureRunning(requireContext())
            }
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
     * - request 返回 true（进入授权流程）：保持前台，授权完成后由授权页自行退后台；
     * - 返回 false（直接进入捕获流程）：立即退后台，露出上一层界面被截入画面。
     */
    private fun startFromApp(mode: CaptureMode) {
        val ctx = requireContext().applicationContext
        val needConsent = CaptureManager.request(ctx, mode, CaptureManager.UI_DISMISS_SETTLE_MS)
        if (!needConsent) {
            requireActivity().moveTaskToBack(true)
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
