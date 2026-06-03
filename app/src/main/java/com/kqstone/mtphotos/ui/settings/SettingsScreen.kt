package com.kqstone.mtphotos.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.kqstone.mtphotos.R
import com.kqstone.mtphotos.ui.util.bounceClick
import com.kqstone.mtphotos.ui.util.UiText

@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onConnected: () -> Unit,
    reconnectMode: Boolean = false,
    onBack: (() -> Unit)? = null
) {
    val uiState by viewModel.uiState.collectAsState()

    LaunchedEffect(uiState.isLoggedIn) {
        if (uiState.isLoggedIn) onConnected()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        MaterialTheme.colorScheme.background,
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.2f),
                        MaterialTheme.colorScheme.background
                    )
                )
            ),
        contentAlignment = Alignment.Center
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            shape = RoundedCornerShape(28.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            ),
            border = BorderStroke(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp, vertical = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                if (onBack != null) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = onBack) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(R.string.back)
                            )
                        }
                        Text(
                            text = stringResource(R.string.server_connection),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                }

                // Beautiful Brand Logo Header
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .background(
                            brush = Brush.linearGradient(
                                colors = listOf(
                                    MaterialTheme.colorScheme.primary,
                                    MaterialTheme.colorScheme.tertiary
                                )
                            ),
                            shape = CircleShape
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.PhotoLibrary,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(32.dp)
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = "MT Gallery",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )

                Text(
                    text = if (reconnectMode) stringResource(R.string.update_server_address) else stringResource(R.string.connect_to_private_server),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    modifier = Modifier.padding(top = 4.dp, bottom = 28.dp)
                )

                ServerUrlField(
                    label = stringResource(R.string.primary_server_url),
                    value = uiState.primaryServerUrl,
                    onValueChange = viewModel::onPrimaryServerUrlChange,
                    selected = uiState.activeServerUrl == uiState.primaryServerUrl ||
                        uiState.activeServerUrl.isBlank(),
                    onSelect = { viewModel.selectActiveUrl(ServerUrlSlot.PRIMARY) },
                    testMessage = uiState.primaryUrlTestMessage,
                    isTesting = uiState.testingUrlSlot == ServerUrlSlot.PRIMARY,
                    onTest = { viewModel.testUrl(ServerUrlSlot.PRIMARY) }
                )

                Spacer(modifier = Modifier.height(16.dp))

                ServerUrlField(
                    label = stringResource(R.string.secondary_server_url),
                    value = uiState.secondaryServerUrl,
                    onValueChange = viewModel::onSecondaryServerUrlChange,
                    selected = uiState.secondaryServerUrl.isNotBlank() &&
                        uiState.activeServerUrl == uiState.secondaryServerUrl,
                    onSelect = { viewModel.selectActiveUrl(ServerUrlSlot.SECONDARY) },
                    testMessage = uiState.secondaryUrlTestMessage,
                    isTesting = uiState.testingUrlSlot == ServerUrlSlot.SECONDARY,
                    onTest = { viewModel.testUrl(ServerUrlSlot.SECONDARY) }
                )

                Spacer(modifier = Modifier.height(16.dp))

                OutlinedTextField(
                    value = uiState.username,
                    onValueChange = viewModel::onUsernameChange,
                    label = { Text(stringResource(R.string.username)) },
                    enabled = uiState.credentialsEditable,
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.Person,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.8f)
                        )
                    },
                    shape = RoundedCornerShape(16.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                    ),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(16.dp))

                if (uiState.credentialsEditable) {
                    OutlinedTextField(
                        value = uiState.password,
                        onValueChange = viewModel::onPasswordChange,
                        label = { Text(stringResource(R.string.password)) },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Default.Lock,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.8f)
                            )
                        },
                        shape = RoundedCornerShape(16.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = MaterialTheme.colorScheme.primary,
                            unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                        ),
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        modifier = Modifier.fillMaxWidth()
                    )
                } else {
                    Text(
                        text = stringResource(R.string.saved_password_notice),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                Spacer(modifier = Modifier.height(24.dp))

                if (uiState.error != null) {
                    Text(
                        text = uiState.error!!.asString(),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(bottom = 16.dp)
                    )
                }

                // Premium tactile action button
                Button(
                    onClick = viewModel::connect,
                    enabled = !uiState.isLoading,
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = Color.White
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                        .bounceClick(onClick = viewModel::connect)
                ) {
                    if (uiState.isLoading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            color = Color.White,
                            strokeWidth = 2.5.dp
                        )
                    } else {
                        Text(
                            text = if (reconnectMode) stringResource(R.string.save_and_relogin) else stringResource(R.string.connect_now),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ServerUrlField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    selected: Boolean,
    onSelect: () -> Unit,
    testMessage: UiText?,
    isTesting: Boolean,
    onTest: () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            label = { Text(label) },
            placeholder = { Text("http://192.168.1.100:8080") },
            leadingIcon = {
                Icon(
                    imageVector = Icons.Default.Link,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.8f)
                )
            },
            shape = RoundedCornerShape(16.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = MaterialTheme.colorScheme.primary,
                unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
            ),
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            RadioButton(selected = selected, onClick = onSelect)
            Text(
                text = stringResource(R.string.currently_used),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = onTest, enabled = !isTesting) {
                if (isTesting) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                } else {
                    Text(stringResource(R.string.test_connection))
                }
            }
        }
        if (testMessage != null) {
            val resolvedMsg = testMessage.asString()
            Text(
                text = resolvedMsg,
                style = MaterialTheme.typography.bodySmall,
                color = if (resolvedMsg.contains("成功") || resolvedMsg.contains("success", ignoreCase = true)) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.error
                }
            )
        }
    }
}
