package com.kevin.tvcleanerbackup.core

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.isActive
import org.json.JSONObject
import java.io.File
import kotlin.coroutines.coroutineContext

/**
 * Sauvegarde vers une clé USB via Storage Access Framework.
 *
 * Sans root, une appli tierce ne peut pas lire les données internes des
 * autres applications ni les fichiers systèmes protégés : on sauvegarde donc
 * ce qui est réellement accessible en lecture sur un Android TV standard :
 * - les dossiers de stockage partagé (Téléchargements, DCIM, Images, Films, Musique, Documents)
 * - les données propres à cette application (préférences, fichiers internes)
 * - un instantané des informations système (modèle, version Android, apps installées)
 */
class UsbBackupManager(private val context: Context) {

    data class Progress(
        val currentItem: String,
        val filesDone: Int,
        val filesTotal: Int,
        val bytesDone: Long,
        val bytesTotal: Long
    )

    private val sourceDirs: List<Pair<String, File?>> by lazy {
        listOf(
            "Telechargements" to Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "Images" to Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
            "DCIM" to Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM),
            "Films" to Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
            "Musique" to Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC),
            "Documents" to Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
        )
    }

    /** Calcule le nombre total de fichiers et d'octets à copier, pour la barre de progression. */
    fun estimateWork(): Pair<Int, Long> {
        var files = 0
        var bytes = 0L
        sourceDirs.forEach { (_, dir) ->
            if (dir != null && dir.exists()) {
                dir.walkTopDown().forEach { f ->
                    if (f.isFile) {
                        files++
                        bytes += f.length()
                    }
                }
            }
        }
        // + fichiers internes de l'appli + instantané système (comptés approximativement)
        files += 2
        return files to bytes
    }

    /**
     * Copie l'ensemble des sources vers l'arbre USB [destinationTreeUri].
     * [onProgress] est appelé après chaque fichier copié.
     * Retourne le nombre de fichiers copiés et d'erreurs rencontrées.
     */
    suspend fun runBackup(
        destinationTreeUri: Uri,
        onProgress: (Progress) -> Unit
    ): BackupResult {
        val root = DocumentFile.fromTreeUri(context, destinationTreeUri)
            ?: return BackupResult(0, 0, listOf("Impossible d'ouvrir la destination USB"))

        val backupRoot = getOrCreateDir(root, "TVBackup_${System.currentTimeMillis()}")
            ?: return BackupResult(0, 0, listOf("Impossible de créer le dossier de sauvegarde"))

        val (totalFiles, totalBytes) = estimateWork()
        var doneFiles = 0
        var doneBytes = 0L
        var copied = 0
        val errors = mutableListOf<String>()

        // 1) Dossiers de stockage partagé
        for ((label, dir) in sourceDirs) {
            if (!coroutineContext.isActive) break
            if (dir == null || !dir.exists()) continue
            val destDir = getOrCreateDir(backupRoot, label) ?: continue
            copyDirRecursive(dir, destDir) { fileName, fileBytes ->
                doneFiles++
                doneBytes += fileBytes
                copied++
                onProgress(Progress(fileName, doneFiles, totalFiles, doneBytes, totalBytes))
            }.let { errors += it }
        }

        // 2) Données propres à l'application (préférences + fichiers internes)
        try {
            val appDataDir = getOrCreateDir(backupRoot, "AppData")
            if (appDataDir != null) {
                writeTextFile(appDataDir, "shared_preferences.json", exportSharedPreferences())
                doneFiles++
                onProgress(Progress("shared_preferences.json", doneFiles, totalFiles, doneBytes, totalBytes))

                val internalFilesDir = context.filesDir
                if (internalFilesDir.exists()) {
                    val internalDest = getOrCreateDir(appDataDir, "files")
                    if (internalDest != null) {
                        copyDirRecursive(internalFilesDir, internalDest) { fileName, fileBytes ->
                            doneFiles++
                            doneBytes += fileBytes
                            copied++
                            onProgress(Progress(fileName, doneFiles, totalFiles, doneBytes, totalBytes))
                        }.let { errors += it }
                    }
                }
            }
        } catch (e: Exception) {
            errors += "Données de l'application : ${e.message}"
        }

        // 3) Instantané des informations système
        try {
            writeTextFile(backupRoot, "device_info.txt", buildDeviceInfo())
            doneFiles++
            onProgress(Progress("device_info.txt", doneFiles, totalFiles, doneBytes, totalBytes))
        } catch (e: Exception) {
            errors += "Infos système : ${e.message}"
        }

        return BackupResult(copied, doneBytes, errors)
    }

    data class BackupResult(val filesCopied: Int, val bytesCopied: Long, val errors: List<String>)

    private fun copyDirRecursive(
        source: File,
        destParent: DocumentFile,
        onFileCopied: (String, Long) -> Unit
    ): List<String> {
        val errors = mutableListOf<String>()
        source.listFiles()?.forEach { child ->
            try {
                if (child.isDirectory) {
                    val childDest = getOrCreateDir(destParent, child.name) ?: return@forEach
                    errors += copyDirRecursive(child, childDest, onFileCopied)
                } else if (child.isFile) {
                    copyFile(child, destParent)
                    onFileCopied(child.name, child.length())
                }
            } catch (e: Exception) {
                errors += "${child.name} : ${e.message}"
            }
        }
        return errors
    }

    private fun copyFile(source: File, destParent: DocumentFile) {
        destParent.findFile(source.name)?.delete()
        val destFile = destParent.createFile(guessMime(source.name), source.name) ?: return
        context.contentResolver.openOutputStream(destFile.uri)?.use { out ->
            source.inputStream().use { input -> input.copyTo(out) }
        }
    }

    private fun writeTextFile(parent: DocumentFile, name: String, content: String) {
        parent.findFile(name)?.delete()
        val file = parent.createFile("text/plain", name) ?: return
        context.contentResolver.openOutputStream(file.uri)?.use { out ->
            out.write(content.toByteArray(Charsets.UTF_8))
        }
    }

    private fun getOrCreateDir(parent: DocumentFile, name: String): DocumentFile? {
        val safeName = name.ifBlank { "dossier" }
        return parent.findFile(safeName)?.takeIf { it.isDirectory }
            ?: parent.createDirectory(safeName)
    }

    private fun guessMime(fileName: String): String {
        val ext = fileName.substringAfterLast('.', "")
        return android.webkit.MimeTypeMap.getSingleton()
            .getMimeTypeFromExtension(ext.lowercase()) ?: "application/octet-stream"
    }

    private fun exportSharedPreferences(): String {
        val json = JSONObject()
        try {
            val prefsDir = File(context.applicationInfo.dataDir, "shared_prefs")
            prefsDir.listFiles { f -> f.extension == "xml" }?.forEach { prefFile ->
                val prefName = prefFile.nameWithoutExtension
                val prefs = context.getSharedPreferences(prefName, Context.MODE_PRIVATE)
                val entries = JSONObject()
                prefs.all.forEach { (k, v) -> entries.put(k, v?.toString()) }
                json.put(prefName, entries)
            }
        } catch (_: Exception) {
            // Certains fichiers de préférences peuvent être inaccessibles ; on ignore.
        }
        return json.toString(2)
    }

    private fun buildDeviceInfo(): String {
        val pm = context.packageManager
        val installedApps = try {
            pm.getInstalledApplications(0).joinToString("\n") { app ->
                "- ${pm.getApplicationLabel(app)} (${app.packageName})"
            }
        } catch (e: Exception) {
            "Indisponible : ${e.message}"
        }
        return buildString {
            appendLine("Sauvegarde générée le : ${java.util.Date()}")
            appendLine("Modèle : ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("Android : ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
            appendLine("Build : ${Build.DISPLAY}")
            appendLine()
            appendLine("Applications installées :")
            appendLine(installedApps)
        }
    }
}
