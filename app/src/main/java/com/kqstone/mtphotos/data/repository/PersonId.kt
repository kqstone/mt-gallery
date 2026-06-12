package com.kqstone.mtphotos.data.repository

object PersonId {
    fun normalize(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return trimmed

        val number = trimmed.toDoubleOrNull() ?: return trimmed
        return normalize(number)
    }

    fun normalize(raw: Number): String {
        val value = raw.toDouble()
        if (!value.isFinite()) return raw.toString()
        return if (value % 1.0 == 0.0) {
            value.toLong().toString()
        } else {
            value.toString()
        }
    }

    fun from(raw: Any?): String? {
        return when (raw) {
            is Number -> normalize(raw)
            is String -> normalize(raw).takeIf { it.isNotBlank() }
            else -> null
        }
    }
}
