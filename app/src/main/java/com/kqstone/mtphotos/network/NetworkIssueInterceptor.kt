package com.kqstone.mtphotos.network

import com.kqstone.mtphotos.data.local.PrefsManager
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException

class NetworkIssueInterceptor(
    private val prefsManager: PrefsManager
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        return try {
            chain.proceed(chain.request())
        } catch (e: IOException) {
            runBlocking {
                if (NetworkFailure.isDeviceOffline(prefsManager.context)) {
                    prefsManager.setNetworkRetryPending(true)
                } else if (NetworkFailure.isServerUnreachable(e)) {
                    prefsManager.setServerUnreachable(true)
                }
            }
            throw e
        }
    }
}
