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
import com.kqstone.mtphotos.worker.BackupScheduler

class MTPhotosApp : Application() {

    lateinit var container: AppContainer
        private set
    private var mediaChangeObserver: MediaChangeObserver? = null

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)

        // 注册 MediaStore 变化监听（备份启用时自动触发同步）
        mediaChangeObserver = MediaChangeObserver(this) {
            container.prefsManager.getBackupEnabledSync()
        }.also { it.register() }

        // 启动时自动恢复调度（备份已启用的情况下）
        if (container.prefsManager.getBackupEnabledSync()) {
            val wifiOnly = container.prefsManager.getBackupWifiOnlySync()
            val syncInterval = container.prefsManager.getSyncIntervalSync().toLong()
            BackupScheduler.scheduleAll(this, wifiOnly, syncInterval)
        }
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
