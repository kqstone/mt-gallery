package com.kqstone.mtphotos.ui.gallery

import com.kqstone.mtphotos.data.model.UnifiedPhotoItem
import kotlin.jvm.JvmName

@JvmName("removeUnifiedPhotos")
fun List<UnifiedPhotoItem>.removePhotos(photos: List<UnifiedPhotoItem>): List<UnifiedPhotoItem> {
    val keys = photos.identityKeys()
    if (keys.isEmpty()) return this
    return filterNot { it.matchesAny(keys) }
}

@JvmName("updateUnifiedFavorite")
fun List<UnifiedPhotoItem>.updateFavorite(
    photos: List<UnifiedPhotoItem>,
    isFavorite: Boolean
): List<UnifiedPhotoItem> {
    val keys = photos.identityKeys()
    if (keys.isEmpty()) return this
    return map { photo ->
        if (photo.matchesAny(keys)) photo.copy(isFavorite = isFavorite) else photo
    }
}

@JvmName("updateUnifiedHide")
fun List<UnifiedPhotoItem>.updateHide(
    photos: List<UnifiedPhotoItem>,
    isHide: Boolean
): List<UnifiedPhotoItem> {
    val keys = photos.identityKeys()
    if (keys.isEmpty()) return this
    return map { photo ->
        if (photo.matchesAny(keys)) photo.copy(isHide = isHide) else photo
    }
}

fun List<UnifiedPhotoItem>.upsertPhotos(photos: List<UnifiedPhotoItem>): List<UnifiedPhotoItem> {
    if (photos.isEmpty()) return this
    val merged = linkedMapOf<String, UnifiedPhotoItem>()
    for (photo in this) {
        merged[photo.primaryIdentityKey()] = photo
    }
    for (photo in photos) {
        merged[photo.primaryIdentityKey()] = photo
    }
    return merged.values.toList()
}

@JvmName("removeMonthGroupPhotos")
fun List<MonthGroup>.removePhotos(photos: List<UnifiedPhotoItem>): List<MonthGroup> {
    val keys = photos.identityKeys()
    if (keys.isEmpty()) return this
    return mapNotNull { month ->
        val updatedDays = month.days.mapNotNull { day ->
            val updatedPhotos = day.photos.filterNot { it.matchesAny(keys) }
            if (updatedPhotos.isEmpty()) null else day.copy(photos = updatedPhotos)
        }
        val removedCount = month.days.sumOf { day ->
            day.photos.count { it.matchesAny(keys) }
        }
        val loadedCount = updatedDays.sumOf { it.photos.size }
        val totalCount = (month.totalCount - removedCount).coerceAtLeast(loadedCount)
        if (updatedDays.isEmpty() && (month.isLoaded || totalCount == 0)) {
            null
        } else {
            month.copy(days = updatedDays, totalCount = totalCount)
        }
    }
}

@JvmName("updateMonthGroupFavorite")
fun List<MonthGroup>.updateFavorite(
    photos: List<UnifiedPhotoItem>,
    isFavorite: Boolean
): List<MonthGroup> {
    val keys = photos.identityKeys()
    if (keys.isEmpty()) return this
    return map { month ->
        month.copy(
            days = month.days.map { day ->
                day.copy(
                    photos = day.photos.map { photo ->
                        if (photo.matchesAny(keys)) photo.copy(isFavorite = isFavorite) else photo
                    }
                )
            }
        )
    }
}

@JvmName("updateMonthGroupHide")
fun List<MonthGroup>.updateHide(
    photos: List<UnifiedPhotoItem>,
    isHide: Boolean
): List<MonthGroup> {
    val keys = photos.identityKeys()
    if (keys.isEmpty()) return this
    return map { month ->
        month.copy(
            days = month.days.map { day ->
                day.copy(
                    photos = day.photos.map { photo ->
                        if (photo.matchesAny(keys)) photo.copy(isHide = isHide) else photo
                    }
                )
            }
        )
    }
}

fun List<MonthGroup>.upsertPhotos(
    photos: List<UnifiedPhotoItem>,
    rebuildGroups: (List<UnifiedPhotoItem>) -> List<MonthGroup>
): List<MonthGroup> {
    if (photos.isEmpty()) return this
    return flattenPhotos().upsertPhotos(photos).let(rebuildGroups)
}

fun List<MonthGroup>.flattenPhotos(): List<UnifiedPhotoItem> {
    return flatMap { month -> month.days.flatMap { it.photos } }
}

fun UnifiedPhotoItem.identityKeys(): Set<String> {
    val keys = mutableSetOf<String>()
    cloudId?.takeIf { it > 0 }?.let { keys += "cloud:$it" }
    dbId.takeIf { it > 0 }?.let { keys += "db:$it" }
    md5.takeIf { it.isNotBlank() }?.let { keys += "md5:$it" }
    keys += "key:$uniqueKey"
    return keys
}

fun Collection<UnifiedPhotoItem>.identityKeys(): Set<String> {
    return flatMap { it.identityKeys() }.toSet()
}

fun UnifiedPhotoItem.matchesAny(keys: Set<String>): Boolean {
    return identityKeys().any { it in keys }
}

private fun UnifiedPhotoItem.primaryIdentityKey(): String {
    return when {
        cloudId != null && cloudId > 0 -> "cloud:$cloudId"
        dbId > 0 -> "db:$dbId"
        md5.isNotBlank() -> "md5:$md5"
        else -> "key:$uniqueKey"
    }
}
