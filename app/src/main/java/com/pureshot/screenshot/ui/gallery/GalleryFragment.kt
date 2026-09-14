package com.pureshot.screenshot.ui.gallery

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.pureshot.screenshot.R
import com.pureshot.screenshot.core.export.ExportManager
import com.pureshot.screenshot.core.util.ErrorReporter
import com.pureshot.screenshot.core.util.FoldableUtil
import com.pureshot.screenshot.ui.preview.PreviewActivity
import kotlinx.coroutines.launch
import java.io.File

/**
 * 本地截图图库管理（Phase2）：浏览/多选/删除/分享，以及 GIF 多图合成动态演示图。
 * MVVM：数据查询/删除由 GalleryViewModel 后台协程调度，StateFlow 驱动列表。
 */
class GalleryFragment : Fragment() {

    private val items = mutableListOf<Shot>()
    private val selected = linkedSetOf<Shot>()
    private var selectMode = false
    private lateinit var recycler: RecyclerView
    private lateinit var adapter: Adapter
    private lateinit var title: TextView
    private lateinit var btnGif: ImageButton
    private lateinit var btnDelete: ImageButton
    private lateinit var btnDone: ImageButton

    private val vm: GalleryViewModel by viewModels()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View =
        inflater.inflate(R.layout.fragment_gallery, container, false)

    override fun onViewCreated(view: View, s: Bundle?) {
        recycler = view.findViewById(R.id.gallery_recycler)
        title = view.findViewById(R.id.gallery_title)
        btnGif = view.findViewById(R.id.btn_gif)
        btnDelete = view.findViewById(R.id.btn_delete_sel)
        btnDone = view.findViewById(R.id.btn_done_sel)
        layoutManager = GridLayoutManager(requireContext(), FoldableUtil.gallerySpan(requireContext()))
        recycler.layoutManager = layoutManager
        adapter = Adapter()
        recycler.adapter = adapter
        view.findViewById<ImageButton>(R.id.btn_select).setOnClickListener {
            selectMode = !selectMode
            if (!selectMode) selected.clear()
            updateToolbar()
        }
        btnDone.setOnClickListener {
            selectMode = false
            selected.clear()
            updateToolbar()
        }
        btnDelete.setOnClickListener { deleteSelected() }
        btnGif.setOnClickListener { composeGif() }
        updateToolbar()
        // StateFlow 驱动列表刷新
        viewLifecycleOwner.lifecycleScope.launch {
            vm.shots.collect { list ->
                items.clear()
                items.addAll(list)
                selected.retainAll(list.toSet())
                adapter.notifyDataSetChanged()
                val empty = view?.findViewById<TextView>(R.id.gallery_empty)
                empty?.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
                updateToolbar()
            }
        }
        // 折叠屏展开/折叠实时调整网格列数
        FoldableUtil.observeFolding(requireActivity(), viewLifecycleOwner.lifecycleScope) {
            layoutManager?.spanCount = FoldableUtil.gallerySpan(requireContext())
        }
    }

    private var layoutManager: GridLayoutManager? = null

    override fun onResume() {
        super.onResume()
        vm.reload(requireContext())
    }

    private fun updateToolbar() {
        btnDelete.visibility = if (selectMode && selected.isNotEmpty()) View.VISIBLE else View.GONE
        btnDone.visibility = if (selectMode) View.VISIBLE else View.GONE
        title.text = if (selectMode) getString(R.string.gallery_selected, selected.size) else getString(R.string.gallery_title)
    }

    private fun onClick(item: Shot) {
        if (selectMode) {
            if (selected.contains(item)) selected.remove(item) else selected.add(item)
            updateToolbar()
            adapter.notifyDataSetChanged()
        } else {
            val p = item.path ?: item.uri?.toString() ?: return
            startActivity(
                Intent(requireContext(), PreviewActivity::class.java)
                    .putExtra(PreviewActivity.EXTRA_PATH, p)
            )
        }
    }

    private fun deleteSelected() {
        if (selected.isEmpty()) return
        MaterialAlertDialogBuilder(requireContext())
            .setMessage(getString(R.string.gallery_delete_confirm, selected.size))
            .setPositiveButton(R.string.delete) { _, _ ->
                vm.delete(requireContext(), selected.toList())
                selected.clear()
                selectMode = false
                updateToolbar()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun composeGif() {
        val picked = if (selected.isNotEmpty()) selected.toList() else null
        if (picked == null || picked.size < 2) {
            Toast.makeText(requireContext(), R.string.gif_pick_hint, Toast.LENGTH_SHORT).show()
            return
        }
        var fps = 2
        var loop = 0
        val fpsInput = EditText(requireContext()).apply {
            hint = getString(R.string.gif_fps)
            setText("2")
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
        }
        val loopInput = EditText(requireContext()).apply {
            hint = getString(R.string.gif_loop)
            setText("0")
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.gif_title) + " · " + getString(R.string.gif_frames, picked.size))
            .setView(android.widget.LinearLayout(requireContext()).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                setPadding(dp(24), dp(12), dp(24), 0)
                addView(fpsInput)
                addView(loopInput)
            })
            .setPositiveButton(R.string.fmt_gif) { _, _ ->
                fps = fpsInput.text.toString().toIntOrNull()?.coerceIn(1, 24) ?: 2
                loop = loopInput.text.toString().toIntOrNull()?.coerceIn(0, 65535) ?: 0
                val ctx = requireContext().applicationContext
                Thread {
                    try {
                        val frames = picked.mapNotNull { s -> loadBitmap(ctx, s, 720) }
                        if (frames.size >= 2) {
                            ExportManager.saveGif(ctx, frames, fps, loop) { uri ->
                                if (uri != null) ErrorReporter.toast(ctx, getString(R.string.saved_to, uri))
                            }
                        }
                    } catch (e: Throwable) {
                        ErrorReporter.dialog(ctx, e)
                    }
                }.start()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun loadBitmap(ctx: Context, item: Shot, maxSide: Int): Bitmap? {
        return try {
            if (item.path != null) {
                com.pureshot.screenshot.core.util.BitmapUtil.decodeSampled(item.path, maxSide)
            } else {
                val uri = item.uri ?: return null
                val opts = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                ctx.contentResolver.openInputStream(uri)?.use {
                    android.graphics.BitmapFactory.decodeStream(it, null, opts)
                }
                var sample = 1
                while (maxOf(opts.outWidth, opts.outHeight) / (sample * 2) >= maxSide) sample *= 2
                val decode = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
                ctx.contentResolver.openInputStream(uri)?.use {
                    android.graphics.BitmapFactory.decodeStream(it, null, decode)
                }
            }
        } catch (e: Throwable) {
            null
        }
    }

    inner class Adapter : RecyclerView.Adapter<Adapter.VH>() {
        inner class VH(view: View) : RecyclerView.ViewHolder(view) {
            val thumb: ImageView = view.findViewById(R.id.gallery_thumb)
            val check: ImageView = view.findViewById(R.id.gallery_check)
            val mask: View = view.findViewById(R.id.gallery_mask)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_gallery, parent, false))

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = items[position]
            val ctx = holder.itemView.context
            if (item.uri != null) {
                Glide.with(ctx).load(item.uri).centerCrop().into(holder.thumb)
            } else if (item.path != null) {
                Glide.with(ctx).load(File(item.path)).centerCrop().into(holder.thumb)
            }
            val sel = selected.contains(item)
            holder.check.visibility = if (selectMode && sel) View.VISIBLE else View.GONE
            holder.mask.visibility = if (selectMode && sel) View.VISIBLE else View.GONE
            holder.itemView.setOnClickListener { onClick(item) }
            holder.itemView.setOnLongClickListener {
                if (!selectMode) {
                    selectMode = true
                    selected.add(item)
                    updateToolbar()
                    notifyDataSetChanged()
                }
                true
            }
        }
    }
}
