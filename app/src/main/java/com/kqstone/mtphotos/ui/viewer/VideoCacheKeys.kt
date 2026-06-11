package com.kqstone.mtphotos.ui.viewer

import android.net.Uri

fun stableVideoCacheKey(videoUrl: String): String? {
    if (!videoUrl.startsWith("http://") && !videoUrl.startsWith("https://")) return null

    val uri = runCatching { Uri.parse(videoUrl) }.getOrNull() ?: return null
    val segments = uri.pathSegments
    val fileIndex = segments.indexOf("file")
    if (fileIndex >= 0 && fileIndex + 2 < segments.size) {
        val fileId = segments[fileIndex + 1]
        val md5 = segments[fileIndex + 2]
        val variant = uri.getQueryParameter("type") ?: "original"
        return "video:file:$fileId:$md5:$variant"
    }

    val motionIndex = segments.indexOf("fileMotion")
    if (motionIndex >= 0 && motionIndex + 2 < segments.size) {
        val fileId = segments[motionIndex + 1]
        val md5 = segments[motionIndex + 2]
        return "video:motion:$fileId:$md5"
    }

    return null
}
