package com.pureshot.screenshot.ui.gallery

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** 图库条目（媒体库或应用私有目录） */
data class Shot(val id: Long, val uri: Uri?, val path: String?, val name: String)

/**
 * 图库视图模型（MVVM）：截图列表查询/删除统一调度，Flow 状态驱动 UI。
 */
class GalleryViewModel : ViewModel() {

    private val _shots = MutableStateFlow<List<Shot>>(emptyList())
    val shots: StateFlow<List<Shot>> = _shots

    fun reload(ctx: Context) {
        val app = ctx.applicationContext
        viewModelScope.launch {
            _shots.value = withContext(Dispatchers.IO) { loadShots(app) }
        }
    }

    fun delete(ctx: Context, targets: Collection<Shot>) {
        val app = ctx.applicationContext
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                for (s in targets) {
                    try {
                        s.uri?.let { app.contentResolver.delete(it, null, null) }
                        s.path?.let { File(it).delete() }
                    } catch (e: Throwable) { /* 单条删除失败容错 */ }
                }
            }
            reload(app)
        }
    }

    private fun loadShots(ctx: Context): List<Shot> {
        val list = ArrayList<Shot>()
        try {
            val proj = arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DISPLAY_NAME)
            val sel = "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ?"
            ctx.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI, proj, sel,
                arrayOf("%PureShot%"), "${MediaStore.Images.Media._ID} DESC"
            )?.use { c ->
                val idCol = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val nameCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
                while (c.moveToNext()) {
                    val id = c.getLong(idCol)
                    list.add(
                        Shot(
                            id,
                            android.content.ContentUris.withAppendedId(
                                MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id
                            ),
                            null,
                            c.getString(nameCol)
                        )
                    )
                }
            }
        } catch (e: Throwable) { /* 媒体库读取失败容错 */ }
        try {
            val dir = File(
                ctx.getExternalFilesDir(Environment.DIRECTORY_PICTURES) ?: ctx.filesDir,
                "PureShot"
            )
            dir.listFiles()?.sortedByDescending { it.lastModified() }?.forEach { f ->
                list.add(Shot(f.absolutePath.hashCode().toLong(), null, f.absolutePath, f.name))
            }
        } catch (e: Throwable) { /* 容错 */ }
        return list
    }
}
