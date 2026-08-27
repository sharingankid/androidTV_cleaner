package com.kevin.tvcleanerbackup.ui

import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.kevin.tvcleanerbackup.core.AppCacheCleaner
import com.kevin.tvcleanerbackup.core.BloatwareManager
import com.kevin.tvcleanerbackup.databinding.ActivityRootToolsBinding
import com.kevin.tvcleanerbackup.utils.FormatUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class RootToolsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityRootToolsBinding
    private lateinit var appCacheCleaner: AppCacheCleaner
    private lateinit var bloatwareManager: BloatwareManager

    private var appCacheItems: List<AppCacheCleaner.AppCacheInfo> = emptyList()
    private val selectedPackages = mutableSetOf<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityRootToolsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        appCacheCleaner = AppCacheCleaner(this)
        bloatwareManager = BloatwareManager(this)

        binding.btnScanAppCache.setOnClickListener { scanAppCache() }
        binding.btnCleanAppCache.setOnClickListener { confirmCleanAppCache() }
        binding.btnScanBloatware.setOnClickListener { scanBloatware() }
    }

    // ---------- Cache des autres applications ----------

    private fun scanAppCache() {
        binding.btnScanAppCache.isEnabled = false
        binding.appCacheStatusText.text = "Analyse en cours…"
        log("Analyse du cache des autres applications…")

        lifecycleScope.launch {
            val items = withContext(Dispatchers.IO) { appCacheCleaner.scan() }
            appCacheItems = items
            selectedPackages.clear()
            val total = items.sumOf { it.sizeBytes }
            binding.appCacheStatusText.text =
                "${items.size} application(s) avec du cache : ${FormatUtils.humanReadableBytes(total)} récupérables"
            binding.appCacheList.layoutManager = LinearLayoutManager(this@RootToolsActivity)
            binding.appCacheList.adapter = AppCacheAdapter(items, selectedPackages) { updateCleanButtonState() }
            binding.btnScanAppCache.isEnabled = true
            updateCleanButtonState()
            log("Analyse du cache terminée : ${items.size} application(s) trouvée(s)")
        }
    }

    private fun updateCleanButtonState() {
        binding.btnCleanAppCache.isEnabled = selectedPackages.isNotEmpty()
    }

    private fun confirmCleanAppCache() {
        val toClean = appCacheItems.filter { selectedPackages.contains(it.packageName) }
        if (toClean.isEmpty()) return
        val total = toClean.sumOf { it.sizeBytes }
        AlertDialog.Builder(this)
            .setTitle("Vider le cache sélectionné ?")
            .setMessage(
                "${toClean.size} application(s), ${FormatUtils.humanReadableBytes(total)} au total.\n" +
                    "Cela ne supprime pas leurs données ni leurs comptes, uniquement leur cache."
            )
            .setPositiveButton("Vider") { _, _ -> cleanAppCache(toClean) }
            .setNegativeButton("Annuler", null)
            .show()
    }

    private fun cleanAppCache(items: List<AppCacheCleaner.AppCacheInfo>) {
        binding.btnCleanAppCache.isEnabled = false
        log("Nettoyage du cache de ${items.size} application(s)…")
        lifecycleScope.launch {
            val freed = withContext(Dispatchers.IO) { appCacheCleaner.clean(items) }
            log("Cache vidé : ${FormatUtils.humanReadableBytes(freed)} libérés")
            scanAppCache()
        }
    }

    // ---------- Bloatware ----------

    private fun scanBloatware() {
        binding.btnScanBloatware.isEnabled = false
        binding.bloatwareStatusText.text = "Analyse en cours…"
        log("Recherche des applications désinstallables…")

        lifecycleScope.launch {
            val candidates = withContext(Dispatchers.IO) { bloatwareManager.listCandidates() }.toMutableList()
            binding.bloatwareStatusText.text =
                "${candidates.size} application(s) proposée(s). Chaque suppression est confirmée individuellement."
            binding.bloatwareList.layoutManager = LinearLayoutManager(this@RootToolsActivity)
            binding.bloatwareList.adapter = BloatwareAdapter(candidates) { candidate ->
                confirmUninstall(candidate)
            }
            binding.btnScanBloatware.isEnabled = true
            log("Analyse terminée : ${candidates.size} application(s) trouvée(s)")
        }
    }

    private fun confirmUninstall(candidate: BloatwareManager.Candidate) {
        AlertDialog.Builder(this)
            .setTitle(candidate.label)
            .setMessage(
                "${candidate.description}\n\n" +
                    "Paquet : ${candidate.packageName}\n" +
                    "Taille estimée : ${FormatUtils.humanReadableBytes(candidate.sizeBytes)}\n\n" +
                    "Désinstallée pour cet utilisateur uniquement : récupérable via un reset " +
                    "d'usine, ou \"Afficher les apps système désactivées\" dans les paramètres " +
                    "pour une application système."
            )
            .setPositiveButton("Désinstaller") { _, _ -> uninstall(candidate) }
            .setNegativeButton("Annuler", null)
            .show()
    }

    private fun uninstall(candidate: BloatwareManager.Candidate) {
        log("Désinstallation de ${candidate.label} (${candidate.packageName})…")
        lifecycleScope.launch {
            val (success, message) = withContext(Dispatchers.IO) { bloatwareManager.uninstall(candidate.packageName) }
            if (success) {
                (binding.bloatwareList.adapter as? BloatwareAdapter)?.removeItem(candidate.packageName)
                log("${candidate.label} désinstallée")
            } else {
                log("Échec de la désinstallation de ${candidate.label} : $message")
            }
        }
    }

    // ---------- Journal ----------

    private fun log(message: String) {
        val current = binding.rootLogText.text.toString()
        binding.rootLogText.text = if (current.isBlank()) message else "$current\n$message"
    }
}
