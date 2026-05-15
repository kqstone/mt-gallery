package com.kqstone.mtphotos.ui.util

import com.kqstone.mtphotos.data.repository.PhotoItem

fun PhotoItem.isVideo(): Boolean {
    val ft = fileType.lowercase()
    return ft.startsWith("video") ||
        ft == "mp4" || ft == "mov" || ft == "avi" || ft == "mkv" ||
        fileName.endsWith(".mp4", true) ||
        fileName.endsWith(".mov", true) ||
        fileName.endsWith(".avi", true)
}
