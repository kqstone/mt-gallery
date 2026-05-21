package com.kqstone.mtphotos.data.local

import android.os.Build

object BackupDestinationDefaults {
    private const val UPLOAD_ROOT = "upload"

    fun path(username: String?, deviceName: String = Build.MODEL): String {
        val cleanUser = username.orEmpty().trim().trim('/')
        val cleanDevice = deviceName.trim().trim('/').ifEmpty { "Android" }
        return if (cleanUser.isNotEmpty()) {
            "/$UPLOAD_ROOT/$cleanUser/$cleanDevice"
        } else {
            "/$UPLOAD_ROOT/$cleanDevice"
        }
    }
}
