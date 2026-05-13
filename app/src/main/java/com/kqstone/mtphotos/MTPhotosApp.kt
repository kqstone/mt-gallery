package com.kqstone.mtphotos

import android.app.Application
import com.kqstone.mtphotos.data.api.AuthApi
import com.kqstone.mtphotos.data.api.GatewayApi
import com.kqstone.mtphotos.data.local.PrefsManager
import com.kqstone.mtphotos.data.repository.AuthRepository
import com.kqstone.mtphotos.data.repository.GalleryRepository
import com.kqstone.mtphotos.network.AuthInterceptor
import com.kqstone.mtphotos.network.RetrofitClient

class MTPhotosApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}

class AppContainer(context: android.content.Context) {
    val prefsManager = PrefsManager(context)
    val authInterceptor = AuthInterceptor(prefsManager)
    val retrofitClient = RetrofitClient(prefsManager, authInterceptor)

    // API instances are derived from current Retrofit (rebuilt on URL change)
    val authApi: AuthApi get() = retrofitClient.create()
    val gatewayApi: GatewayApi get() = retrofitClient.create()

    val authRepository = AuthRepository(this)
    val galleryRepository = GalleryRepository(this)
}
