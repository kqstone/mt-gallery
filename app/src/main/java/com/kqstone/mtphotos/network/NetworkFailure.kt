package com.kqstone.mtphotos.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.io.IOException
import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

object NetworkFailure {
    fun isDeviceOffline(context: Context): Boolean {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        val network = manager.activeNetwork ?: return true
        val capabilities = manager.getNetworkCapabilities(network) ?: return true
        return !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    fun isServerUnreachable(error: Throwable): Boolean {
        return error is UnknownHostException ||
            error is ConnectException ||
            error is SocketTimeoutException ||
            error is SocketException ||
            error is IOException
    }
}
