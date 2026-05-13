package com.kqstone.mtphotos.network

import com.kqstone.mtphotos.data.local.PrefsManager
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

class RetrofitClient(
    private val prefsManager: PrefsManager,
    private val authInterceptor: AuthInterceptor
) {
    private var cachedRetrofit: Retrofit? = null
    private var cachedBaseUrl: String = ""

    // Placeholder used before server URL is configured
    private companion object {
        const val PLACEHOLDER_URL = "http://localhost/"
    }

    fun getRetrofit(): Retrofit {
        val baseUrl = prefsManager.getServerUrlSync().trim().ifEmpty { PLACEHOLDER_URL }
        if (cachedRetrofit != null && cachedBaseUrl == baseUrl) {
            return cachedRetrofit!!
        }

        val logging = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BODY
        }

        val client = OkHttpClient.Builder()
            .addInterceptor(authInterceptor)
            .addInterceptor(logging)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()

        val url = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"
        val retrofit = Retrofit.Builder()
            .baseUrl(url)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()

        cachedRetrofit = retrofit
        cachedBaseUrl = baseUrl
        return retrofit
    }

    fun invalidate() {
        cachedRetrofit = null
        cachedBaseUrl = ""
    }

    fun <T> create(service: Class<T>): T = getRetrofit().create(service)

    inline fun <reified T> create(): T = create(T::class.java)
}
