package com.kqstone.mtphotos.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.util.Log
import com.kqstone.mtphotos.data.local.PrefsManager
import com.kqstone.mtphotos.worker.BackupScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

private const val TAG = "NetworkResumeMonitor"
private const val MIN_TRIGGER_INTERVAL_MS = 10_000L

class NetworkResumeMonitor(
    private val context: Context,
    private val prefsManager: PrefsManager
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var lastTriggerAt = 0L

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            triggerPendingWorkIfNeeded()
        }
    }

    fun register() {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return
        try {
            manager.registerDefaultNetworkCallback(callback)
            triggerPendingWorkIfNeeded()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to register network callback", e)
        }
    }

    private fun triggerPendingWorkIfNeeded() {
        scope.launch {
            if (!prefsManager.getNetworkRetryPendingSync()) return@launch
            if (NetworkFailure.isDeviceOffline(context)) return@launch

            val now = System.currentTimeMillis()
            if (now - lastTriggerAt < MIN_TRIGGER_INTERVAL_MS) return@launch
            lastTriggerAt = now

            prefsManager.setNetworkRetryPending(false)
            BackupScheduler.triggerServerOpWork(
                context,
                replaceExisting = true,
                forceRetryNow = true
            )
            if (prefsManager.getBackupEnabledSync()) {
                val wifiOnly = prefsManager.getBackupWifiOnlySync()
                BackupScheduler.triggerSyncWork(context)
                BackupScheduler.triggerImmediateBackup(context, wifiOnly, replaceExisting = true)
            }
            Log.d(TAG, "Triggered pending work after network became available")
        }
    }
}
