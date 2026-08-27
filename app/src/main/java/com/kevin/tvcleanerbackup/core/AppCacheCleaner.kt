package com.kevin.tvcleanerbackup.core

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager

/**
 * Vide le cache d'AUTRES applications via un shell root : sans root, Android
 * limite chaque application à son propre cache (cf. StorageCleaner).
 */
class AppCacheCleaner(private val context: Context) {

    data class AppCacheInfo(
        val packageName: String,
        val label: String,
        val cachePaths: List<String>,
        val sizeBytes: Long
    )

    /** Liste les autres applications installées et la taille de leur cache. */
    fun scan(): List<AppCacheInfo> {
        val pm = context.packageManager
        val ownPackage = context.packageName
        val apps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
            .filter { it.packageName != ownPackage }
        if (apps.isEmpty()) return emptyList()

        val commands = apps.flatMap { app -> candidatePaths(app) }
            .map { path -> "[ -d \"$path\" ] && echo \"$path::$(du -sk \"$path\" 2>/dev/null | cut -f1)\"" }

        val (_, output) = RootManager.exec(commands)

        val sizeKbByPath = output.mapNotNull { line ->
            val idx = line.lastIndexOf("::")
            if (idx < 0) return@mapNotNull null
            val path = line.substring(0, idx)
            val kb = line.substring(idx + 2).toLongOrNull() ?: return@mapNotNull null
            path to kb
        }.toMap()

        return apps.mapNotNull { app ->
            val paths = candidatePaths(app).filter { sizeKbByPath.containsKey(it) }
            val totalKb = paths.sumOf { sizeKbByPath[it] ?: 0L }
            if (totalKb <= 0) return@mapNotNull null
            val label = try { pm.getApplicationLabel(app).toString() } catch (e: Exception) { app.packageName }
            AppCacheInfo(app.packageName, label, paths, totalKb * 1024)
        }.sortedByDescending { it.sizeBytes }
    }

    /** Supprime le contenu des dossiers de cache sélectionnés. Retourne les octets libérés. */
    fun clean(items: List<AppCacheInfo>): Long {
        var freed = 0L
        val commands = mutableListOf<String>()
        items.forEach { item ->
            freed += item.sizeBytes
            item.cachePaths.forEach { path ->
                commands += "find \"$path\" -mindepth 1 -exec rm -rf {} + 2>/dev/null"
            }
        }
        if (commands.isNotEmpty()) RootManager.exec(commands)
        return freed
    }

    private fun candidatePaths(app: ApplicationInfo): List<String> = listOf(
        "${app.dataDir}/cache",
        "${app.dataDir}/code_cache",
        "/storage/emulated/0/Android/data/${app.packageName}/cache"
    )
}
