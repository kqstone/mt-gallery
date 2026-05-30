package com.kqstone.mtphotos.ui.util

import android.content.Context
import android.content.Intent
import android.widget.Toast
import com.kqstone.mtphotos.data.model.UnifiedPhotoItem
import com.kqstone.mtphotos.data.repository.GalleryRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ShareManager(
    private val galleryRepository: GalleryRepository,
    private val coroutineScope: CoroutineScope
) {
    private val _isSharing = MutableStateFlow(false)
    val isSharing = _isSharing.asStateFlow()

    private val _shareProgressText = MutableStateFlow<String?>(null)
    val shareProgressText = _shareProgressText.asStateFlow()

    private val _shareProgress = MutableStateFlow<Float?>(null)
    val shareProgress = _shareProgress.asStateFlow()

    fun sharePhotos(context: Context, photos: List<UnifiedPhotoItem>, onComplete: () -> Unit = {}) {
        if (photos.isEmpty() || _isSharing.value) return
        _isSharing.value = true
        _shareProgressText.value = "准备分享中..."
        _shareProgress.value = null

        coroutineScope.launch {
            try {
                val uris = MediaShareHelper.prepareShareUris(
                    context = context,
                    photos = photos,
                    getFullImageUrl = { photo ->
                        if (!photo.localUri.isNullOrEmpty() && !photo.isStorageOptimized) {
                            return@prepareShareUris photo.localUri
                        }
                        val cloudId = photo.cloudId ?: return@prepareShareUris ""
                        galleryRepository.getFullImageUrl(cloudId, photo.md5)
                    },
                    getVideoUrl = { photo ->
                        if (!photo.isMotionPhoto() && !photo.localUri.isNullOrEmpty() && !photo.isStorageOptimized) {
                            return@prepareShareUris photo.localUri
                        }
                        val cloudId = photo.cloudId ?: return@prepareShareUris ""
                        if (photo.isMotionPhoto()) {
                            galleryRepository.getMotionPhotoUrl(cloudId, photo.md5)
                        } else {
                            galleryRepository.getTranscodeVideoUrl(cloudId, photo.md5)
                        }
                    },
                    onProgress = { current, total, ratio ->
                        if (photos.size == 1) {
                            val text = if (ratio != null) {
                                "准备分享中... ${(ratio * 100).toInt()}%"
                            } else {
                                "准备分享中..."
                            }
                            _shareProgressText.value = text
                            _shareProgress.value = ratio
                        } else {
                            _shareProgressText.value = "准备分享中: ${current + 1}/$total"
                            _shareProgress.value = ratio
                        }
                    }
                )

                withContext(Dispatchers.Main) {
                    _isSharing.value = false
                    _shareProgressText.value = null
                    _shareProgress.value = null
                    if (uris.isNotEmpty()) {
                        val shareIntent = Intent().apply {
                            action = if (uris.size > 1) Intent.ACTION_SEND_MULTIPLE else Intent.ACTION_SEND
                            if (uris.size == 1) {
                                putExtra(Intent.EXTRA_STREAM, uris.first())
                            } else {
                                putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
                            }
                            
                            val mimeTypes = uris.mapNotNull { context.contentResolver.getType(it) }.distinct()
                            type = when {
                                mimeTypes.isEmpty() -> "*/*"
                                mimeTypes.size == 1 -> mimeTypes.first()
                                mimeTypes.all { it.startsWith("image/") } -> "image/*"
                                mimeTypes.all { it.startsWith("video/") } -> "video/*"
                                else -> "*/*"
                            }
                            flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
                        }
                        context.startActivity(Intent.createChooser(shareIntent, "分享媒体"))
                        onComplete()
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("ShareManager", "Sharing failed", e)
                withContext(Dispatchers.Main) {
                    _isSharing.value = false
                    _shareProgressText.value = null
                    _shareProgress.value = null
                    Toast.makeText(context, "分享失败: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}
