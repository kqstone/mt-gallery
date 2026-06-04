package com.kqstone.mtphotos.worker

import com.kqstone.mtphotos.data.local.PrefsManager
import com.kqstone.mtphotos.network.NetworkFailure

suspend fun PrefsManager.markRecoverableFailure(error: Throwable) {
    if (NetworkFailure.isDeviceOffline(context)) {
        setNetworkRetryPending(true)
    } else if (NetworkFailure.isServerUnreachable(error)) {
        setServerUnreachable(true)
    }
}
