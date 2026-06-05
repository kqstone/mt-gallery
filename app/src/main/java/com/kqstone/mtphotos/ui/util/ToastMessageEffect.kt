package com.kqstone.mtphotos.ui.util

import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext

@Composable
fun ToastMessageEffect(
    message: UiText?,
    onConsumed: () -> Unit
) {
    val context = LocalContext.current
    LaunchedEffect(message) {
        if (message != null) {
            Toast.makeText(context, message.asString(context), Toast.LENGTH_SHORT).show()
            onConsumed()
        }
    }
}
