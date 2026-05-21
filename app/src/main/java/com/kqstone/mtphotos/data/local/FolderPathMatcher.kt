package com.kqstone.mtphotos.data.local

object FolderPathMatcher {
    fun normalize(path: String): String {
        return path.replace('\\', '/').trim().trimEnd('/')
    }

    fun contains(folderPath: String, childPath: String?): Boolean {
        val folder = normalize(folderPath)
        val child = childPath?.let(::normalize).orEmpty()
        if (folder.isEmpty() || child.isEmpty()) return false
        return child == folder || child.startsWith("$folder/")
    }

    fun isInAnyScope(path: String?, folderPaths: Set<String>): Boolean {
        if (folderPaths.isEmpty()) return false
        return folderPaths.any { contains(it, path) }
    }

    fun hasAncestorSelected(path: String, selectedPaths: Set<String>): Boolean {
        return selectedPaths.any { selected ->
            val normalizedSelected = normalize(selected)
            val normalizedPath = normalize(path)
            normalizedPath != normalizedSelected && contains(normalizedSelected, normalizedPath)
        }
    }

    fun isDescendantOf(path: String, parentPath: String): Boolean {
        val normalizedPath = normalize(path)
        val normalizedParent = normalize(parentPath)
        return normalizedPath != normalizedParent && contains(normalizedParent, normalizedPath)
    }
}
