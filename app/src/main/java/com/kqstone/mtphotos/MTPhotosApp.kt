package com.kqstone.mtphotos

import android.app.Application
import com.kqstone.mtphotos.data.api.AuthApi
import com.kqstone.mtphotos.data.api.GatewayApi
import com.kqstone.mtphotos.data.local.LocalMediaScanner
import com.kqstone.mtphotos.data.local.MediaChangeObserver
import com.kqstone.mtphotos.data.local.PrefsManager
import com.kqstone.mtphotos.data.local.StorageOptimizer
import com.kqstone.mtphotos.data.local.ThumbnailCacheManager
import com.kqstone.mtphotos.data.local.db.AppDatabase
import com.kqstone.mtphotos.data.repository.AuthRepository
import com.kqstone.mtphotos.data.repository.GalleryRepository
import com.kqstone.mtphotos.data.repository.SyncRepository
import com.kqstone.mtphotos.network.AuthInterceptor
import com.kqstone.mtphotos.network.RetrofitClient

class MTPhotosApp : Application() {

    lateinit var container: AppContainer
        private set
    private var mediaChangeObserver: MediaChangeObserver? = null

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        mediaChangeObserver = MediaChangeObserver(this).also { it.register() }
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

    // Room 数据库
    val database = AppDatabase.getInstance(context)

    // 本地媒体扫描器
    val localMediaScanner = LocalMediaScanner(context)

    // 同步仓库（合并本地+云端数据）
    val syncRepository = SyncRepository(this, database)

    // 缩略图缓存管理器
    val thumbnailCacheManager = ThumbnailCacheManager(context)

    // 存储优化器
    val storageOptimizer = StorageOptimizer(context, syncRepository)
}

