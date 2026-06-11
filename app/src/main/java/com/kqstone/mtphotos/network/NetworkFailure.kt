package com.kqstone.mtphotos.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.Locale
import kotlinx.coroutines.CancellationException

object NetworkFailure {
    fun isDeviceOffline(context: Context): Boolean {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        val network = manager.activeNetwork ?: return true
        val capabilities = manager.getNetworkCapabilities(network) ?: return true
        return !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    fun isServerUnreachable(error: Throwable): Boolean {
        if (isRequestCanceled(error)) return false
        return error is UnknownHostException ||
            error is ConnectException ||
            error is SocketTimeoutException ||
            error is SocketException
    }

    fun isRequestCanceled(error: Throwable): Boolean {
        return generateSequence(error) { it.cause }.any { candidate ->
            candidate is CancellationException ||
                candidate.message?.trim()
                    ?.lowercase(Locale.US)
                    ?.let { it == "canceled" || it == "cancelled" } == true
        }
    }
}
