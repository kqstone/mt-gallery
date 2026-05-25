package com.kqstone.mtphotos.data.local.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

@Dao
interface MapPhotoDao {
    @Query("SELECT * FROM map_photos")
    suspend fun getAll(): List<MapPhotoEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(photos: List<MapPhotoEntity>)

    @Query("DELETE FROM map_photos")
    suspend fun clear()

    @Transaction
    suspend fun replaceAll(photos: List<MapPhotoEntity>) {
        clear()
        if (photos.isNotEmpty()) {
            upsertAll(photos)
        }
    }
}
