package com.kqstone.mtphotos.ui.util

import com.kqstone.mtphotos.R
import com.kqstone.mtphotos.network.NetworkFailure
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout

object PullRefreshSupport {
    private const val TIMEOUT_MS = 15_000L

    suspend fun run(
        isDeviceOffline: () -> Boolean,
        onOffline: suspend () -> Unit = {},
        timeoutMs: Long = TIMEOUT_MS,
        block: suspend () -> Unit
    ): UiText? {
        if (isDeviceOffline()) {
            onOffline()
            return UiText.StringResource(R.string.refresh_no_network)
        }

        return try {
            withTimeout(timeoutMs) {
                block()
            }
            null
        } catch (e: TimeoutCancellationException) {
            UiText.StringResource(R.string.refresh_timeout)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            toRefreshMessage(e)
        }
    }

    fun toRefreshMessage(error: Throwable): UiText {
        return when {
            error is TimeoutCancellationException -> {
                UiText.StringResource(R.string.refresh_timeout)
            }
            NetworkFailure.isServerUnreachable(error) -> {
                UiText.StringResource(R.string.refresh_server_unreachable)
            }
            !error.message.isNullOrBlank() -> UiText.DynamicString(error.message!!)
            else -> UiText.StringResource(R.string.refresh_failed)
        }
    }
}
