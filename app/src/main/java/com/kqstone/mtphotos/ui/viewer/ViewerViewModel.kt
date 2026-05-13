package com.kqstone.mtphotos.ui.viewer

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.kqstone.mtphotos.data.repository.GalleryRepository

class ViewerViewModel(private val galleryRepository: GalleryRepository) : ViewModel() {

    fun getFullImageUrl(id: Double, md5: String): String {
        return galleryRepository.getFullImageUrl(id, md5)
    }

    class Factory(private val galleryRepository: GalleryRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return ViewerViewModel(galleryRepository) as T
        }
    }
}
