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
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.pureshot.screenshot.R
import com.pureshot.screenshot.core.Prefs
import com.pureshot.screenshot.core.export.ExportManager
import com.pureshot.screenshot.core.longshot.LongShotAccessibilityService
import com.pureshot.screenshot.core.theme.ThemeManager
import com.pureshot.screenshot.core.update.UpdateManager
import com.pureshot.screenshot.core.util.ErrorReporter
import com.pureshot.screenshot.core.util.PermissionUtil
import com.pureshot.screenshot.core.util.RomUtils
import com.pureshot.screenshot.ui.floating.FloatingBallService
import java.io.File
import java.util.Locale

/**
 * 设置中心（Issue18）：主题 / 截图设置 / 编辑设置 / 导出设置 / 权限管理 / 关于，
 * 全部本地持久化，默认参数最优适配。视觉参照 screen-pulse：分区标题 + 圆角卡片分组。
 */
class SettingsFragment : Fragment() {

    private lateinit var container: LinearLayout
    private var currentCard: LinearLayout? = null
    private var rowsInCard = 0

    // 版本更新状态
    private var updateChecking = false
    private var updateDownloading = false
    private var pendingApk: File? = null

    @Volatile
    private var downloadCancelled = false

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
        refresh()
        // 从「允许安装未知应用」授权页返回后，自动继续安装待安装的更新包
        val apk = pendingApk
        if (apk != null && apk.exists() && isAdded && UpdateManager.canRequestInstall(requireContext())) {
            pendingApk = null
            UpdateManager.install(requireContext(), apk)
        }
    }

    private fun brand(): Int = ContextCompat.getColor(requireContext(), R.color.brand_primary)

    private fun muted(): Int = com.google.android.material.color.MaterialColors.getColor(
        container, com.google.android.material.R.attr.colorOnSurfaceVariant
    )

    private fun onSurface(): Int = com.google.android.material.color.MaterialColors.getColor(
        container, com.google.android.material.R.attr.colorOnSurface
    )

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun build() {
        // ---------- 主题模式 ----------
        sectionTitle(getString(R.string.theme_title))
        card {
            radioRow(getString(R.string.theme_system), Prefs.themeMode == ThemeManager.MODE_SYSTEM) {
                ThemeManager.setMode(ThemeManager.MODE_SYSTEM)
                refresh()
            }
            radioRow(getString(R.string.theme_light), Prefs.themeMode == ThemeManager.MODE_LIGHT) {
                ThemeManager.setMode(ThemeManager.MODE_LIGHT)
                refresh()
            }
            radioRow(getString(R.string.theme_dark), Prefs.themeMode == ThemeManager.MODE_DARK) {
                ThemeManager.setMode(ThemeManager.MODE_DARK)
                refresh()
            }
        }

        // ---------- 截图设置 ----------
        sectionTitle(getString(R.string.set_capture))
        card {
            valueRow(getString(R.string.delay_title), getString(R.string.delay_fmt, Prefs.delaySeconds)) {
                Dialogs.delayPicker(requireContext()) { refresh() }
            }
            valueRow(
                getString(R.string.keep_alive_title),
                getString(R.string.keep_alive_fmt, Prefs.keepAliveMinutes),
                getString(R.string.keep_alive_value_fmt, Prefs.keepAliveMinutes)
            ) {
                val options = intArrayOf(1, 3, 5, 10, 15, 30)
                singleChoice(
                    options.map { "${it} 分钟" }.toTypedArray(),
                    options.indexOf(Prefs.keepAliveMinutes).coerceAtLeast(0)
                ) {
                    Prefs.keepAliveMinutes = options[it]
                    refresh()
                }
            }
            valueRow(
                getString(R.string.rom_switch_title),
                getString(
                    if (RomUtils.isEnhanceSupported) R.string.rom_supported else R.string.rom_unsupported,
                    RomUtils.romName()
                ),
                if (Prefs.romEnhanced) getString(R.string.perm_granted) else getString(R.string.perm_denied)
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
            val accEnabled = PermissionUtil.isAccessibilityEnabled(requireContext())
            val accOn = Prefs.accAutoScroll && accEnabled
            valueRow(
                getString(R.string.acc_switch_title),
                getString(R.string.acc_switch_desc),
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
            switchRow(getString(R.string.ball_switch_title), getString(R.string.ball_switch_desc), Prefs.floatingBall) { checked ->
                if (checked) {
                    if (!PermissionUtil.hasOverlay(requireContext())) {
                        Prefs.floatingBall = false
                        try {
                            startActivity(PermissionUtil.overlayIntent(requireContext()))
                        } catch (e: Throwable) { /* 容错 */ }
                    } else {
                        Prefs.floatingBall = true
                        FloatingBallService.ensureRunning(requireContext())
                    }
                } else {
                    Prefs.floatingBall = false
                    FloatingBallService.stop(requireContext())
                }
                refresh()
            }
            switchRow(getString(R.string.preview_switch_title), getString(R.string.preview_switch_desc), Prefs.floatingPreview) {
                Prefs.floatingPreview = it
            }
            valueRow(getString(R.string.battery_title), getString(R.string.battery_desc)) {
                try {
                    val i = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                    startActivity(i)
                } catch (e: Throwable) {
                    ErrorReporter.dialog(requireContext(), e)
                }
            }
        }

        // ---------- 编辑设置 ----------
        sectionTitle(getString(R.string.set_edit))
        card {
            valueRow(getString(R.string.mosaic_size_title), sizeName(Prefs.mosaicSize)) {
                singleChoice(
                    arrayOf(getString(R.string.size_small), getString(R.string.size_medium), getString(R.string.size_large)),
                    Prefs.mosaicSize
                ) { Prefs.mosaicSize = it; refresh() }
            }
            valueRow(getString(R.string.blur_strength_title), blurName(Prefs.blurStrength)) {
                singleChoice(
                    arrayOf(getString(R.string.blur_low), getString(R.string.blur_mid), getString(R.string.blur_high)),
                    Prefs.blurStrength
                ) { Prefs.blurStrength = it; refresh() }
            }
            switchRow(getString(R.string.remember_switch_title), getString(R.string.remember_switch_desc), Prefs.rememberParams) {
                Prefs.rememberParams = it
            }
        }

        // ---------- 导出设置 ----------
        sectionTitle(getString(R.string.set_export))
        card {
            valueRow(getString(R.string.default_format_title), Prefs.defaultFormat.uppercase()) {
                val fmts = ExportManager.FORMATS.toTypedArray()
                singleChoice(fmts.map { it.uppercase() }.toTypedArray(), fmts.indexOf(Prefs.defaultFormat).coerceAtLeast(0)) {
                    Prefs.defaultFormat = fmts[it]
                    refresh()
                }
            }
            valueRow(getString(R.string.jpg_quality_title), "${Prefs.jpgQuality}%") {
                singleChoice(arrayOf("60%", "80%", "100%"), when (Prefs.jpgQuality) { 60 -> 0; 100 -> 2; else -> 1 }) {
                    Prefs.jpgQuality = intArrayOf(60, 80, 100)[it]
                    refresh()
                }
            }
            valueRow(getString(R.string.webp_quality_title), "${Prefs.webpQuality}%") {
                singleChoice(arrayOf("60%", "80%", "90%", "100%"), when (Prefs.webpQuality) { 60 -> 0; 80 -> 1; 100 -> 3; else -> 2 }) {
                    Prefs.webpQuality = intArrayOf(60, 80, 90, 100)[it]
                    refresh()
                }
            }
            valueRow(getString(R.string.export_scale), "${Prefs.exportScale}%") {
                singleChoice(arrayOf("50%", "75%", "100%"), when (Prefs.exportScale) { 50 -> 0; 75 -> 1; else -> 2 }) {
                    Prefs.exportScale = intArrayOf(50, 75, 100)[it]
                    refresh()
                }
            }
            valueRow(getString(R.string.save_dir_title), saveDirDesc()) {
                try {
                    pickTree.launch(null)
                } catch (e: Throwable) {
                    ErrorReporter.dialog(requireContext(), e)
                }
            }
            valueRow(getString(R.string.name_rule_title), Prefs.nameTemplate) {
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
                Prefs.showInGallery = it
            }
            switchRow(getString(R.string.orig_switch_title), getString(R.string.orig_switch_desc), Prefs.autoSaveOriginal) {
                Prefs.autoSaveOriginal = it
            }
        }

        // ---------- 权限管理 ----------
        sectionTitle(getString(R.string.set_perm))
        card {
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

        // ---------- 关于 ----------
        sectionTitle(getString(R.string.about_section))
        card {
            aboutCard()
            updateRow()
        }
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

    // ---------- 结构构建 ----------

    private fun sectionTitle(text: String) {
        container.addView(TextView(requireContext()).apply {
            this.text = text
            setTextColor(brand())
            textSize = 16f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(dp(4), dp(24), 0, dp(8))
        })
    }

    private fun card(block: () -> Unit) {
        val cardView = layoutInflater.inflate(R.layout.item_settings_card, container, false)
        container.addView(cardView)
        currentCard = cardView.findViewById(R.id.settings_card_inner)
        rowsInCard = 0
        block()
        currentCard = null
    }

    private fun addRow(row: View) {
        val card = currentCard ?: return
        if (rowsInCard > 0) card.addView(View(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1)
            ).apply { marginStart = dp(16); marginEnd = dp(16) }
            setBackgroundColor(ContextCompat.getColor(requireContext(), R.color.app_divider))
        })
        card.addView(row)
        rowsInCard++
    }

    private fun baseRow(): LinearLayout = LinearLayout(requireContext()).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(16), dp(14), dp(16), dp(14))
        minimumHeight = dp(56)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    private fun clickableRow(): LinearLayout = baseRow().apply {
        background = selectableBg()
        isClickable = true
        isFocusable = true
    }

    private fun selectableBg(): android.graphics.drawable.Drawable? {
        val tv = android.util.TypedValue()
        requireContext().theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)
        return ContextCompat.getDrawable(requireContext(), tv.resourceId)
    }

    /** 左侧标题 + 可选说明的纵向文本块 */
    private fun titleBlock(title: String, desc: String?): LinearLayout =
        LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            addView(TextView(requireContext()).apply {
                text = title
                textSize = 15f
                setTextColor(onSurface())
            })
            if (!desc.isNullOrBlank()) addView(TextView(requireContext()).apply {
                text = desc
                textSize = 12f
                setTextColor(muted())
                setPadding(0, dp(3), 0, 0)
            })
        }

    private fun chevron(): ImageView = ImageView(requireContext()).apply {
        setImageResource(R.drawable.ic_chevron_right)
        imageTintList = android.content.res.ColorStateList.valueOf(muted())
        layoutParams = LinearLayout.LayoutParams(dp(18), dp(18)).apply { marginStart = dp(6) }
        contentDescription = null
    }

    /** 单选项行（主题模式） */
    private fun radioRow(label: String, checked: Boolean, onPick: () -> Unit) {
        val row = clickableRow()
        row.addView(com.google.android.material.radiobutton.MaterialRadioButton(requireContext()).apply {
            isChecked = checked
            isClickable = false
            buttonTintList = android.content.res.ColorStateList.valueOf(brand())
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = dp(8) }
        })
        row.addView(TextView(requireContext()).apply {
            text = label
            textSize = 15f
            setTextColor(onSurface())
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        row.setOnClickListener { onPick() }
        addRow(row)
    }

    /** 开关行：标题 + 说明 + 右侧 MaterialSwitch */
    private fun switchRow(title: String, desc: String, checked: Boolean, onChange: (Boolean) -> Unit) {
        val row = baseRow()
        row.addView(titleBlock(title, desc))
        row.addView(MaterialSwitch(requireContext()).apply {
            isChecked = checked
            setOnCheckedChangeListener { _, c -> onChange(c) }
        })
        addRow(row)
    }

    /** 取值行：标题 + 可选说明 + 右侧主色值 + 箭头 */
    private fun valueRow(title: String, desc: String?, value: String, onClick: () -> Unit) {
        val row = clickableRow()
        row.addView(titleBlock(title, desc))
        row.addView(TextView(requireContext()).apply {
            text = value
            textSize = 13f
            setTextColor(brand())
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            // 限制右侧取值宽度，避免过长取值挤压/遮挡左侧标题
            maxWidth = (resources.displayMetrics.widthPixels * 0.5f).toInt()
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = dp(12) }
        })
        row.addView(chevron())
        row.setOnClickListener { onClick() }
        addRow(row)
    }

    private fun valueRow(title: String, value: String, onClick: () -> Unit) = valueRow(title, null, value, onClick)

    /** 权限行：标题 + 说明 + 右侧状态（未授权显示「去开启」） */
    private fun permRow(title: String, desc: String, granted: Boolean?, onGo: () -> Unit) {
        val row = baseRow()
        row.addView(titleBlock(title, desc))
        row.addView(TextView(requireContext()).apply {
            text = when (granted) {
                true -> getString(R.string.perm_granted)
                false -> getString(R.string.perm_go)
                else -> getString(R.string.perm_none)
            }
            textSize = 13f
            setTextColor(if (granted == false) brand() else muted())
            setTypeface(typeface, if (granted == false) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
        })
        if (granted == false) {
            row.makeClickable { onGo() }
        }
        addRow(row)
    }

    /** 关于卡片内容：应用名 / 版本 / 设备 ROM / 说明 */
    private fun aboutCard() {
        val body = currentCard ?: return
        body.addView(LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
            addView(TextView(requireContext()).apply {
                text = getString(R.string.app_name)
                textSize = 16f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(onSurface())
            })
            addView(TextView(requireContext()).apply {
                text = getString(R.string.version_value, UpdateManager.currentVersion())
                textSize = 13f
                setTextColor(brand())
                setPadding(0, dp(4), 0, 0)
            })
            addView(TextView(requireContext()).apply {
                text = getString(R.string.about_disclaimer)
                textSize = 13f
                setTextColor(muted())
                setLineSpacing(dp(4).toFloat(), 1f)
                setPadding(0, dp(12), 0, 0)
            })
            addView(TextView(requireContext()).apply {
                text = getString(R.string.about_device_label) + "：" + RomUtils.romName()
                textSize = 13f
                setTextColor(muted())
                setPadding(0, dp(12), 0, 0)
            })
        })
        rowsInCard++
    }

    // ---------- 版本更新（关于模块，下载与安装全程在应用内） ----------

    /** 关于卡片内的「检查更新」入口行 */
    private fun updateRow() {
        val row = clickableRow()
        row.addView(titleBlock(getString(R.string.update_check_title), getString(R.string.update_check_desc)))
        row.addView(TextView(requireContext()).apply {
            text = getString(R.string.version_value, UpdateManager.currentVersion())
            textSize = 13f
            setTextColor(brand())
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = dp(12) }
        })
        row.addView(chevron())
        row.setOnClickListener { checkUpdate() }
        addRow(row)
    }

    private fun checkUpdate() {
        if (updateChecking || updateDownloading) return
        val ctx = requireContext()
        if (!UpdateManager.isOnline(ctx)) {
            ErrorReporter.toast(ctx, getString(R.string.update_no_network))
            return
        }
        updateChecking = true
        val checkingBox = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(0, dp(16), 0, 0)
            addView(ProgressBar(ctx).apply {
                isIndeterminate = true
                layoutParams = LinearLayout.LayoutParams(dp(40), dp(40))
            })
        }
        val dialog = MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.update_checking)
            .setView(checkingBox)
            .setNegativeButton(R.string.cancel, null)
            .create()
        dialog.show()

        UpdateManager.check(ctx) { result ->
            updateChecking = false
            if (!isAdded) return@check
            if (dialog.isShowing) dialog.dismiss()
            result.onSuccess { info ->
                if (info == null) {
                    MaterialAlertDialogBuilder(requireContext())
                        .setTitle(R.string.update_latest)
                        .setMessage(getString(R.string.update_latest_msg, UpdateManager.currentVersion()))
                        .setPositiveButton(R.string.ok, null)
                        .show()
                } else {
                    showUpdateFound(info)
                }
            }.onFailure {
                ErrorReporter.dialog(requireContext(), it)
            }
        }
    }

    private fun showUpdateFound(info: UpdateManager.UpdateInfo) {
        val notes = info.notes.ifBlank { getString(R.string.update_no_notes) }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.update_found_title, info.version))
            .setMessage(getString(R.string.update_notes_fmt, notes))
            .setPositiveButton(R.string.update_now) { _, _ -> startDownload(info) }
            .setNegativeButton(R.string.update_later, null)
            .show()
    }

    private fun startDownload(info: UpdateManager.UpdateInfo) {
        if (updateDownloading) return
        updateDownloading = true
        downloadCancelled = false
        val ctx = requireContext()

        val bar = ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            isIndeterminate = false
        }
        val progressText = TextView(ctx).apply {
            text = getString(R.string.update_download_start)
            textSize = 13f
            setTextColor(muted())
            setPadding(0, dp(8), 0, 0)
        }
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(16), dp(24), 0)
            addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(progressText)
        }

        val dialog = MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.update_downloading)
            .setView(box)
            .setNegativeButton(R.string.cancel) { _, _ -> downloadCancelled = true }
            .setCancelable(false)
            .create()
        dialog.show()

        UpdateManager.download(
            ctx, info,
            onProgress = { downloaded, total ->
                if (!isAdded || !dialog.isShowing) return@download
                val pct = if (total > 0) ((downloaded * 100) / total).toInt().coerceIn(0, 100) else 0
                bar.progress = pct
                progressText.text = getString(
                    R.string.update_download_progress, pct, bytes(downloaded), bytes(total)
                )
            },
            isCancelled = { downloadCancelled },
            onDone = { apk ->
                updateDownloading = false
                if (dialog.isShowing) dialog.dismiss()
                if (isAdded) afterDownloaded(apk)
            },
            onCancelled = {
                updateDownloading = false
                if (dialog.isShowing) dialog.dismiss()
                if (isAdded) ErrorReporter.toast(requireContext(), getString(R.string.update_cancelled))
            },
            onError = { e ->
                updateDownloading = false
                if (dialog.isShowing) dialog.dismiss()
                if (isAdded) ErrorReporter.dialog(requireContext(), e)
            })
    }

    /** 下载完成后在应用内直接调起安装；缺少安装权限时引导授权，返回后自动续接。 */
    private fun afterDownloaded(apk: File) {
        val ctx = requireContext()
        if (UpdateManager.canRequestInstall(ctx)) {
            UpdateManager.install(ctx, apk)
        } else {
            pendingApk = apk
            MaterialAlertDialogBuilder(ctx)
                .setTitle(R.string.update_install_perm_title)
                .setMessage(R.string.update_install_perm_msg)
                .setPositiveButton(R.string.update_go_grant) { _, _ ->
                    try {
                        startActivity(UpdateManager.installPermissionIntent(ctx))
                    } catch (e: Throwable) {
                        ErrorReporter.dialog(ctx, e)
                    }
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
    }

    private fun bytes(v: Long): String {
        if (v <= 0L) return "0 B"
        val kb = 1024.0
        val mb = kb * 1024
        return when {
            v >= mb -> String.format(Locale.US, "%.1f MB", v / mb)
            v >= kb -> String.format(Locale.US, "%.0f KB", v / kb)
            else -> "$v B"
        }
    }

    private fun LinearLayout.makeClickable(action: () -> Unit) {
        isClickable = true
        isFocusable = true
        setOnClickListener { action() }
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
