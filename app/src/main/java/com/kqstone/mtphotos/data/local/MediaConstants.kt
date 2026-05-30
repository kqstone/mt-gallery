package com.kqstone.mtphotos.data.local

object MediaConstants {
    /**
     * Downloaded media is stored here.
     * This folder is implicitly included in backups and hidden from the backup selection UI.
     */
    const val MT_GALLERY_DOWNLOAD_FOLDER_RELATIVE = "Pictures/MtGallery"
    
    val MT_GALLERY_DOWNLOAD_FOLDER_ABSOLUTE: String
        get() = android.os.Environment.getExternalStorageDirectory().absolutePath + "/" + MT_GALLERY_DOWNLOAD_FOLDER_RELATIVE
}
