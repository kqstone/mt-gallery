package com.kqstone.mtphotos.ui.gallery

import androidx.compose.foundation.Canvas
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.kqstone.mtphotos.R
import com.kqstone.mtphotos.data.local.PrivacyLockMode
import com.kqstone.mtphotos.data.model.UnifiedPhotoItem
import com.kqstone.mtphotos.ui.media.MediaGridHost
import com.kqstone.mtphotos.ui.util.UiText
import com.kqstone.mtphotos.ui.util.BackTitleTopBar
import com.kqstone.mtphotos.ui.util.ToastMessageEffect
import com.kqstone.mtphotos.ui.util.hazeContentSource
import com.kqstone.mtphotos.ui.util.rememberScrollAlpha
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sqrt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrivateAlbumScreen(
    viewModel: PrivateAlbumViewModel,
    onPhotoClick: (UnifiedPhotoItem, List<UnifiedPhotoItem>) -> Unit,
    onBack: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val selectedIds by viewModel.selectionManager.selectedPhotoIds.collectAsState()
    val isSelectionMode = selectedIds.isNotEmpty()
    val context = LocalContext.current
    var usePasswordFallback by remember { mutableStateOf(false) }

    fun leavePrivateAlbum() {
        viewModel.lock()
        onBack()
    }

    ToastMessageEffect(
        message = uiState.toastMessage,
        onConsumed = viewModel::clearToastMessage
    )

    LaunchedEffect(uiState.isLocked) {
        if (!uiState.isLocked) {
            usePasswordFallback = false
        }
    }

    BackHandler(enabled = true) {
        when {
            uiState.showSetupPrompt || uiState.setupMode != null -> Unit
            isSelectionMode -> viewModel.selectionManager.clearSelection()
            usePasswordFallback -> usePasswordFallback = false
            else -> leavePrivateAlbum()
        }
    }

    val gridState = rememberLazyGridState()
    val scrollState = rememberScrollAlpha(
        firstVisibleItemIndex = { gridState.firstVisibleItemIndex },
        firstVisibleItemScrollOffset = { gridState.firstVisibleItemScrollOffset }
    )

    Box(modifier = Modifier.fillMaxSize()) {
        if (uiState.isLocked) {
            when {
                uiState.localLockMode == PrivacyLockMode.PIN && !usePasswordFallback -> {
                    PrivateAlbumPinUnlockContent(
                        pin = uiState.localPin,
                        isLoading = uiState.isLoading,
                        error = uiState.error?.asString(),
                        onPinChange = viewModel::updateLocalPin,
                        onUnlock = viewModel::unlockWithPin,
                        onUsePasswordFallback = { usePasswordFallback = true },
                        modifier = Modifier.fillMaxSize()
                    )
                }
                uiState.localLockMode == PrivacyLockMode.PATTERN && !usePasswordFallback -> {
                    PrivateAlbumPatternUnlockContent(
                        isLoading = uiState.isLoading,
                        error = uiState.error?.asString(),
                        onPatternUnlock = viewModel::unlockWithPattern,
                        onUsePasswordFallback = { usePasswordFallback = true },
                        modifier = Modifier.fillMaxSize()
                    )
                }
                else -> {
                    PrivateAlbumUnlockContent(
                        password = uiState.password,
                        isLoading = uiState.isLoading,
                        error = uiState.error?.asString(),
                        onPasswordChange = viewModel::updatePassword,
                        onUnlock = viewModel::unlockWithPassword,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 24.dp)
                    )
                }
            }
        } else {
            val pullRefreshState = rememberPullToRefreshState()
            MediaGridHost(
                months = uiState.months,
                columnCount = uiState.columnCount,
                selectedPhotoIds = selectedIds,
                isSelectionMode = isSelectionMode,
                selectionManager = viewModel.selectionManager,
                getThumbUrl = viewModel::getThumbUrl,
                onPhotoClick = { photo -> onPhotoClick(photo, viewModel.getAllLoadedPhotos()) },
                onColumnCountChange = viewModel::updateColumnCount,
                onSelectAll = viewModel::selectAll,
                onDeleteSelected = viewModel::deleteSelected,
                onClearSelection = { viewModel.selectionManager.clearSelection() },
                normalTopBar = {
                    BackTitleTopBar(
                        title = stringResource(R.string.private_album_title),
                        onBack = { leavePrivateAlbum() },
                        scrollAlpha = scrollState.scrollAlpha
                    )
                },
                isEmpty = !uiState.isLoading && uiState.months.isEmpty(),
                emptyContent = {
                    Text(
                        text = stringResource(R.string.private_album_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                },
                contentTopPadding = scrollState.topBarHeight,
                stateKey = "gallery:private",
                gridState = gridState,
                contentPadding = PaddingValues(
                    top = scrollState.topBarHeight,
                    bottom = 80.dp,
                    start = 1.dp,
                    end = 1.dp
                ),
                gridContainer = { gridContent ->
                    PullToRefreshBox(
                        isRefreshing = uiState.isLoading,
                        onRefresh = viewModel::refresh,
                        modifier = Modifier.fillMaxSize().hazeContentSource(),
                        state = pullRefreshState,
                        indicator = {
                            PullToRefreshDefaults.Indicator(
                                modifier = Modifier
                                    .align(Alignment.TopCenter)
                                    .padding(top = scrollState.topBarHeight),
                                isRefreshing = uiState.isLoading,
                                state = pullRefreshState
                            )
                        }
                    ) {
                        gridContent()
                    }
                },
                shareManager = viewModel.shareManager,
                scrollAlpha = if (isSelectionMode) 1f else scrollState.scrollAlpha,
                showTopBar = !uiState.showSetupPrompt && uiState.setupMode == null,
                handleSelectionBack = false,
                selectionActions = listOf(
                    MediaSelectionAction(MediaSelectionActionType.SHARE) { viewModel.shareSelected(context) },
                    MediaSelectionAction(MediaSelectionActionType.UNHIDE) { viewModel.unhideSelected() }
                )
            )
        }

        if (uiState.showSetupPrompt) {
            PrivateAlbumSetupChoiceScreen(
                onChoosePin = viewModel::beginPinSetup,
                onChoosePattern = viewModel::beginPatternSetup
            )
        }

        when (uiState.setupMode) {
            PrivacyLockMode.PIN -> {
                PrivateAlbumPinSetupScreen(
                    pin = uiState.setupPin,
                    pinConfirm = uiState.setupPinConfirm,
                    isSaving = uiState.isLoading,
                    error = uiState.error?.asString(),
                    onPinChange = viewModel::updateSetupPin,
                    onPinConfirmChange = viewModel::updateSetupPinConfirm,
                    onSave = viewModel::savePinLock
                )
            }
            PrivacyLockMode.PATTERN -> {
                PrivateAlbumPatternSetupScreen(
                    isSaving = uiState.isLoading,
                    error = uiState.error?.asString(),
                    onSave = viewModel::savePatternLock,
                    onError = viewModel::setError
                )
            }
            else -> Unit
        }

    }

    if (uiState.showIntro) {
        AlertDialog(
            onDismissRequest = viewModel::acknowledgeIntro,
            title = { Text(stringResource(R.string.private_album_intro_title)) },
            text = { Text(stringResource(R.string.private_album_intro_message)) },
            confirmButton = {
                TextButton(onClick = viewModel::acknowledgeIntro) {
                    Text(stringResource(R.string.ok))
                }
            }
        )
    }

}

@Composable
private fun PrivateAlbumUnlockContent(
    password: String,
    isLoading: Boolean,
    error: String?,
    onPasswordChange: (String) -> Unit,
    onUnlock: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 24.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = stringResource(R.string.private_album_password_prompt),
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(modifier = Modifier.height(16.dp))
            OutlinedTextField(
                value = password,
                onValueChange = onPasswordChange,
                label = { Text(stringResource(R.string.password)) },
                singleLine = true,
                enabled = !isLoading,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    imeAction = ImeAction.Done
                ),
                keyboardActions = KeyboardActions(onDone = { onUnlock() }),
                modifier = Modifier.fillMaxWidth()
            )
            if (error != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = error,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall
                )
            }
            Spacer(modifier = Modifier.height(16.dp))
            Button(
                enabled = !isLoading,
                onClick = onUnlock,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (isLoading) {
                    CircularProgressIndicator()
                } else {
                    Text(stringResource(R.string.private_album_unlock))
                }
            }
            if (!isLoading) {
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = stringResource(R.string.private_album_use_current_password),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.clickable { onUnlock() }
                )
            }
        }
    }
}

@Composable
private fun PrivateAlbumPinUnlockContent(
    pin: String,
    isLoading: Boolean,
    error: String?,
    onPinChange: (String) -> Unit,
    onUnlock: () -> Unit,
    onUsePasswordFallback: () -> Unit,
    modifier: Modifier = Modifier
) {
    LaunchedEffect(pin, isLoading) {
        if (!isLoading && pin.length == 6) {
            onUnlock()
        }
    }

    FullScreenPinContent(
        title = stringResource(R.string.private_album_pin_prompt),
        pinLength = pin.length,
        isLoading = isLoading,
        error = error,
        onDigit = { digit ->
            if (!isLoading && pin.length < 6) {
                onPinChange(pin + digit)
            }
        },
        onBackspace = {
            if (!isLoading && pin.isNotEmpty()) {
                onPinChange(pin.dropLast(1))
            }
        },
        footer = {
            if (!isLoading) {
                Text(
                    text = stringResource(R.string.private_album_use_password_fallback),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.clickable { onUsePasswordFallback() }
                )
            }
        },
        modifier = modifier
    )
}

@Composable
private fun PrivateAlbumPatternUnlockContent(
    isLoading: Boolean,
    error: String?,
    onPatternUnlock: (List<Int>) -> Unit,
    onUsePasswordFallback: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 24.dp),
        contentAlignment = Alignment.Center
    ) {
        TouchBlocker()
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceEvenly
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    imageVector = Icons.Filled.Lock,
                    contentDescription = null,
                    modifier = Modifier.size(48.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = stringResource(R.string.private_album_pattern_prompt),
                    style = MaterialTheme.typography.titleMedium
                )
            }
            PatternLockPad(
                enabled = !isLoading,
                resetSeed = 0,
                onComplete = onPatternUnlock,
                modifier = Modifier
                    .fillMaxWidth(0.94f)
                    .widthIn(max = 520.dp)
            )
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                if (error != null) {
                    Text(
                        text = error,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                if (isLoading) {
                    Spacer(modifier = Modifier.height(16.dp))
                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                }
                if (!isLoading) {
                    Spacer(modifier = Modifier.height(24.dp))
                    Text(
                        text = stringResource(R.string.private_album_use_password_fallback),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.clickable { onUsePasswordFallback() }
                    )
                }
            }
        }
    }
}

@Composable
private fun PrivateAlbumSetupChoiceScreen(
    onChoosePin: () -> Unit,
    onChoosePattern: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 32.dp),
        contentAlignment = Alignment.Center
    ) {
        TouchBlocker()
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = stringResource(R.string.private_album_setup_title),
                style = MaterialTheme.typography.headlineSmall
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = stringResource(R.string.private_album_setup_message),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(48.dp))
            Button(
                onClick = onChoosePin,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
            ) {
                Text(stringResource(R.string.private_album_set_pin))
            }
            Spacer(modifier = Modifier.height(16.dp))
            TextButton(
                onClick = onChoosePattern,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
            ) {
                Text(stringResource(R.string.private_album_set_gesture))
            }
        }
    }
}

@Composable
private fun PrivateAlbumPinSetupScreen(
    pin: String,
    pinConfirm: String,
    isSaving: Boolean,
    error: String?,
    onPinChange: (String) -> Unit,
    onPinConfirmChange: (String) -> Unit,
    onSave: () -> Unit
) {
    val isConfirming = pin.length == 6
    val activePin = if (isConfirming) pinConfirm else pin
    val title = if (isConfirming) {
        stringResource(R.string.private_album_pin_confirm_prompt)
    } else {
        stringResource(R.string.private_album_pin_prompt)
    }

    LaunchedEffect(pinConfirm, isSaving) {
        if (!isSaving && pin.length == 6 && pinConfirm.length == 6) {
            onSave()
        }
    }

    FullScreenPinContent(
        title = title,
        pinLength = activePin.length,
        isLoading = isSaving,
        error = error,
        onDigit = { digit ->
            if (!isSaving) {
                if (pin.length < 6) {
                    onPinChange(pin + digit)
                } else if (pinConfirm.length < 6) {
                    onPinConfirmChange(pinConfirm + digit)
                }
            }
        },
        onBackspace = {
            if (!isSaving) {
                if (pinConfirm.isNotEmpty()) {
                    onPinConfirmChange(pinConfirm.dropLast(1))
                } else if (pin.isNotEmpty()) {
                    onPinChange(pin.dropLast(1))
                }
            }
        },
        modifier = Modifier.fillMaxSize()
    )
}

@Composable
private fun PrivateAlbumPatternSetupScreen(
    isSaving: Boolean,
    error: String?,
    onSave: (List<Int>) -> Unit,
    onError: (UiText?) -> Unit
) {
    var stage by remember { mutableStateOf(0) }
    var firstPattern by remember { mutableStateOf<List<Int>?>(null) }
    val prompt = if (stage == 0) {
        stringResource(R.string.private_album_pattern_prompt)
    } else {
        stringResource(R.string.private_album_pattern_confirm_prompt)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 24.dp),
        contentAlignment = Alignment.Center
    ) {
        TouchBlocker()
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceEvenly
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = stringResource(R.string.private_album_set_gesture),
                    style = MaterialTheme.typography.headlineSmall
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = prompt,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            PatternLockPad(
                enabled = !isSaving,
                resetSeed = stage,
                onComplete = { pattern ->
                    if (pattern.size < 4) {
                        onError(UiText.StringResource(R.string.private_album_pattern_invalid))
                        return@PatternLockPad
                    }
                    if (stage == 0) {
                        firstPattern = pattern
                        stage = 1
                        onError(null)
                    } else {
                        if (pattern == firstPattern) {
                            onSave(pattern)
                            onError(null)
                        } else {
                            onError(UiText.StringResource(R.string.private_album_pattern_mismatch))
                            firstPattern = null
                            stage = 0
                        }
                    }
                },
                modifier = Modifier
                    .fillMaxWidth(0.94f)
                    .widthIn(max = 520.dp)
            )
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                if (error != null) {
                    Text(
                        text = error,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                if (isSaving) {
                    Spacer(modifier = Modifier.height(16.dp))
                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                }
            }
        }
    }
}

@Composable
private fun FullScreenPinContent(
    title: String,
    pinLength: Int,
    isLoading: Boolean,
    error: String?,
    onDigit: (String) -> Unit,
    onBackspace: () -> Unit,
    modifier: Modifier = Modifier,
    footer: @Composable (() -> Unit)? = null
) {
    Box(
        modifier = modifier
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 24.dp),
        contentAlignment = Alignment.Center
    ) {
        TouchBlocker()
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceEvenly
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    imageVector = Icons.Filled.Lock,
                    contentDescription = null,
                    modifier = Modifier.size(48.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = title,
                    style = MaterialTheme.typography.headlineSmall
                )
            }
            PinDots(count = pinLength)
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                if (error != null) {
                    Text(
                        text = error,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                }
                if (isLoading) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                    Spacer(modifier = Modifier.height(16.dp))
                }
                PinNumberPad(
                    enabled = !isLoading,
                    onDigit = onDigit,
                    onBackspace = onBackspace,
                    modifier = Modifier.fillMaxWidth()
                )
                if (footer != null) {
                    Spacer(modifier = Modifier.height(20.dp))
                    footer()
                }
            }
        }
    }
}

@Composable
private fun BoxScope.TouchBlocker() {
    Box(
        modifier = Modifier
            .matchParentSize()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {}
            )
    )
}

@Composable
private fun PinDots(count: Int) {
    val filledColor = MaterialTheme.colorScheme.primary
    val unfilledColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
    Row(
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        repeat(6) { index ->
            Canvas(modifier = Modifier.size(16.dp)) {
                val isFilled = index < count
                val targetRadius = if (isFilled) size.minDimension / 2f else size.minDimension / 3.5f
                drawCircle(
                    color = if (isFilled) filledColor else unfilledColor,
                    radius = targetRadius
                )
            }
        }
    }
}

@Composable
private fun PinNumberPad(
    enabled: Boolean,
    onDigit: (String) -> Unit,
    onBackspace: () -> Unit,
    modifier: Modifier = Modifier
) {
    val rows = listOf(
        listOf("1", "2", "3"),
        listOf("4", "5", "6"),
        listOf("7", "8", "9"),
        listOf("", "0", "backspace")
    )
    BoxWithConstraints(modifier = modifier) {
        val keySize = (maxWidth / 4f).coerceIn(72.dp, 96.dp)
        val rowSpacing = (keySize / 5f).coerceIn(12.dp, 24.dp)
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(rowSpacing)
        ) {
            rows.forEach { row ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    row.forEach { key ->
                        when (key) {
                            "" -> Spacer(modifier = Modifier.size(keySize))
                            "backspace" -> Box(
                                modifier = Modifier
                                    .size(keySize)
                                    .clip(androidx.compose.foundation.shape.CircleShape)
                                    .clickable(enabled = enabled, onClick = onBackspace)
                                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.Backspace,
                                    contentDescription = stringResource(R.string.delete),
                                    tint = MaterialTheme.colorScheme.onSurface
                                )
                            }
                            else -> Box(
                                modifier = Modifier
                                    .size(keySize)
                                    .clip(androidx.compose.foundation.shape.CircleShape)
                                    .clickable(enabled = enabled, onClick = { onDigit(key) })
                                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = key,
                                    style = MaterialTheme.typography.headlineMedium,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PatternLockPad(
    enabled: Boolean,
    resetSeed: Int,
    onComplete: (List<Int>) -> Unit,
    modifier: Modifier = Modifier
) {
    val selectedNodes = remember(resetSeed) { mutableStateListOf<Int>() }
    var size by remember(resetSeed) { mutableStateOf(IntSize.Zero) }
    val centers = remember(size) { computePatternCenters(size) }
    val hitRadius = remember(size) {
        if (size.width <= 0 || size.height <= 0) 0f else min(size.width, size.height) / 6f
    }
    val primaryColor = MaterialTheme.colorScheme.primary
    val primarySoftColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
    val outlineColor = MaterialTheme.colorScheme.outline
    val surfaceVariantColor = MaterialTheme.colorScheme.surfaceVariant

    Box(
        modifier = modifier.pointerInput(resetSeed, enabled, centers) {
            if (!enabled) return@pointerInput
            detectDragGestures(
                onDragStart = { position ->
                    selectedNodes.clear()
                    addPatternNode(position, centers, hitRadius, selectedNodes)
                },
                onDrag = { change, _ ->
                    change.consume()
                    addPatternNode(change.position, centers, hitRadius, selectedNodes)
                },
                onDragEnd = {
                    onComplete(selectedNodes.toList())
                    selectedNodes.clear()
                },
                onDragCancel = {
                    selectedNodes.clear()
                }
            )
        }
            .aspectRatio(1f)
            .onSizeChanged { size = it },
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            if (centers.isNotEmpty()) {
                val patternSize = min(size.width, size.height).toFloat()
                val outerRadius = patternSize / 12f
                val centerRadius = patternSize / 28f
                val strokeWidth = patternSize / 80f
                if (selectedNodes.size > 1) {
                    val path = androidx.compose.ui.graphics.Path().apply {
                        moveTo(centers[selectedNodes.first()].x, centers[selectedNodes.first()].y)
                        selectedNodes.drop(1).forEach { index ->
                            lineTo(centers[index].x, centers[index].y)
                        }
                    }
                    drawPath(
                        path = path,
                        color = primaryColor,
                        style = androidx.compose.ui.graphics.drawscope.Stroke(width = strokeWidth)
                    )
                }
                centers.forEachIndexed { index, center ->
                    val selected = index in selectedNodes
                    drawCircle(
                        color = if (selected) primarySoftColor else surfaceVariantColor,
                        radius = outerRadius,
                        center = center
                    )
                    drawCircle(
                        color = if (selected) primaryColor else outlineColor,
                        radius = centerRadius,
                        center = center
                    )
                }
            }
        }
    }
}

private fun computePatternCenters(size: IntSize): List<Offset> {
    if (size.width <= 0 || size.height <= 0) return emptyList()
    val cellWidth = size.width / 3f
    val cellHeight = size.height / 3f
    return buildList {
        for (row in 0 until 3) {
            for (col in 0 until 3) {
                add(Offset(cellWidth * col + cellWidth / 2f, cellHeight * row + cellHeight / 2f))
            }
        }
    }
}

private fun addPatternNode(
    position: Offset,
    centers: List<Offset>,
    hitRadius: Float,
    selectedNodes: MutableList<Int>
) {
    val hitIndex = centers.indexOfFirst { center ->
        distance(center, position) <= hitRadius
    }
    if (hitIndex < 0) return
    if (selectedNodes.isEmpty()) {
        selectedNodes.add(hitIndex)
        return
    }
    val last = selectedNodes.last()
    if (last == hitIndex) return
    val skipped = skippedPatternNodes(last, hitIndex)
    skipped.forEach { if (it !in selectedNodes) selectedNodes.add(it) }
    if (hitIndex !in selectedNodes) {
        selectedNodes.add(hitIndex)
    }
}

private fun skippedPatternNodes(from: Int, to: Int): List<Int> {
    val fromRow = from / 3
    val fromCol = from % 3
    val toRow = to / 3
    val toCol = to % 3
    val dr = toRow - fromRow
    val dc = toCol - fromCol
    return when {
        abs(dr) == 2 && dc == 0 -> listOf((fromRow + dr / 2) * 3 + fromCol)
        dr == 0 && abs(dc) == 2 -> listOf(fromRow * 3 + fromCol + dc / 2)
        abs(dr) == 2 && abs(dc) == 2 -> listOf((fromRow + dr / 2) * 3 + (fromCol + dc / 2))
        else -> emptyList()
    }
}

private fun distance(a: Offset, b: Offset): Float {
    val dx = a.x - b.x
    val dy = a.y - b.y
    return sqrt(dx * dx + dy * dy)
}
