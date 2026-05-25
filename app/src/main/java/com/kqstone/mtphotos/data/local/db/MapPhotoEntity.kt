package com.kqstone.mtphotos.data.local.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.kqstone.mtphotos.data.repository.MapPhotoItem

@Entity(
    tableName = "map_photos",
    indices = [
        Index(value = ["md5"]),
        Index(value = ["lat", "lng"]),
        Index(value = ["updatedAt"])
    ]
)
data class MapPhotoEntity(
    @PrimaryKey
    val cloudId: Double,
    val md5: String,
    val lat: Double,
    val lng: Double,
    val fileName: String = "",
    val fileType: String = "",
    val mtime: String = "",
    val updatedAt: Long = System.currentTimeMillis()
) {
    fun toMapPhotoItem(): MapPhotoItem {
        return MapPhotoItem(
            id = cloudId,
            md5 = md5,
            lat = lat,
            lng = lng,
            fileName = fileName,
            fileType = fileType,
            mtime = mtime
        )
    }
}

fun MapPhotoItem.toMapPhotoEntity(updatedAt: Long = System.currentTimeMillis()): MapPhotoEntity {
    return MapPhotoEntity(
        cloudId = id,
        md5 = md5,
        lat = lat,
        lng = lng,
        fileName = fileName,
        fileType = fileType,
        mtime = mtime,
        updatedAt = updatedAt
    )
}
