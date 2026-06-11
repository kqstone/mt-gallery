package com.kqstone.mtphotos.network

import android.util.Log
import com.kqstone.mtphotos.data.api.AuthApi
import com.kqstone.mtphotos.data.local.PrefsManager
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

private const val TAG = "MediaAuthInterceptor"

class MediaAuthInterceptor(
    private val prefsManager: PrefsManager,
    private val authRecovery: AuthRecovery
) : Interceptor {

    private companion object {
        private val authCodeRefreshLock = Any()
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = addBearerToken(chain.request())
        val response = chain.proceed(request)
        if (!request.usesAuthCode() || response.code !in setOf(401, 403)) {
            return response
        }

        val failedAuthCode = request.url.queryParameter("auth_code").orEmpty()
        val freshAuthCode = synchronized(authCodeRefreshLock) {
            val currentAuthCode = prefsManager.getAuthCodeSync()
            if (currentAuthCode.isNotBlank() && currentAuthCode != failedAuthCode) {
                currentAuthCode
            } else {
                runBlocking { refreshMediaAuthCode() }
            }
        }
        if (freshAuthCode.isBlank()) {
            return response
        }

        response.close()
        val retryUrl = request.url.newBuilder()
            .setQueryParameter("auth_code", freshAuthCode)
            .build()
        return chain.proceed(addBearerToken(request.newBuilder().url(retryUrl).build()))
    }

    private fun addBearerToken(request: okhttp3.Request): okhttp3.Request {
        val token = prefsManager.getTokenSync()
        return if (token.isNotEmpty()) {
            request.newBuilder()
                .header("Authorization", "Bearer $token")
                .build()
        } else {
            request
        }
    }

    private suspend fun refreshMediaAuthCode(): String {
        val direct = requestAuthCode()
        if (direct.isNotBlank()) return direct

        if (!authRecovery.recover()) return ""
        return requestAuthCode()
    }

    private suspend fun requestAuthCode(): String {
        val serverUrl = prefsManager.getServerUrlSync()
        if (serverUrl.isBlank()) return ""

        return try {
            val refreshToken = prefsManager.getRefreshTokenSync()
            val body: Map<String, Any> = if (refreshToken.isNotEmpty()) {
                mapOf("refresh_token" to refreshToken)
            } else {
                emptyMap()
            }
            val response = createAuthApi(serverUrl).AppControllerGetAuthCode(body)
            val authCode = response["auth_code"] as? String
                ?: response["authCode"] as? String
                ?: return ""
            prefsManager.saveAuthCode(authCode)
            authCode
        } catch (e: Exception) {
            Log.w(TAG, "Failed to refresh media auth_code", e)
            ""
        }
    }

    private fun createAuthApi(serverUrl: String): AuthApi {
        val client = OkHttpClient.Builder()
            .addInterceptor(AuthInterceptor(prefsManager))
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
        val url = if (serverUrl.endsWith("/")) serverUrl else "$serverUrl/"
        return Retrofit.Builder()
            .baseUrl(url)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(AuthApi::class.java)
    }

    private fun okhttp3.Request.usesAuthCode(): Boolean {
        return url.queryParameter("auth_code") != null
    }
}
