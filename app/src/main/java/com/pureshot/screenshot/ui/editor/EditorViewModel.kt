package com.pureshot.screenshot.ui.editor

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pureshot.screenshot.core.editor.EditDocument
import com.pureshot.screenshot.core.util.BitmapUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 视图模型层（MVVM）：承接编辑页 UI 事件、调度图片解码与文档状态管理。
 * 大图采样解码走 Coroutine 后台线程，杜绝 ANR。
 */
class EditorViewModel : ViewModel() {

    private val _document = MutableStateFlow<EditDocument?>(null)
    val document: StateFlow<EditDocument?> = _document

    private val _loadFailed = MutableStateFlow(false)
    val loadFailed: StateFlow<Boolean> = _loadFailed

    fun consumeLoadFailure() {
        _loadFailed.value = false
    }

    fun load(ctx: Context, path: String) {
        if (_document.value != null) return
        viewModelScope.launch {
            val bmp = withContext(Dispatchers.Default) {
                BitmapUtil.decodeSampledAny(ctx, path, 8192)
            }
            if (bmp != null) _document.value = EditDocument(bmp)
            else _loadFailed.value = true
        }
    }

    override fun onCleared() {
        _document.value?.dispose()
        super.onCleared()
    }
}
