package com.kqstone.mtphotos.ui.settings

import android.content.Context
import android.util.Log
import androidx.annotation.Keep
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.google.gson.Gson
import com.kqstone.mtphotos.R
import com.kqstone.mtphotos.ui.util.UiText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream

private const val TAG = "AboutVM"

sealed interface CheckStatus {
    object Idle : CheckStatus
    object Checking : CheckStatus
    object UpToDate : CheckStatus
    data class NewVersionAvailable(val latestVersion: String, val downloadUrl: String, val body: String) : CheckStatus
    data class Error(val message: UiText) : CheckStatus
}

sealed interface DownloadStatus {
    object Idle : DownloadStatus
    data class Downloading(val progress: Float) : DownloadStatus
    data class Success(val apkFile: File) : DownloadStatus
    data class Error(val message: UiText) : DownloadStatus
}

data class AboutUiState(
    val currentVersion: String = "1.0",
    val checkStatus: CheckStatus = CheckStatus.Idle,
    val downloadStatus: DownloadStatus = DownloadStatus.Idle
)

// Data classes for parsing GitHub releases JSON
// @Keep prevents R8 from obfuscating field names, which Gson needs for reflection-based deserialization
@Keep
private data class GitHubRelease(
    val tag_name: String,
    val body: String?,
    val assets: List<GitHubAsset>?
)

@Keep
private data class GitHubAsset(
    val name: String,
    val browser_download_url: String
)

class AboutViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(AboutUiState())
    val uiState: StateFlow<AboutUiState> = _uiState.asStateFlow()

    private val httpClient = OkHttpClient.Builder().build()
    private val gson = Gson()

    fun initVersion(context: Context) {
        viewModelScope.launch {
            try {
                val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
                var verName = packageInfo.versionName ?: "1.0"
                val isDebug = context.packageName.endsWith(".debug") ||
                        (context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
                if (isDebug) {
                    verName += " (Debug)"
                }
                _uiState.update { it.copy(currentVersion = verName) }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to get version name", e)
            }
        }
    }

    fun checkForUpdates() {
        if (_uiState.value.checkStatus is CheckStatus.Checking) return

        _uiState.update { it.copy(checkStatus = CheckStatus.Checking, downloadStatus = DownloadStatus.Idle) }

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val request = Request.Builder()
                    .url("https://api.github.com/repos/kqstone/mt-gallery/releases/latest")
                    .header("User-Agent", "MT-Gallery-Android-App")
                    .build()

                val response = httpClient.newCall(request).execute()
                if (!response.isSuccessful) {
                    throw Exception("HTTP error code: ${response.code}")
                }

                val bodyStr = response.body?.string() ?: throw Exception("Empty response body")
                val release = gson.fromJson(bodyStr, GitHubRelease::class.java)
                
                val latestTag = release.tag_name
                val body = release.body ?: ""
                val apkAsset = release.assets?.firstOrNull { it.name.endsWith(".apk") }
                
                if (apkAsset == null) {
                    throw Exception("No APK found in the latest release assets")
                }

                val currentVer = _uiState.value.currentVersion
                val isNew = isNewerVersion(currentVer, latestTag)

                withContext(Dispatchers.Main) {
                    if (isNew) {
                        _uiState.update {
                            it.copy(
                                checkStatus = CheckStatus.NewVersionAvailable(
                                    latestVersion = latestTag,
                                    downloadUrl = apkAsset.browser_download_url,
                                    body = body
                                )
                            )
                        }
                    } else {
                        _uiState.update { it.copy(checkStatus = CheckStatus.UpToDate) }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to check for updates", e)
                withContext(Dispatchers.Main) {
                    val msg = e.message
                    val uiMsg = if (msg.isNullOrBlank()) {
                        UiText.StringResource(R.string.update_error)
                    } else {
                        UiText.DynamicString(msg)
                    }
                    _uiState.update {
                        it.copy(checkStatus = CheckStatus.Error(uiMsg))
                    }
                }
            }
        }
    }

    fun downloadAndInstallApk(context: Context, downloadUrl: String) {
        if (_uiState.value.downloadStatus is DownloadStatus.Downloading) return

        _uiState.update { it.copy(downloadStatus = DownloadStatus.Downloading(0f)) }

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val request = Request.Builder()
                    .url(downloadUrl)
                    .build()

                val response = httpClient.newCall(request).execute()
                if (!response.isSuccessful) {
                    throw Exception("HTTP download error: ${response.code}")
                }

                val responseBody = response.body ?: throw Exception("Empty download response body")
                val contentLength = responseBody.contentLength()

                val sharedMediaDir = File(context.cacheDir, "shared_media")
                if (!sharedMediaDir.exists()) {
                    sharedMediaDir.mkdirs()
                }
                val apkFile = File(sharedMediaDir, "update.apk")

                responseBody.byteStream().use { input ->
                    FileOutputStream(apkFile).use { output ->
                        val buffer = ByteArray(8192)
                        var bytesRead: Int
                        var totalBytesRead = 0L

                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            output.write(buffer, 0, bytesRead)
                            totalBytesRead += bytesRead
                            if (contentLength > 0) {
                                val progress = totalBytesRead.toFloat() / contentLength
                                withContext(Dispatchers.Main) {
                                    _uiState.update { it.copy(downloadStatus = DownloadStatus.Downloading(progress)) }
                                }
                            }
                        }
                        output.flush()
                    }
                }

                withContext(Dispatchers.Main) {
                    _uiState.update { it.copy(downloadStatus = DownloadStatus.Success(apkFile)) }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Download failed", e)
                withContext(Dispatchers.Main) {
                    val msg = e.message
                    val uiMsg = if (msg.isNullOrBlank()) {
                        UiText.StringResource(R.string.download_failed)
                    } else {
                        UiText.DynamicString(msg)
                    }
                    _uiState.update {
                        it.copy(downloadStatus = DownloadStatus.Error(uiMsg))
                    }
                }
            }
        }
    }

    fun resetDownloadStatus() {
        _uiState.update { it.copy(downloadStatus = DownloadStatus.Idle) }
    }

    fun isNewerVersion(current: String, latest: String): Boolean {
        val cleanCurrent = current.trim().lowercase().removePrefix("v").substringBefore(" ")
        val cleanLatest = latest.trim().lowercase().removePrefix("v").substringBefore(" ")
        if (cleanCurrent == cleanLatest) return false

        val currentParts = cleanCurrent.split(".").mapNotNull { it.takeWhile { char -> char.isDigit() }.toLongOrNull() }
        val latestParts = cleanLatest.split(".").mapNotNull { it.takeWhile { char -> char.isDigit() }.toLongOrNull() }

        val size = maxOf(currentParts.size, latestParts.size)
        for (i in 0 until size) {
            val currVal = currentParts.getOrElse(i) { 0L }
            val latVal = latestParts.getOrElse(i) { 0L }
            if (latVal > currVal) return true
            if (currVal > latVal) return false
        }
        return false
    }

    class Factory : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return AboutViewModel() as T
        }
    }
}
