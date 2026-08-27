package com.kevin.tvcleanerbackup.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.kevin.tvcleanerbackup.R
import com.kevin.tvcleanerbackup.core.RootManager
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

    private var rootAvailable = false

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
        binding.btnRootTools.setOnClickListener {
            startActivity(Intent(this, RootToolsActivity::class.java))
        }

        checkRootAccess()
    }

    // ---------- Accès root ----------

    private fun checkRootAccess() {
        setFunctionalControlsEnabled(false)
        binding.rootStatusText.text = getString(R.string.no_root_title)
        lifecycleScope.launch {
            rootAvailable = withContext(Dispatchers.IO) { RootManager.hasRoot() }
            if (rootAvailable) {
                binding.rootStatusText.text = getString(R.string.root_status_ok)
                binding.rootStatusText.setTextColor(getColor(R.color.success))
                setFunctionalControlsEnabled(true)
                log(getString(R.string.root_status_ok))
            } else {
                binding.rootStatusText.text = getString(R.string.no_root_title)
                binding.rootStatusText.setTextColor(getColor(R.color.danger))
                log(getString(R.string.no_root_message))
            }
        }
    }

    private fun setFunctionalControlsEnabled(enabled: Boolean) {
        binding.btnChooseUsb.isEnabled = enabled
        binding.btnBackup.isEnabled = enabled && usbTreeUri != null
        binding.btnScan.isEnabled = enabled
        binding.btnClean.isEnabled = enabled && pendingJunkItems.isNotEmpty()
        binding.btnRootTools.isEnabled = enabled
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
        if (!rootAvailable) return

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
        if (!rootAvailable) return
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
