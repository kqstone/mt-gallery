package com.kqstone.mtphotos.data.repository

import android.util.Log
import com.kqstone.mtphotos.AppContainer
import com.kqstone.mtphotos.data.local.PrefsManager

private const val TAG = "AuthRepo"

class AuthRepository(private val container: AppContainer) {

    private val prefsManager: PrefsManager get() = container.prefsManager

    suspend fun login(serverUrl: String, username: String, password: String): Result<Unit> {
        return try {
            val cleanUrl = serverUrl.trim().trimEnd('/')
            Log.d(TAG, "Step 0: Saving credentials, serverUrl=$cleanUrl")
            prefsManager.saveCredentials(cleanUrl, username, password)
            container.retrofitClient.invalidate()

            // Step 1: Login with plain text password
            Log.d(TAG, "Step 1: Logging in")
            val loginBody = mapOf(
                "username" to username,
                "password" to password
            )
            val loginResponse = container.authApi.AppControllerLogin(loginBody)
            Log.d(TAG, "Login response keys: ${loginResponse.keys}")

            val token = loginResponse["access_token"] as? String
                ?: loginResponse["token"] as? String
                ?: return Result.failure(Exception("Login succeeded but no token returned"))
            Log.d(TAG, "Got token: ${token.take(20)}...")

            val refreshTk = loginResponse["refresh_token"] as? String ?: ""
            prefsManager.saveToken(token, refreshTk)

            // Step 2: Save auth_code from login response (no separate call needed)
            val authCode = loginResponse["auth_code"] as? String
                ?: loginResponse["authCode"] as? String
            if (authCode != null) {
                Log.d(TAG, "Got auth_code from login response")
                prefsManager.saveAuthCode(authCode)
            } else {
                Log.d(TAG, "No auth_code in login response, fetching separately")
                refreshAuthCode()
            }

            Log.d(TAG, "Login complete!")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Login failed", e)
            Result.failure(e)
        }
    }

    suspend fun refreshAuthCode(): Result<String> {
        return try {
            val refreshToken = prefsManager.getRefreshTokenSync()
            val body = if (refreshToken.isNotEmpty()) {
                mapOf("refresh_token" to refreshToken)
            } else {
                emptyMap()
            }
            val response = container.authApi.AppControllerGetAuthCode(body)
            val authCode = response["auth_code"] as? String
                ?: response["authCode"] as? String
                ?: return Result.failure(Exception("No auth_code in response"))
            prefsManager.saveAuthCode(authCode)
            Result.success(authCode)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun getAuthCode(): String {
        return prefsManager.getAuthCodeSync()
    }

    fun prefs(): PrefsManager {
        return prefsManager
    }
}
