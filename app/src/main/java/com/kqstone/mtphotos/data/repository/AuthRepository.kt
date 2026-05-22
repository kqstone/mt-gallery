package com.kqstone.mtphotos.data.repository

import android.util.Log
import com.kqstone.mtphotos.AppContainer
import com.kqstone.mtphotos.data.api.AuthApi
import com.kqstone.mtphotos.data.local.PrefsManager
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

private const val TAG = "AuthRepo"

class AuthRepository(private val container: AppContainer) {

    private val prefsManager: PrefsManager get() = container.prefsManager

    suspend fun login(
        serverUrl: String,
        username: String,
        password: String,
        primaryServerUrl: String = serverUrl,
        secondaryServerUrl: String = ""
    ): Result<Unit> {
        return try {
            val cleanUrl = cleanBaseUrl(serverUrl)
            val cleanPrimaryUrl = primaryServerUrl.trim().ifBlank { cleanUrl }.let(::cleanBaseUrl)
            val cleanSecondaryUrl = secondaryServerUrl.trim().takeIf { it.isNotBlank() }?.let(::cleanBaseUrl) ?: ""

            // Step 1: Login with plain text password
            Log.d(TAG, "Step 1: Logging in")
            val loginBody: Map<String, String> = mapOf(
                "username" to username,
                "password" to password
            )
            val loginResponse = createAuthApi(cleanUrl).AppControllerLogin(loginBody)
            Log.d(TAG, "Login response keys: ${loginResponse.keys}")

            val token = loginResponse["access_token"] as? String
                ?: loginResponse["token"] as? String
                ?: return Result.failure(Exception("Login succeeded but no token returned"))
            Log.d(TAG, "Got token: ${token.take(20)}...")

            val refreshTk = loginResponse["refresh_token"] as? String ?: ""
            Log.d(TAG, "Step 2: Saving credentials, serverUrl=$cleanUrl")
            prefsManager.saveServerUrls(cleanPrimaryUrl, cleanSecondaryUrl, cleanUrl)
            prefsManager.saveCredentials(cleanUrl, username, password)
            container.retrofitClient.invalidate()
            prefsManager.saveToken(token, refreshTk)

            // Step 3: Save auth_code from login response (no separate call needed)
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

    suspend fun loginWithSavedCredentials(
        serverUrl: String,
        primaryServerUrl: String,
        secondaryServerUrl: String
    ): Result<Unit> {
        val username = prefsManager.getUsernameSync()
        val password = prefsManager.getPasswordSync()
        if (username.isBlank() || password.isBlank()) {
            return Result.failure(Exception("缺少已保存的用户名或密码"))
        }
        return login(
            serverUrl = serverUrl,
            username = username,
            password = password,
            primaryServerUrl = primaryServerUrl,
            secondaryServerUrl = secondaryServerUrl
        )
    }

    suspend fun testServerUrl(serverUrl: String, username: String, password: String): Result<Unit> {
        return try {
            val api = createAuthApi(cleanBaseUrl(serverUrl))
            if (username.isNotBlank() && password.isNotBlank()) {
                api.AppControllerLogin(
                    mapOf(
                        "username" to username,
                        "password" to password
                    )
                )
            } else {
                api.AppControllerGetInfo()
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Server URL test failed", e)
            Result.failure(e)
        }
    }

    suspend fun refreshAuthCode(): Result<String> {
        return try {
            val refreshToken = prefsManager.getRefreshTokenSync()
            val body: Map<String, Any> = if (refreshToken.isNotEmpty()) {
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

    private fun cleanBaseUrl(serverUrl: String): String {
        val cleanUrl = serverUrl
            .trim()
            .replace(Regex("[\\p{Cf}\\p{Cc}]"), "")
            .trimEnd('/')
        if (cleanUrl.isBlank()) {
            throw IllegalArgumentException("服务器地址不能为空")
        }
        if (!cleanUrl.startsWith("http://") && !cleanUrl.startsWith("https://")) {
            throw IllegalArgumentException("服务器地址需要以 http:// 或 https:// 开头")
        }
        return cleanUrl
    }
}
