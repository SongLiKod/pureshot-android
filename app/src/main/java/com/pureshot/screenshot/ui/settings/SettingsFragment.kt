package com.pureshot.screenshot.ui.settings

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.pureshot.screenshot.R
import com.pureshot.screenshot.core.Prefs
import com.pureshot.screenshot.core.export.ExportManager
import com.pureshot.screenshot.core.longshot.LongShotAccessibilityService
import com.pureshot.screenshot.core.util.ErrorReporter
import com.pureshot.screenshot.core.util.PermissionUtil
import com.pureshot.screenshot.core.util.RomUtils
import com.pureshot.screenshot.ui.floating.FloatingBallService

/**
 * 设置中心（Issue18）：截图设置 / 编辑设置 / 导出设置 / 权限管理 四大板块，
 * 全部本地持久化，默认参数最优适配。
 */
class SettingsFragment : Fragment() {

    private lateinit var container: LinearLayout

    private val requestMedia = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
    private val requestNotif = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
    private val pickTree = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            try {
                requireContext().contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
                Prefs.saveTreeUri = uri.toString()
            } catch (e: Throwable) {
                ErrorReporter.dialog(requireContext(), e)
            }
        }
        refresh()
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View =
        inflater.inflate(R.layout.fragment_settings, container, false)

    override fun onViewCreated(view: View, s: Bundle?) {
        container = view.findViewById(R.id.settings_container)
        build()
    }

    override fun onResume() {
        super.onResume()
        container.removeAllViews()
        build()
    }

    private fun brand(): Int = androidx.core.content.ContextCompat.getColor(requireContext(), R.color.brand_primary)

    private fun muted(): Int = com.google.android.material.color.MaterialColors.getColor(
        container, com.google.android.material.R.attr.colorOnSurfaceVariant
    )

    private fun onSurface(): Int = com.google.android.material.color.MaterialColors.getColor(
        container, com.google.android.material.R.attr.colorOnSurface
    )

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun build() {
        sectionTitle(getString(R.string.set_capture))
        clickRow(getString(R.string.delay_title), getString(R.string.delay_fmt, Prefs.delaySeconds)) {
            Dialogs.delayPicker(requireContext()) { refresh() }
        }
        val romState = if (Prefs.romEnhanced) getString(R.string.perm_granted) else getString(R.string.perm_denied)
        clickRow(
            getString(R.string.rom_switch_title),
            getString(R.string.rom_switch_desc) + "\n" + getString(
                if (RomUtils.isEnhanceSupported) R.string.rom_supported else R.string.rom_unsupported,
                RomUtils.romName()
            ) + "\n" + romState
        ) {
            if (Prefs.romEnhanced) {
                Prefs.romEnhanced = false
                refresh()
            } else {
                Dialogs.romNotice(requireContext()) {
                    Prefs.romEnhanced = true
                    refresh()
                }
            }
        }
        val accOn = Prefs.accAutoScroll && PermissionUtil.isAccessibilityEnabled(requireContext())
        clickRow(
            getString(R.string.acc_switch_title),
            getString(R.string.acc_switch_desc) + "\n" +
                if (accOn) getString(R.string.perm_granted) else getString(R.string.perm_denied)
        ) {
            if (accOn) {
                Prefs.accAutoScroll = false
                LongShotAccessibilityService.instance?.stopAutoScroll()
                refresh()
            } else {
                // Issue7：启用前展示权限说明与风险提示
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle(R.string.acc_risk_title)
                    .setMessage(R.string.acc_risk_msg)
                    .setPositiveButton(R.string.acc_go_settings) { _, _ ->
                        Prefs.accAutoScroll = true
                        try {
                            startActivity(PermissionUtil.accessibilityIntent())
                        } catch (e: Throwable) { /* 容错 */ }
                        refresh()
                    }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
            }
        }
        clickRow(
            getString(R.string.ball_switch_title),
            getString(R.string.ball_switch_desc) + "\n" +
                if (Prefs.floatingBall) getString(R.string.perm_granted) else getString(R.string.perm_denied)
        ) {
            if (Prefs.floatingBall) {
                Prefs.floatingBall = false
                FloatingBallService.stop(requireContext())
            } else if (!PermissionUtil.hasOverlay(requireContext())) {
                try {
                    startActivity(PermissionUtil.overlayIntent(requireContext()))
                } catch (e: Throwable) { /* 容错 */ }
            } else {
                Prefs.floatingBall = true
                FloatingBallService.ensureRunning(requireContext())
            }
            refresh()
        }
        switchRow(getString(R.string.preview_switch_title), getString(R.string.preview_switch_desc), Prefs.floatingPreview) {
            Prefs.floatingPreview = it
        }
        clickRow(getString(R.string.battery_title), getString(R.string.battery_desc)) {
            try {
                val i = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
                    Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                else Intent(Settings.ACTION_SETTINGS)
                startActivity(i)
            } catch (e: Throwable) {
                ErrorReporter.dialog(requireContext(), e)
            }
        }

        sectionTitle(getString(R.string.set_edit))
        clickRow(getString(R.string.mosaic_size_title), sizeName(Prefs.mosaicSize)) {
            singleChoice(
                arrayOf(getString(R.string.size_small), getString(R.string.size_medium), getString(R.string.size_large)),
                Prefs.mosaicSize
            ) { Prefs.mosaicSize = it; refresh() }
        }
        clickRow(getString(R.string.blur_strength_title), blurName(Prefs.blurStrength)) {
            singleChoice(
                arrayOf(getString(R.string.blur_low), getString(R.string.blur_mid), getString(R.string.blur_high)),
                Prefs.blurStrength
            ) { Prefs.blurStrength = it; refresh() }
        }
        switchRow(getString(R.string.remember_switch_title), getString(R.string.remember_switch_desc), Prefs.rememberParams) {
            Prefs.rememberParams = it; true
        }

        sectionTitle(getString(R.string.set_export))
        clickRow(getString(R.string.default_format_title), Prefs.defaultFormat.uppercase()) {
            val fmts = ExportManager.FORMATS.toTypedArray()
            singleChoice(fmts.map { it.uppercase() }.toTypedArray(), fmts.indexOf(Prefs.defaultFormat).coerceAtLeast(0)) {
                Prefs.defaultFormat = fmts[it]
                refresh()
            }
        }
        clickRow(getString(R.string.jpg_quality_title), "${Prefs.jpgQuality}%") {
            singleChoice(arrayOf("60%", "80%", "100%"), when (Prefs.jpgQuality) { 60 -> 0; 100 -> 2; else -> 1 }) {
                Prefs.jpgQuality = intArrayOf(60, 80, 100)[it]
                refresh()
            }
        }
        clickRow(getString(R.string.webp_quality_title), "${Prefs.webpQuality}%") {
            singleChoice(arrayOf("60%", "80%", "90%", "100%"), when (Prefs.webpQuality) { 60 -> 0; 80 -> 1; 100 -> 3; else -> 2 }) {
                Prefs.webpQuality = intArrayOf(60, 80, 90, 100)[it]
                refresh()
            }
        }
        clickRow(getString(R.string.export_scale), "${Prefs.exportScale}%") {
            singleChoice(arrayOf("50%", "75%", "100%"), when (Prefs.exportScale) { 50 -> 0; 75 -> 1; else -> 2 }) {
                Prefs.exportScale = intArrayOf(50, 75, 100)[it]
                refresh()
            }
        }
        clickRow(getString(R.string.save_dir_title), saveDirDesc()) {
            try {
                pickTree.launch(null)
            } catch (e: Throwable) {
                ErrorReporter.dialog(requireContext(), e)
            }
        }
        clickRow(getString(R.string.name_rule_title), Prefs.nameTemplate) {
            val input = EditText(requireContext()).apply {
                setText(Prefs.nameTemplate)
                hint = getString(R.string.name_rule_hint)
            }
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.name_rule_title)
                .setMessage(R.string.name_rule_hint)
                .setView(input)
                .setPositiveButton(R.string.ok) { _, _ ->
                    val v = input.text.toString().trim()
                    if (v.isNotBlank()) { Prefs.nameTemplate = v; refresh() }
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
        switchRow(getString(R.string.album_switch_title), getString(R.string.album_switch_desc), Prefs.showInGallery) {
            Prefs.showInGallery = it; true
        }
        switchRow(getString(R.string.orig_switch_title), getString(R.string.orig_switch_desc), Prefs.autoSaveOriginal) {
            Prefs.autoSaveOriginal = it; true
        }

        sectionTitle(getString(R.string.set_perm))
        permRow(getString(R.string.perm_media), getString(R.string.perm_media_desc), PermissionUtil.hasMediaRead(requireContext())) {
            val perm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                android.Manifest.permission.READ_MEDIA_IMAGES
            else android.Manifest.permission.READ_EXTERNAL_STORAGE
            requestMedia.launch(perm)
        }
        permRow(getString(R.string.perm_notif), getString(R.string.perm_notif_desc), PermissionUtil.hasNotification(requireContext())) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) requestNotif.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
        permRow(getString(R.string.perm_overlay), getString(R.string.perm_overlay_desc), PermissionUtil.hasOverlay(requireContext())) {
            try { startActivity(PermissionUtil.overlayIntent(requireContext())) } catch (e: Throwable) {}
        }
        permRow(
            getString(R.string.perm_acc), getString(R.string.perm_acc_desc),
            PermissionUtil.isAccessibilityEnabled(requireContext())
        ) {
            try { startActivity(PermissionUtil.accessibilityIntent()) } catch (e: Throwable) {}
        }
        permRow(getString(R.string.perm_projection), getString(R.string.perm_projection_desc), null) { }
    }

    private fun saveDirDesc(): String =
        if (Prefs.saveTreeUri.isNotBlank()) getString(R.string.save_dir_custom, Uri.parse(Prefs.saveTreeUri).lastPathSegment ?: "")
        else getString(R.string.save_dir_desc)

    private fun sizeName(i: Int) = getString(
        when (i) { 0 -> R.string.size_small; 2 -> R.string.size_large; else -> R.string.size_medium }
    )

    private fun blurName(i: Int) = getString(
        when (i) { 0 -> R.string.blur_low; 2 -> R.string.blur_high; else -> R.string.blur_mid }
    )

    private fun refresh() {
        container.removeAllViews()
        build()
    }

    // ---------- 行构建 ----------

    private fun sectionTitle(text: String) {
        container.addView(TextView(requireContext()).apply {
            this.text = text
            setTextColor(brand())
            textSize = 14f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, dp(20), 0, dp(8))
        })
    }

    private fun baseRow(): LinearLayout = LinearLayout(requireContext()).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, dp(12), 0, dp(12))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    private fun titleBlock(title: String, desc: String?): LinearLayout {
        return LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            addView(TextView(requireContext()).apply {
                text = title; textSize = 15f; setTextColor(onSurface())
            })
            if (!desc.isNullOrBlank()) addView(TextView(requireContext()).apply {
                text = desc; textSize = 12f; setTextColor(muted()); setPadding(0, dp(2), 0, 0)
            })
        }
    }

    private fun switchRow(title: String, desc: String, checked: Boolean, onChange: (Boolean) -> Unit) {
        val row = baseRow()
        row.addView(titleBlock(title, desc))
        row.addView(MaterialSwitch(requireContext()).apply {
            isChecked = checked
            setOnCheckedChangeListener { _, c -> onChange(c) }
        })
        container.addView(row)
    }

    private fun clickRow(title: String, desc: String, onClick: () -> Unit) {
        val row = baseRow()
        row.addView(titleBlock(title, desc))
        row.addView(TextView(requireContext()).apply {
            text = ">"; textSize = 18f; setTextColor(muted())
        })
        row.setOnClickListener { onClick() }
        container.addView(row)
    }

    private fun permRow(title: String, desc: String, granted: Boolean?, onGo: () -> Unit) {
        val row = baseRow()
        row.addView(titleBlock(title, desc))
        val status = TextView(requireContext()).apply {
            text = when (granted) {
                true -> getString(R.string.perm_granted)
                false -> getString(R.string.perm_denied)
                else -> getString(R.string.perm_none)
            }
            textSize = 12f
            setTextColor(if (granted == true) brand() else muted())
            setPadding(dp(8), 0, dp(8), 0)
        }
        row.addView(status)
        if (granted == false) {
            row.addView(TextView(requireContext()).apply {
                text = getString(R.string.perm_go)
                textSize = 13f
                setTextColor(brand())
                setPadding(dp(6), 0, dp(6), 0)
            })
        }
        row.setOnClickListener { onGo() }
        container.addView(row)
    }

    private fun singleChoice(items: Array<String>, checked: Int, onPick: (Int) -> Unit) {
        MaterialAlertDialogBuilder(requireContext())
            .setSingleChoiceItems(items, checked) { dialog, which ->
                onPick(which)
                dialog.dismiss()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }
}
