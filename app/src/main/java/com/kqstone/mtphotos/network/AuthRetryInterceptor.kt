package com.kqstone.mtphotos.network

import com.kqstone.mtphotos.data.local.PrefsManager
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.Response

class AuthRetryInterceptor(
    private val prefsManager: PrefsManager,
    private val authRecovery: AuthRecovery
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val response = chain.proceed(request)
        if (request.isAuthRequest() || response.code !in setOf(401, 403)) {
            return response
        }

        response.close()
        val recovered = runBlocking { authRecovery.recover() }
        if (!recovered) {
            return chain.proceed(request)
        }

        val token = prefsManager.getTokenSync()
        val retryRequest = if (token.isNotEmpty()) {
            request.newBuilder()
                .header("Authorization", "Bearer $token")
                .build()
        } else {
            request
        }
        return chain.proceed(retryRequest)
    }

    private fun okhttp3.Request.isAuthRequest(): Boolean {
        val path = url.encodedPath
        return path == "/auth/login" || path == "/auth/refresh"
    }
}
