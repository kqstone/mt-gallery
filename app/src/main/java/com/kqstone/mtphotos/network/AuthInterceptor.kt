package com.kqstone.mtphotos.network

import com.kqstone.mtphotos.data.local.PrefsManager
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.Response

class AuthInterceptor(private val prefsManager: PrefsManager) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val token = prefsManager.getTokenSync()
        val request = if (token.isNotEmpty()) {
            chain.request().newBuilder()
                .header("Authorization", "Bearer $token")
                .build()
        } else {
            chain.request()
        }

        return chain.proceed(request)
    }
}
