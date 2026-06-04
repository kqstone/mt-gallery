package com.kqstone.mtphotos.data.model

val TimelinePhotoComparator: Comparator<UnifiedPhotoItem> =
    compareByDescending<UnifiedPhotoItem> { MediaTimeParser.parseMillis(it.mtime) ?: Long.MIN_VALUE }
        .thenByDescending { it.mtime }
        .thenByDescending { it.cloudId ?: Double.NEGATIVE_INFINITY }
        .thenByDescending { it.dbId }
        .thenBy { it.md5 }
        .thenBy { it.fileName }
        .thenBy { it.fileType }

fun Iterable<UnifiedPhotoItem>.sortedForTimeline(): List<UnifiedPhotoItem> {
    return sortedWith(TimelinePhotoComparator)
}
