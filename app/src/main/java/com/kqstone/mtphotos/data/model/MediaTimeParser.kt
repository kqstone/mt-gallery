package com.kqstone.mtphotos.data.model

import java.text.SimpleDateFormat
import java.text.ParsePosition
import java.util.Date
import java.util.Locale

object MediaTimeParser {
    private const val MILLIS_THRESHOLD = 9_999_999_999L
    private val fractionalSecondsRegex = Regex("(\\d{2}:\\d{2}:\\d{2})\\.(\\d{1,9})(.*)")
    private val parsePatterns = listOf(
        "yyyy-MM-dd'T'HH:mm:ss.SSSXXX",
        "yyyy-MM-dd'T'HH:mm:ssXXX",
        "yyyy-MM-dd HH:mm:ss.SSSXXX",
        "yyyy-MM-dd HH:mm:ssXXX",
        "yyyy-MM-dd'T'HH:mm:ss.SSSZ",
        "yyyy-MM-dd'T'HH:mm:ssZ",
        "yyyy-MM-dd HH:mm:ss.SSSZ",
        "yyyy-MM-dd HH:mm:ssZ",
        "yyyy-MM-dd'T'HH:mm:ss.SSS",
        "yyyy-MM-dd'T'HH:mm:ss",
        "yyyy-MM-dd HH:mm:ss.SSS",
        "yyyy-MM-dd HH:mm:ss",
        "yyyy-MM-dd"
    )

    fun parseMillis(value: Any?): Long? {
        return when (value) {
            is Number -> normalizeEpochMillis(value.toLong())
            is String -> parseStringMillis(value)
            else -> null
        }
    }

    fun formatTimelineMillis(millis: Long): String {
        return formatter("yyyy-MM-dd'T'HH:mm:ss").format(Date(millis))
    }

    private fun parseStringMillis(raw: String): Long? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty() || trimmed.equals("null", ignoreCase = true)) return null

        trimmed.toLongOrNull()?.let { return normalizeEpochMillis(it) }
        trimmed.toDoubleOrNull()?.let { return normalizeEpochMillis(it.toLong()) }

        val normalized = normalizeFractionalSeconds(trimmed)
        for (pattern in parsePatterns) {
            formatter(pattern).parseStrict(normalized)?.let { return it.time }
        }
        return null
    }

    private fun normalizeEpochMillis(value: Long): Long? {
        if (value <= 0L) return null
        return if (value > MILLIS_THRESHOLD) value else value * 1000L
    }

    private fun normalizeFractionalSeconds(value: String): String {
        return fractionalSecondsRegex.replace(value) { match ->
            val millis = match.groupValues[2].padEnd(3, '0').take(3)
            "${match.groupValues[1]}.$millis${match.groupValues[3]}"
        }
    }

    private fun formatter(pattern: String): SimpleDateFormat {
        return SimpleDateFormat(pattern, Locale.US).apply {
            isLenient = false
        }
    }

    private fun SimpleDateFormat.parseStrict(value: String): Date? {
        val position = ParsePosition(0)
        val parsed = parse(value, position) ?: return null
        return parsed.takeIf { position.index == value.length }
    }
}
