package com.kqstone.mtphotos

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import coil.request.CachePolicy
import okhttp3.OkHttpClient
import okhttp3.Response
import com.kqstone.mtphotos.data.api.AuthApi
import com.kqstone.mtphotos.data.api.GatewayApi
import com.kqstone.mtphotos.data.api.AlbumApi
import com.kqstone.mtphotos.data.api.PeopleDescriptorAdminApi
import com.kqstone.mtphotos.data.api.ShareApi
import com.kqstone.mtphotos.data.local.LocalMediaScanner
import com.kqstone.mtphotos.data.local.MediaChangeObserver
import com.kqstone.mtphotos.data.local.PrefsManager
import com.kqstone.mtphotos.data.local.StorageOptimizer
import com.kqstone.mtphotos.data.local.ThumbnailCacheManager
import com.kqstone.mtphotos.data.local.ThumbnailCachePaths
import com.kqstone.mtphotos.data.local.OriginalDownloadManager
import com.kqstone.mtphotos.data.local.db.AppDatabase
import com.kqstone.mtphotos.data.repository.AuthRepository
import com.kqstone.mtphotos.data.repository.BackupDestinationRepository
import com.kqstone.mtphotos.data.repository.GalleryRepository
import com.kqstone.mtphotos.data.repository.MediaUiMutationBus
import com.kqstone.mtphotos.data.repository.ServerOpTaskRepository
import com.kqstone.mtphotos.data.repository.SyncRepository
import com.kqstone.mtphotos.network.AuthInterceptor
import com.kqstone.mtphotos.network.AuthRecovery
import com.kqstone.mtphotos.network.MediaAuthInterceptor
import com.kqstone.mtphotos.network.NetworkResumeMonitor
import com.kqstone.mtphotos.network.NetworkIssueInterceptor
import com.kqstone.mtphotos.network.RetrofitClient
import com.kqstone.mtphotos.worker.BackupScheduler
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class MTPhotosApp : Application(), ImageLoaderFactory {

    companion object {
        fun updateImageLoader(context: android.content.Context) {
            val app = context.applicationContext as MTPhotosApp
            coil.Coil.setImageLoader(app.newImageLoader())
        }
    }

    lateinit var container: AppContainer
        private set
    private var mediaChangeObserver: MediaChangeObserver? = null
    private var networkResumeMonitor: NetworkResumeMonitor? = null
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    lateinit var fullImageLoader: ImageLoader
        private set
    lateinit var videoCache: SimpleCache
        private set

    override fun newImageLoader(): ImageLoader {
        val prefsManager = PrefsManager(this)
        val authRecovery = AuthRecovery(prefsManager)
        val maxCacheMb = prefsManager.getCoilDiskCacheMbSync()
        return ImageLoader.Builder(this)
            .okHttpClient {
                mediaOkHttpClient(prefsManager, authRecovery)
            }
            .memoryCachePolicy(CachePolicy.ENABLED)
            .memoryCache {
                MemoryCache.Builder(this)
                    .maxSizePercent(0.25)
                    .build()
            }
            .diskCachePolicy(CachePolicy.ENABLED)
            .diskCache {
                DiskCache.Builder()
                    .directory(ThumbnailCachePaths.coilImageCacheDir(this))
                    .maxSizeBytes(maxCacheMb * 1024L * 1024L)
                    .build()
            }
            .allowRgb565(true)
            .crossfade(true)
            .build()
    }

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        val prefsManager = container.prefsManager
        val authRecovery = container.authRecovery

        // 初始化全尺寸大图 ImageLoader 实例，使用独立的磁盘缓存，防止挤压缩略图缓存
        fullImageLoader = ImageLoader.Builder(this)
            .okHttpClient {
                mediaOkHttpClient(prefsManager, authRecovery)
            }
            .memoryCachePolicy(CachePolicy.ENABLED)
            .memoryCache {
                MemoryCache.Builder(this)
                    .maxSizePercent(0.15)
                    .build()
            }
            .diskCachePolicy(CachePolicy.ENABLED)
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("full_image_cache"))
                    .maxSizeBytes(1024L * 1024L * 1024L) // 1 GB LRU 限额
                    .build()
            }
            .allowRgb565(true)
            .crossfade(true)
            .build()

        // 初始化视频缓存器，使用 3 GB 物理限额与 LRU 机制
        val databaseProvider = StandaloneDatabaseProvider(this)
        val videoCacheDir = File(cacheDir, "video_cache")
        val evictor = LeastRecentlyUsedCacheEvictor(3L * 1024L * 1024L * 1024L) // 3 GB LRU 限额
        videoCache = SimpleCache(videoCacheDir, evictor, databaseProvider)

        // 注册 MediaStore 变化监听（备份启用时自动触发同步）
        mediaChangeObserver = MediaChangeObserver(
            context = this,
            isBackupEnabled = { container.prefsManager.getBackupEnabledSync() },
            onDebouncedChange = { uris ->
                appScope.launch {
                    val folders = container.prefsManager.getBackupFolderSelectionSync().effectiveFolders
                    container.syncRepository.handleMediaStoreChanged(uris, folders)
                }
            }
        ).also { it.register() }
        networkResumeMonitor = NetworkResumeMonitor(this, container.prefsManager).also { it.register() }

        // 启动时自动恢复调度（备份已启用的情况下）
        if (container.prefsManager.getBackupEnabledSync()) {
            val wifiOnly = container.prefsManager.getBackupWifiOnlySync()
            val syncInterval = container.prefsManager.getSyncIntervalSync().toLong()
            BackupScheduler.scheduleAll(this, wifiOnly, syncInterval)
        } else {
            BackupScheduler.schedulePeriodicServerOp(this)
        }
        BackupScheduler.triggerServerOpWork(this)
    }
}

private fun Response.withThumbnailCacheControl(): Response {
    if (!isSuccessful) return this
    return newBuilder()
        .header("Cache-Control", "public, max-age=31536000")
        .build()
}

private fun mediaOkHttpClient(
    prefsManager: PrefsManager,
    authRecovery: AuthRecovery
): OkHttpClient {
    return OkHttpClient.Builder()
        .addInterceptor(MediaAuthInterceptor(prefsManager, authRecovery))
        .addInterceptor(NetworkIssueInterceptor(prefsManager))
        .addNetworkInterceptor { chain ->
            val response = chain.proceed(chain.request())
            response.withThumbnailCacheControl()
        }
        .build()
}

class AppContainer(context: android.content.Context) {
    val prefsManager = PrefsManager(context)
    val authInterceptor = AuthInterceptor(prefsManager)
    val authRecovery = AuthRecovery(prefsManager)
    val retrofitClient = RetrofitClient(prefsManager, authInterceptor, authRecovery)

    // API instances are derived from current Retrofit (rebuilt on URL change)
    val authApi: AuthApi get() = retrofitClient.create()
    val gatewayApi: GatewayApi get() = retrofitClient.create()
    val albumApi: AlbumApi get() = retrofitClient.create()
    val peopleDescriptorAdminApi: PeopleDescriptorAdminApi get() = retrofitClient.create()
    val shareApi: ShareApi get() = retrofitClient.create()

    val authRepository = AuthRepository(this)
    val backupDestinationRepository = BackupDestinationRepository(this)
    val mediaUiMutationBus = MediaUiMutationBus()
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
    val storageOptimizer = StorageOptimizer(syncRepository)

    // 服务器操作任务仓库
    val serverOpTaskRepository = ServerOpTaskRepository(this, database)

    // 原文件后台下载管理器
    val originalDownloadManager = OriginalDownloadManager(context, galleryRepository)
}
