package com.kevin.tvcleanerbackup.ui

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.lifecycle.lifecycleScope
import com.kevin.tvcleanerbackup.core.StorageCleaner
import com.kevin.tvcleanerbackup.core.UsbBackupManager
import com.kevin.tvcleanerbackup.databinding.ActivityMainBinding
import com.kevin.tvcleanerbackup.utils.FormatUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var backupManager: UsbBackupManager
    private lateinit var storageCleaner: StorageCleaner

    private var usbTreeUri: Uri? = null
    private var pendingJunkItems: List<StorageCleaner.JunkItem> = emptyList()

    private val openUsbTreeLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri -> onUsbTreeSelected(uri) }

    private val requestLegacyStoragePermission = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { reportStoragePermissionState() }

    private val requestManageStoragePermission = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { reportStoragePermissionState() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        backupManager = UsbBackupManager(this)
        storageCleaner = StorageCleaner(this)

        binding.btnChooseUsb.setOnClickListener { openUsbTreeLauncher.launch(null) }
        binding.btnBackup.setOnClickListener { startBackup() }
        binding.btnScan.setOnClickListener { scanJunk() }
        binding.btnClean.setOnClickListener { cleanJunk() }

        ensureStoragePermission()
    }

    override fun onResume() {
        super.onResume()
        // L'octroi de MANAGE_EXTERNAL_STORAGE se fait dans un écran Paramètres
        // externe : on ne le sait qu'au retour sur l'activité, pas via un callback.
        reportStoragePermissionState()
    }

    // ---------- Permissions ----------

    private fun hasStoragePermission(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Android 11+ : le stockage cloisonné s'applique quel que soit
            // requestLegacyExternalStorage (qui n'a d'effet que sur Android 10).
            // Les permissions média granulaires ne couvrent ni les fichiers
            // résiduels non-média, ni le droit de les supprimer : il faut l'accès
            // complet "gestion de tous les fichiers".
            Environment.isExternalStorageManager()
        } else {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                .all { ActivityCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }
        }

    private fun ensureStoragePermission() {
        if (hasStoragePermission()) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val intent = Intent(
                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                Uri.parse("package:$packageName")
            )
            try {
                requestManageStoragePermission.launch(intent)
            } catch (e: ActivityNotFoundException) {
                requestManageStoragePermission.launch(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            }
        } else {
            requestLegacyStoragePermission.launch(
                arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE)
            )
        }
    }

    private fun reportStoragePermissionState() {
        if (hasStoragePermission()) {
            log("Autorisation de stockage accordée")
        }
    }

    // ---------- Sauvegarde USB ----------

    private fun onUsbTreeSelected(uri: Uri?) {
        if (uri == null) {
            log("Sélection de la clé USB annulée")
            return
        }
        contentResolver.takePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        )
        usbTreeUri = uri
        binding.usbStatusText.text = "Destination USB : ${uri.lastPathSegment}"
        binding.btnBackup.isEnabled = true
        log("Clé USB sélectionnée : ${uri.lastPathSegment}")
    }

    private fun startBackup() {
        val destination = usbTreeUri ?: return
        if (!hasStoragePermission()) {
            ensureStoragePermission()
            log("Autorisation de stockage requise avant la sauvegarde")
            return
        }

        setBackupControlsEnabled(false)
        binding.backupProgress.visibility = android.view.View.VISIBLE
        binding.backupProgress.progress = 0
        log("Démarrage de la sauvegarde…")

        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                backupManager.runBackup(destination) { progress ->
                    runOnUiThread {
                        val percent = if (progress.filesTotal > 0)
                            (progress.filesDone * 100 / progress.filesTotal).coerceIn(0, 100)
                        else 0
                        binding.backupProgress.progress = percent
                        binding.usbStatusText.text =
                            "Sauvegarde en cours (${progress.filesDone}/${progress.filesTotal}) : ${progress.currentItem}"
                    }
                }
            }

            binding.backupProgress.progress = 100
            binding.usbStatusText.text =
                "Sauvegarde terminée : ${result.filesCopied} fichiers, ${FormatUtils.humanReadableBytes(result.bytesCopied)}"
            log("Sauvegarde terminée : ${result.filesCopied} fichiers copiés (${FormatUtils.humanReadableBytes(result.bytesCopied)})")
            if (result.errors.isNotEmpty()) {
                log("Erreurs (${result.errors.size}) :")
                result.errors.take(5).forEach { log("  - $it") }
            }
            setBackupControlsEnabled(true)
        }
    }

    private fun setBackupControlsEnabled(enabled: Boolean) {
        binding.btnChooseUsb.isEnabled = enabled
        binding.btnBackup.isEnabled = enabled && usbTreeUri != null
    }

    // ---------- Nettoyage ----------

    private fun scanJunk() {
        if (!hasStoragePermission()) {
            ensureStoragePermission()
            log("Autorisation de stockage requise avant l'analyse")
            return
        }
        binding.cleanStatusText.text = "Analyse en cours…"
        binding.btnClean.isEnabled = false
        log("Analyse du stockage…")

        lifecycleScope.launch {
            val (ownCache, junk) = withContext(Dispatchers.IO) {
                storageCleaner.ownCacheSize() to storageCleaner.scanJunk()
            }
            pendingJunkItems = junk
            val junkTotal = junk.sumOf { it.sizeBytes }
            val total = ownCache + junkTotal
            binding.cleanStatusText.text =
                "${FormatUtils.humanReadableBytes(total)} récupérables : " +
                    "${FormatUtils.humanReadableBytes(ownCache)} de cache applicatif, " +
                    "${junk.size} fichier(s)/dossier(s) résiduel(s) (${FormatUtils.humanReadableBytes(junkTotal)})"
            binding.btnClean.isEnabled = total > 0
            log("Analyse terminée : ${junk.size} éléments trouvés, ${FormatUtils.humanReadableBytes(total)} récupérables")
        }
    }

    private fun cleanJunk() {
        binding.btnClean.isEnabled = false
        binding.btnScan.isEnabled = false
        log("Nettoyage en cours…")

        lifecycleScope.launch {
            val freed = withContext(Dispatchers.IO) { storageCleaner.clean(pendingJunkItems) }
            pendingJunkItems = emptyList()
            binding.cleanStatusText.text = "Nettoyage terminé : ${FormatUtils.humanReadableBytes(freed)} libérés"
            log("Nettoyage terminé : ${FormatUtils.humanReadableBytes(freed)} libérés")
            binding.btnScan.isEnabled = true
        }
    }

    // ---------- Journal ----------

    private fun log(message: String) {
        val current = binding.logText.text.toString()
        val updated = if (current.isBlank()) message else "$current\n$message"
        binding.logText.text = updated
    }
}
