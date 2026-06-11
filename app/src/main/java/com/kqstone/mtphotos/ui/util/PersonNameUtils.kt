package com.kqstone.mtphotos.ui.util

/**
 * Pure-logic helpers for person display names.
 *
 * Previously duplicated in DiscoveryScreen.kt and CategoryFileListScreen.kt;
 * now shared from one location.
 */
object PersonNameUtils {

    /** Returns `true` when the raw name from the server should be treated as "unnamed". */
    fun isMissing(name: String): Boolean {
        val normalized = name.trim()
        return normalized.isBlank() ||
            normalized == "未知" ||
            normalized == "未命名" ||
            normalized.equals("unknown", ignoreCase = true) ||
            normalized.equals("unnamed", ignoreCase = true)
    }

    /** Display name: falls back to [unnamedName] when the real name is missing. */
    fun displayName(name: String, unnamedName: String): String =
        if (isMissing(name)) unnamedName else name

    /** Editable name for rename dialogs: returns empty string when name is missing. */
    fun editableName(name: String): String =
        if (isMissing(name)) "" else name
}
