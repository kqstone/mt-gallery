package com.kqstone.mtphotos.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.kqstone.mtphotos.data.local.PrefsManager
import com.kqstone.mtphotos.worker.BackupScheduler

private const val TAG = "BootReceiver"

/**
 * 开机广播接收器：设备重启后恢复同步和备份调度。
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        val prefsManager = PrefsManager(context)
        if (prefsManager.getBackupEnabledSync()) {
            val wifiOnly = prefsManager.getBackupWifiOnlySync()
            val syncInterval = prefsManager.getSyncIntervalSync().toLong()
            BackupScheduler.scheduleAll(context, wifiOnly, syncInterval)
            Log.d(TAG, "Rescheduled after boot (wifiOnly=$wifiOnly, syncInterval=${syncInterval}min)")
        }
    }
}
