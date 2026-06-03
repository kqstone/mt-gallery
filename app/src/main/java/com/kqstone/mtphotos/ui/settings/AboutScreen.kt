package com.kqstone.mtphotos.ui.settings

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kqstone.mtphotos.R
import com.kqstone.mtphotos.ui.util.UiText

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(
    viewModel: AboutViewModel,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsState()
    val scrollState = rememberScrollState()

    // Initialize app version dynamically
    LaunchedEffect(Unit) {
        viewModel.initVersion(context)
    }

    // Auto-launch package installer when download is successful
    LaunchedEffect(uiState.downloadStatus) {
        val status = uiState.downloadStatus
        if (status is DownloadStatus.Success) {
            try {
                val apkFile = status.apkFile
                val apkUri = androidx.core.content.FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    apkFile
                )
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(apkUri, "application/vnd.android.package-archive")
                    flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(intent)
                viewModel.resetDownloadStatus()
            } catch (e: Exception) {
                Toast.makeText(
                    context,
                    context.getString(R.string.cannot_launch_installer) + ": ${e.message}",
                    Toast.LENGTH_LONG
                ).show()
                viewModel.resetDownloadStatus()
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.about_title), style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground
                )
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(scrollState)
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(28.dp))

            // Premium Logo Block
            Box(
                modifier = Modifier
                    .size(100.dp)
                    .background(
                        brush = Brush.linearGradient(
                            colors = listOf(
                                MaterialTheme.colorScheme.primary,
                                MaterialTheme.colorScheme.secondary
                            )
                        ),
                        shape = CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.PhotoLibrary,
                    contentDescription = stringResource(R.string.app_name),
                    tint = Color.White,
                    modifier = Modifier.size(54.dp)
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "MT Gallery",
                style = MaterialTheme.typography.headlineMedium.copy(
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.5.sp
                ),
                color = MaterialTheme.colorScheme.onBackground
            )

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = stringResource(R.string.current_version, uiState.currentVersion),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
            )

            Spacer(modifier = Modifier.height(28.dp))

            // App Description Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                ),
                shape = RoundedCornerShape(20.dp)
            ) {
                Column(
                    modifier = Modifier.padding(18.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Info,
                            contentDescription = stringResource(R.string.about_desc_title),
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.about_desc_title),
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = stringResource(R.string.about_desc_content),
                        style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 22.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Author and GitHub Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                ),
                shape = RoundedCornerShape(20.dp)
            ) {
                Column {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 18.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Person,
                            contentDescription = stringResource(R.string.author),
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = stringResource(R.string.author),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            text = "kqstone",
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(1.dp)
                            .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                    )

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                try {
                                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/kqstone/mt-gallery"))
                                    context.startActivity(intent)
                                } catch (e: Exception) {
                                    Toast.makeText(context, context.getString(R.string.cannot_open_link), Toast.LENGTH_SHORT).show()
                                }
                            }
                            .padding(horizontal = 18.dp, vertical = 16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Code,
                            contentDescription = "GitHub",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = stringResource(R.string.github_repo),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            text = stringResource(R.string.go_to_project),
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.primary
                            )
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(28.dp))

            // Check updates Actions
            Button(
                onClick = { viewModel.checkForUpdates() },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                shape = RoundedCornerShape(14.dp),
                enabled = uiState.checkStatus !is CheckStatus.Checking && uiState.downloadStatus !is DownloadStatus.Downloading,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                )
            ) {
                if (uiState.checkStatus is CheckStatus.Checking) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = MaterialTheme.colorScheme.onPrimary,
                        strokeWidth = 2.dp
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.checking_updates))
                } else {
                    Text(stringResource(R.string.check_updates), fontWeight = FontWeight.SemiBold)
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Status Messages or Actions
            AnimatedVisibility(
                visible = uiState.checkStatus is CheckStatus.UpToDate ||
                        uiState.checkStatus is CheckStatus.Error ||
                        uiState.checkStatus is CheckStatus.NewVersionAvailable ||
                        uiState.downloadStatus is DownloadStatus.Downloading ||
                        uiState.downloadStatus is DownloadStatus.Error,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    when (val checkStatus = uiState.checkStatus) {
                        is CheckStatus.UpToDate -> {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 8.dp),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.CheckCircle,
                                    contentDescription = stringResource(R.string.up_to_date),
                                    tint = Color(0xFF4CAF50),
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = stringResource(R.string.up_to_date),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = Color(0xFF4CAF50)
                                )
                            }
                        }

                        is CheckStatus.Error -> {
                            Text(
                                text = checkStatus.message.asString(),
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(vertical = 8.dp),
                                textAlign = TextAlign.Center
                            )
                        }

                        is CheckStatus.NewVersionAvailable -> {
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f)
                                ),
                                shape = RoundedCornerShape(20.dp)
                            ) {
                                Column(
                                    modifier = Modifier.padding(18.dp)
                                ) {
                                    Text(
                                        text = stringResource(R.string.new_version_available, checkStatus.latestVersion),
                                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                        color = MaterialTheme.colorScheme.onSecondaryContainer
                                    )
                                    if (checkStatus.body.isNotBlank()) {
                                        Spacer(modifier = Modifier.height(8.dp))
                                        Text(
                                            text = checkStatus.body,
                                            style = MaterialTheme.typography.bodySmall.copy(lineHeight = 18.sp),
                                            color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.85f),
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                    }
                                    Spacer(modifier = Modifier.height(16.dp))

                                    val downloadStatus = uiState.downloadStatus
                                    if (downloadStatus is DownloadStatus.Downloading) {
                                        Column {
                                            LinearProgressIndicator(
                                                progress = { downloadStatus.progress },
                                                modifier = Modifier.fillMaxWidth(),
                                                color = MaterialTheme.colorScheme.primary,
                                                trackColor = MaterialTheme.colorScheme.surfaceVariant
                                            )
                                            Spacer(modifier = Modifier.height(6.dp))
                                            Text(
                                                text = stringResource(R.string.downloading, downloadStatus.progress * 100f),
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSecondaryContainer
                                            )
                                        }
                                    } else {
                                        Button(
                                            onClick = { viewModel.downloadAndInstallApk(context, checkStatus.downloadUrl) },
                                            modifier = Modifier.fillMaxWidth(),
                                            shape = RoundedCornerShape(10.dp)
                                        ) {
                                            Text(stringResource(R.string.download_and_install), fontWeight = FontWeight.SemiBold)
                                        }
                                    }

                                    if (downloadStatus is DownloadStatus.Error) {
                                        Spacer(modifier = Modifier.height(8.dp))
                                        Text(
                                            text = downloadStatus.message.asString(),
                                            color = MaterialTheme.colorScheme.error,
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                    }
                                }
                            }
                        }
                        else -> {}
                    }
                }
            }

            Spacer(modifier = Modifier.height(40.dp))
        }
    }
}
