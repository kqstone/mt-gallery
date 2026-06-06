package com.kqstone.mtphotos.ui.gallery

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.kqstone.mtphotos.data.local.PrivacyLockMode
import com.kqstone.mtphotos.R
import com.kqstone.mtphotos.data.local.PrefsManager
import com.kqstone.mtphotos.data.model.UnifiedPhotoItem
import com.kqstone.mtphotos.data.model.sortedForTimeline
import com.kqstone.mtphotos.data.repository.GalleryRepository
import com.kqstone.mtphotos.data.repository.MediaUiMutation
import com.kqstone.mtphotos.data.repository.MediaUiMutationBus
import com.kqstone.mtphotos.data.repository.ServerOpTaskRepository
import com.kqstone.mtphotos.data.repository.toCloudOnlyUnifiedPhotoItem
import com.kqstone.mtphotos.ui.util.ShareManager
import com.kqstone.mtphotos.ui.util.ThumbnailUrlResolver
import com.kqstone.mtphotos.ui.util.UiText
import com.kqstone.mtphotos.worker.BackupScheduler
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

data class PrivateAlbumUiState(
    val months: List<MonthGroup> = emptyList(),
    val isLocked: Boolean = true,
    val showIntro: Boolean = false,
    val password: String = "",
    val localPin: String = "",
    val setupPin: String = "",
    val setupPinConfirm: String = "",
    val isLoading: Boolean = false,
    val error: UiText? = null,
    val columnCount: Int = 4,
    val toastMessage: UiText? = null,
    val localLockMode: PrivacyLockMode = PrivacyLockMode.NONE,
    val showSetupPrompt: Boolean = false,
    val setupMode: PrivacyLockMode? = null
)

class PrivateAlbumViewModel(
    private val galleryRepository: GalleryRepository,
    private val prefsManager: PrefsManager,
    private val serverOpTaskRepository: ServerOpTaskRepository,
    private val appContext: Context,
    private val mediaUiMutationBus: MediaUiMutationBus? = null
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        PrivateAlbumUiState(
            showIntro = !prefsManager.getPrivacyIntroShownSync(),
            localLockMode = prefsManager.getPrivacyLockModeSync(),
            showSetupPrompt = false
        )
    )
    val uiState: StateFlow<PrivateAlbumUiState> = _uiState

    val selectionManager = SelectionManager(
        scope = viewModelScope,
        onDelete = { ids -> galleryRepository.deleteFiles(ids) },
        onError = { msg -> _uiState.value = _uiState.value.copy(error = msg) }
    )
    val shareManager = ShareManager(galleryRepository, viewModelScope)

    private var sessionPassword: String = ""

    init {
        observeMediaUiMutations()
    }

    private fun observeMediaUiMutations() {
        val bus = mediaUiMutationBus ?: return
        viewModelScope.launch {
            bus.mutations.collect { mutation ->
                applyMediaUiMutation(mutation)
            }
        }
    }

    private fun applyMediaUiMutation(mutation: MediaUiMutation) {
        when (mutation) {
            is MediaUiMutation.Deleted -> {
                _uiState.value = _uiState.value.copy(
                    months = _uiState.value.months.removePhotos(mutation.photos)
                )
            }
            is MediaUiMutation.FavoriteChanged -> {
                _uiState.value = _uiState.value.copy(
                    months = _uiState.value.months.updateFavorite(
                        mutation.photos,
                        mutation.isFavorite
                    )
                )
            }
            is MediaUiMutation.HideChanged -> {
                _uiState.value = _uiState.value.copy(
                    months = if (mutation.isHide) {
                        _uiState.value.months.upsertPhotos(
                            mutation.photos.map { it.copy(isHide = true) },
                            ::buildMonthGroups
                        )
                    } else {
                        _uiState.value.months.removePhotos(mutation.photos)
                    }
                )
            }
            is MediaUiMutation.PersonRenamed -> Unit
        }
    }

    fun acknowledgeIntro() {
        _uiState.value = _uiState.value.copy(showIntro = false)
        viewModelScope.launch {
            prefsManager.setPrivacyIntroShown(true)
        }
    }

    fun updatePassword(value: String) {
        _uiState.value = _uiState.value.copy(password = value, error = null)
    }

    fun updateLocalPin(value: String) {
        val digits = value.filter { it.isDigit() }.take(6)
        _uiState.value = _uiState.value.copy(localPin = digits, error = null)
    }

    fun updateSetupPin(value: String) {
        val digits = value.filter { it.isDigit() }.take(6)
        _uiState.value = _uiState.value.copy(setupPin = digits, error = null)
    }

    fun updateSetupPinConfirm(value: String) {
        val digits = value.filter { it.isDigit() }.take(6)
        _uiState.value = _uiState.value.copy(setupPinConfirm = digits, error = null)
    }

    fun unlock() {
        unlockWithPassword()
    }

    fun unlockWithPassword() {
        val password = _uiState.value.password.trim()
        if (password.isBlank()) {
            _uiState.value = _uiState.value.copy(error = UiText.StringResource(R.string.private_album_password_required))
            return
        }
        loadHidden(password)
    }

    fun refresh() {
        val password = sessionPassword.takeIf { it.isNotBlank() }
            ?: prefsManager.getPasswordSync().trim().takeIf { it.isNotBlank() }
        if (password != null) {
            loadHidden(password)
        } else {
            loadCachedHidden()
        }
    }

    private fun loadHidden(password: String) {
        _uiState.value = _uiState.value.copy(isLoading = true, error = null)
        viewModelScope.launch {
            val result = galleryRepository.getHiddenFiles(password)
            result.fold(
                onSuccess = { photos ->
                    sessionPassword = password
                    _uiState.value = _uiState.value.copy(
                        isLocked = false,
                        password = "",
                        isLoading = false,
                        months = buildMonthGroups(photos.map { it.toCloudOnlyUnifiedPhotoItem().copy(isHide = true) }),
                        showSetupPrompt = !_uiState.value.localLockMode.isConfigured()
                    )
                },
                onFailure = { error ->
                    val cached = galleryRepository.getCachedHiddenFiles()
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        months = if (cached.isNotEmpty()) {
                            buildMonthGroups(cached.map { it.toCloudOnlyUnifiedPhotoItem().copy(isHide = true) })
                        } else {
                            _uiState.value.months
                        },
                        error = UiText.StringResource(
                            R.string.private_album_unlock_failed,
                            error.message.orEmpty()
                        )
                    )
                }
            )
        }
    }

    fun beginPinSetup() {
        _uiState.value = _uiState.value.copy(
            showSetupPrompt = false,
            setupMode = PrivacyLockMode.PIN,
            setupPin = "",
            setupPinConfirm = "",
            localPin = "",
            error = null
        )
    }

    fun beginPatternSetup() {
        _uiState.value = _uiState.value.copy(
            showSetupPrompt = false,
            setupMode = PrivacyLockMode.PATTERN,
            setupPin = "",
            setupPinConfirm = "",
            localPin = "",
            error = null
        )
    }

    fun cancelSetup() {
        _uiState.value = _uiState.value.copy(showSetupPrompt = true, setupMode = null, error = null)
    }

    fun savePinLock() {
        val first = _uiState.value.setupPin.trim()
        val second = _uiState.value.setupPinConfirm.trim()
        if (first.length != 6 || !first.all { it.isDigit() }) {
            _uiState.value = _uiState.value.copy(error = UiText.StringResource(R.string.private_album_pin_invalid))
            return
        }
        if (first != second) {
            _uiState.value = _uiState.value.copy(
                setupPinConfirm = "",
                error = UiText.StringResource(R.string.private_album_pin_mismatch)
            )
            return
        }
        persistLocalLock(PrivacyLockMode.PIN, first)
    }

    fun savePatternLock(pattern: List<Int>) {
        if (pattern.size < 4) {
            _uiState.value = _uiState.value.copy(error = UiText.StringResource(R.string.private_album_pattern_invalid))
            return
        }
        persistLocalLock(PrivacyLockMode.PATTERN, pattern.joinToString("-"))
    }

    fun unlockWithPattern(pattern: List<Int>) {
        if (pattern.size < 4) {
            _uiState.value = _uiState.value.copy(error = UiText.StringResource(R.string.private_album_pattern_invalid))
            return
        }
        unlockWithLocalSecret(pattern.joinToString("-"))
    }

    fun unlockWithPin() {
        val pin = _uiState.value.localPin.trim()
        if (pin.length != 6 || !pin.all { it.isDigit() }) {
            _uiState.value = _uiState.value.copy(error = UiText.StringResource(R.string.private_album_pin_invalid))
            return
        }
        unlockWithLocalSecret(pin)
    }

    private fun unlockWithLocalSecret(secret: String) {
        _uiState.value = _uiState.value.copy(isLoading = true, error = null)
        viewModelScope.launch {
            if (!prefsManager.verifyPrivacyLockSecret(secret)) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    localPin = "",
                    error = UiText.StringResource(R.string.private_album_lock_wrong)
                )
                return@launch
            }

            val password = prefsManager.getPasswordSync().trim()
            if (password.isNotBlank()) {
                val result = galleryRepository.getHiddenFiles(password)
                result.getOrNull()?.let { photos ->
                    sessionPassword = password
                    _uiState.value = _uiState.value.copy(
                        isLocked = false,
                        isLoading = false,
                        password = "",
                        localPin = "",
                        months = buildMonthGroups(photos.map { it.toCloudOnlyUnifiedPhotoItem().copy(isHide = true) }),
                        error = null
                    )
                    return@launch
                }
            }

            openCachedHidden()
        }
    }

    private fun unlockWithCachedHidden() {
        _uiState.value = _uiState.value.copy(isLoading = true, error = null)
        viewModelScope.launch {
            openCachedHidden()
        }
    }

    private suspend fun openCachedHidden() {
        val cached = galleryRepository.getCachedHiddenFiles().map { it.toCloudOnlyUnifiedPhotoItem().copy(isHide = true) }
        _uiState.value = _uiState.value.copy(
            isLocked = false,
            isLoading = false,
            password = "",
            localPin = "",
            months = buildMonthGroups(cached),
            error = null
        )
    }

    private fun loadCachedHidden() {
        unlockWithCachedHidden()
    }

    private fun persistLocalLock(mode: PrivacyLockMode, secret: String) {
        _uiState.value = _uiState.value.copy(isLoading = true, error = null)
        viewModelScope.launch {
            prefsManager.savePrivacyLock(mode, secret)
            _uiState.value = _uiState.value.copy(
                isLoading = false,
                setupMode = null,
                localLockMode = mode,
                setupPin = "",
                setupPinConfirm = "",
                showSetupPrompt = false,
                toastMessage = UiText.StringResource(R.string.private_album_setup_complete)
            )
        }
    }

    fun lock() {
        sessionPassword = ""
        selectionManager.clearSelection()
        _uiState.value = _uiState.value.copy(
            isLocked = true,
            password = "",
            localPin = "",
            setupPin = "",
            setupPinConfirm = "",
            months = emptyList(),
            error = null,
            setupMode = null,
            showSetupPrompt = false
        )
    }

    fun selectAll() {
        selectionManager.selectAll(getAllLoadedPhotos().map { it.id })
    }

    fun unhideSelected() {
        val selectedIds = selectionManager.selectedPhotoIds.value
        if (selectedIds.isEmpty()) return
        val selectedPhotos = getAllLoadedPhotos().filter { it.id in selectedIds }
        if (selectedPhotos.isEmpty()) return

        _uiState.value = _uiState.value.copy(
            months = _uiState.value.months.removePhotos(selectedPhotos)
        )
        selectionManager.clearSelection()

        viewModelScope.launch {
            serverOpTaskRepository.enqueueHides(selectedPhotos, isHide = false)
            if (selectedPhotos.any { it.cloudId != null }) {
                BackupScheduler.triggerServerOpWork(appContext)
            }
        }
    }

    fun shareSelected(context: Context) {
        val ids = selectionManager.selectedPhotoIds.value
        if (ids.isEmpty()) return
        shareManager.sharePhotos(context, getAllLoadedPhotos().filter { it.id in ids })
    }

    fun deleteSelected() {
        val selectedIds = selectionManager.selectedPhotoIds.value
        val selectedPhotos = getAllLoadedPhotos().filter { it.id in selectedIds }
        selectionManager.deleteSelected(
            photos = getAllLoadedPhotos(),
            onDeletePhotos = { photos -> galleryRepository.deletePhotos(photos) }
        ) {
            _uiState.value = _uiState.value.copy(
                months = _uiState.value.months.removePhotos(selectedPhotos)
            )
        }
    }

    fun updateColumnCount(newCount: Int) {
        val clamped = newCount.coerceIn(2, 6)
        if (clamped != _uiState.value.columnCount) {
            _uiState.value = _uiState.value.copy(columnCount = clamped)
        }
    }

    fun clearToastMessage() {
        _uiState.value = _uiState.value.copy(toastMessage = null)
    }

    fun setError(error: UiText?) {
        _uiState.value = _uiState.value.copy(error = error)
    }

    fun getThumbUrl(photo: UnifiedPhotoItem): String {
        return ThumbnailUrlResolver.resolve(
            photo = photo,
            galleryRepository = galleryRepository,
            imageCloudUrl = { galleryRepository.getThumbUrlByMd5(it.md5) }
        )
    }

    fun getAllLoadedPhotos(): List<UnifiedPhotoItem> {
        return _uiState.value.months.flatMap { month -> month.days.flatMap { it.photos } }
    }

    private fun buildMonthGroups(photos: List<UnifiedPhotoItem>): List<MonthGroup> {
        return photos
            .sortedForTimeline()
            .groupBy { it.mtime.take(7) }
            .toSortedMap(compareByDescending { it })
            .map { (yearMonth, monthPhotos) ->
                MonthGroup(
                    yearMonth = yearMonth,
                    displayTitle = formatYearMonth(yearMonth),
                    totalCount = monthPhotos.size,
                    days = monthPhotos
                        .sortedForTimeline()
                        .groupBy { it.mtime.take(10) }
                        .toSortedMap(compareByDescending { it })
                        .map { (date, dayPhotos) ->
                            DayGroup(date = date, photos = dayPhotos)
                        },
                    isLoaded = true
                )
            }
    }

    private fun formatYearMonth(ym: String): UiText {
        val parts = ym.split("-")
        return if (parts.size >= 2) {
            UiText.StringResource(R.string.year_month_format, parts[0], parts[1].toIntOrNull() ?: 1)
        } else {
            UiText.DynamicString(ym)
        }
    }

    private fun PrivacyLockMode.isConfigured(): Boolean = this != PrivacyLockMode.NONE

    class Factory(
        private val galleryRepository: GalleryRepository,
        private val prefsManager: PrefsManager,
        private val serverOpTaskRepository: ServerOpTaskRepository,
        private val appContext: Context,
        private val mediaUiMutationBus: MediaUiMutationBus? = null
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return PrivateAlbumViewModel(
                galleryRepository,
                prefsManager,
                serverOpTaskRepository,
                appContext,
                mediaUiMutationBus
            ) as T
        }
    }
}
