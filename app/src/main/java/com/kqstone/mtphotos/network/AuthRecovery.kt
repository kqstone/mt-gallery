package com.kqstone.mtphotos.network

import android.util.Log
import com.kqstone.mtphotos.data.api.AuthApi
import com.kqstone.mtphotos.data.local.PrefsManager
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import retrofit2.HttpException
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

private const val TAG = "AuthRecovery"

class AuthRecovery(private val prefsManager: PrefsManager) {
    private val mutex = Mutex()

    suspend fun recover(): Boolean = mutex.withLock {
        val serverUrl = prefsManager.getServerUrlSync()
        if (serverUrl.isBlank()) {
            prefsManager.setAuthRequired(true)
            return@withLock false
        }

        if (refreshToken(serverUrl)) return@withLock true

        val username = prefsManager.getUsernameSync()
        val password = prefsManager.getPasswordSync()
        if (username.isBlank() || password.isBlank()) {
            prefsManager.setAuthRequired(true)
            return@withLock false
        }

        return@withLock login(serverUrl, username, password)
    }

    private suspend fun refreshToken(serverUrl: String): Boolean {
        val refreshToken = prefsManager.getRefreshTokenSync()
        if (refreshToken.isBlank()) return false

        return try {
            val response = createAuthApi(serverUrl).AppControllerRefreshToken(
                mapOf("refresh_token" to refreshToken)
            )
            val token = response["access_token"] as? String
                ?: response["token"] as? String
                ?: return false
            val nextRefreshToken = response["refresh_token"] as? String ?: refreshToken
            prefsManager.saveToken(token, nextRefreshToken)
            prefsManager.setAuthRequired(false)
            Log.d(TAG, "Token refreshed")
            true
        } catch (e: Exception) {
            Log.w(TAG, "Token refresh failed", e)
            markConnectionState(e)
            false
        }
    }

    private suspend fun login(serverUrl: String, username: String, password: String): Boolean {
        return try {
            val response = createAuthApi(serverUrl).AppControllerLogin(
                mapOf(
                    "username" to username,
                    "password" to password
                )
            )
            val token = response["access_token"] as? String
                ?: response["token"] as? String
                ?: return false
            val refreshToken = response["refresh_token"] as? String ?: ""
            prefsManager.saveCredentials(serverUrl, username, password)
            prefsManager.saveToken(token, refreshToken)
            prefsManager.setAuthRequired(false)
            prefsManager.setServerUnreachable(false)
            Log.d(TAG, "Re-login succeeded")
            true
        } catch (e: Exception) {
            Log.w(TAG, "Re-login failed", e)
            if ((e as? HttpException)?.code() in setOf(401, 403)) {
                prefsManager.setAuthRequired(true)
            } else {
                markConnectionState(e)
            }
            false
        }
    }

    private suspend fun markConnectionState(error: Throwable) {
        if (NetworkFailure.isDeviceOffline(prefsManager.context)) {
            prefsManager.setNetworkRetryPending(true)
        } else if (NetworkFailure.isServerUnreachable(error)) {
            prefsManager.setServerUnreachable(true)
        }
    }

    private fun createAuthApi(serverUrl: String): AuthApi {
        val client = OkHttpClient.Builder()
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
}
