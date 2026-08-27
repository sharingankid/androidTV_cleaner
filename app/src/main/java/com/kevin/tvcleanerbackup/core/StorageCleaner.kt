package com.kevin.tvcleanerbackup.core

import android.content.Context
import android.os.Environment
import java.io.File

/**
 * Nettoyage sans root : Android limite une appli tierce à son propre cache et
 * aux fichiers qu'elle peut réellement lire dans le stockage partagé.
 * Impossible ici de vider le cache d'une AUTRE application ou de fichiers
 * système protégés (cela nécessite soit le rôle "gestionnaire de stockage"
 * réservé aux apps système, soit un accès root).
 */
class StorageCleaner(private val context: Context) {

    data class JunkItem(val file: File, val sizeBytes: Long, val reason: String)

    private val junkExtensions = setOf("tmp", "log", "bak", "old", "dmp")
    private val junkFileNames = setOf("thumbs.db", ".ds_store")

    private val scannableDirs: List<File> by lazy {
        listOfNotNull(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM),
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC),
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
        ).filter { it.exists() }
    }

    /** Taille actuelle du cache propre à cette application (toujours nettoyable). */
    fun ownCacheSize(): Long {
        var total = dirSize(context.cacheDir)
        context.externalCacheDirs?.forEach { dir -> if (dir != null) total += dirSize(dir) }
        return total
    }

    /** Recherche les fichiers temporaires/orphelins dans les dossiers accessibles. */
    fun scanJunk(): List<JunkItem> {
        val results = mutableListOf<JunkItem>()
        scannableDirs.forEach { root ->
            root.walkTopDown().forEach { f ->
                if (f.isFile) {
                    val lowerName = f.name.lowercase()
                    val ext = f.extension.lowercase()
                    when {
                        ext in junkExtensions ->
                            results += JunkItem(f, f.length(), "extension temporaire .$ext")
                        lowerName in junkFileNames ->
                            results += JunkItem(f, f.length(), "fichier système résiduel")
                    }
                } else if (f.isDirectory && f != root) {
                    if (f.name.equals(".thumbnails", ignoreCase = true) ||
                        f.name.equals(".trashed", ignoreCase = true)
                    ) {
                        val size = dirSize(f)
                        if (size > 0) results += JunkItem(f, size, "cache de miniatures")
                    } else if (f.listFiles()?.isEmpty() == true) {
                        results += JunkItem(f, 0L, "dossier vide")
                    }
                }
            }
        }
        return results
    }

    /** Supprime le cache propre à l'application + les éléments sélectionnés. Retourne les octets libérés. */
    fun clean(items: List<JunkItem>): Long {
        var freed = ownCacheSize()
        clearOwnCache()
        items.forEach { item ->
            freed += if (item.file.isDirectory) dirSize(item.file) else item.sizeBytes
            item.file.deleteRecursively()
        }
        return freed
    }

    private fun clearOwnCache() {
        context.cacheDir.listFiles()?.forEach { it.deleteRecursively() }
        context.externalCacheDirs?.forEach { dir ->
            dir?.listFiles()?.forEach { it.deleteRecursively() }
        }
    }

    private fun dirSize(dir: File): Long {
        if (!dir.exists()) return 0L
        return dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }
}
