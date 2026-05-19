package com.kqstone.mtphotos.data.repository

import com.kqstone.mtphotos.AppContainer

data class BackupDestinationNode(
    val id: Long,
    val name: String,
    val path: String
)

class BackupDestinationRepository(private val container: AppContainer) {

    suspend fun getRootDestinations(): List<BackupDestinationNode> {
        val response = container.gatewayApi.GatewayControllerPart4BackupDistRoot()
        return parseNodes(response, "/")
    }

    suspend fun getSubDestinations(parentId: Long, parentPath: String): List<BackupDestinationNode> {
        val response = container.gatewayApi.GatewayControllerPart4BackupDistSubDir(parentId.toInt())
        return parseNodes(response, parentPath)
    }

    suspend fun createFolder(parentId: Long, name: String): Map<String, Any> {
        return container.gatewayApi.GatewayControllerPart5FolderCreate(
            mapOf("pid" to parentId.toInt(), "name" to name)
        )
    }

    private fun parseNodes(
        rawNodes: List<Map<String, Any>>,
        parentPath: String
    ): List<BackupDestinationNode> {
        return rawNodes.mapNotNull { raw ->
            val id = raw.firstLong("id", "dist_id", "distId", "value") ?: return@mapNotNull null
            val name = raw.firstString("name", "dist_name", "distName", "label", "title")
                ?.takeIf { it.isNotBlank() }
                ?: "目录$id"
            val path = raw.firstString("path", "full_path", "fullPath", "absPath", "location")
                ?.takeIf { it.isNotBlank() }
                ?: joinPath(parentPath, name)

            BackupDestinationNode(
                id = id,
                name = name,
                path = normalizePath(path)
            )
        }.distinctBy { it.id }
    }

    private fun Map<String, Any>.firstLong(vararg keys: String): Long? {
        for (key in keys) {
            val value = this[key] ?: continue
            when (value) {
                is Number -> return value.toLong()
                is String -> value.toLongOrNull()?.let { return it }
            }
        }
        return null
    }

    private fun Map<String, Any>.firstString(vararg keys: String): String? {
        for (key in keys) {
            val value = this[key] ?: continue
            when (value) {
                is String -> return value
                else -> return value.toString()
            }
        }
        return null
    }

    private fun joinPath(parentPath: String, name: String): String {
        val cleanParent = normalizePath(parentPath)
        return if (cleanParent == "/") "/$name" else "$cleanParent/$name"
    }

    private fun normalizePath(path: String): String {
        val trimmed = path.trim().replace('\\', '/')
        if (trimmed.isBlank()) return "/"
        val normalized = if (trimmed.startsWith("/")) trimmed else "/$trimmed"
        return normalized.replace(Regex("/+"), "/")
    }
}
