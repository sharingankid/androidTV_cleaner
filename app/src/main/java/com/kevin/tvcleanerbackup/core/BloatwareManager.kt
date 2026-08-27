package com.kevin.tvcleanerbackup.core

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build

/**
 * Recense les applications désinstallables (préinstallées ou non) et fournit
 * une désinstallation root par utilisateur courant (`pm uninstall --user 0`) :
 * réversible via un reset d'usine ou "Afficher tout / Activer" dans les
 * paramètres système pour une app système, contrairement à un
 * `pm uninstall` complet qui la retire définitivement du système.
 */
class BloatwareManager(private val context: Context) {

    data class Candidate(
        val packageName: String,
        val label: String,
        val description: String,
        val isSystemApp: Boolean,
        val sizeBytes: Long
    )

    /** Jamais proposées à la désinstallation : cœur système, lanceur, boutique, cette appli. */
    private val protectedPrefixes = listOf(
        "android",
        "com.kevin.tvcleanerbackup",
        "com.android.systemui",
        "com.android.settings",
        "com.android.providers.",
        "com.android.server.",
        "com.android.shell",
        "com.android.permissioncontroller",
        "com.android.vending",
        "com.google.android.gms",
        "com.google.android.gsf",
        "com.google.android.packageinstaller",
        "com.google.android.tvlauncher",
        "com.google.android.apps.tv.launcherx",
        "com.google.android.tv.launcher",
        "com.android.tv.settings",
        "com.amazon.tv.settings",
        "com.amazon.tv.launcher"
    )

    private val knownDescriptions = mapOf(
        "com.google.android.videos" to "Google TV / Play Films : location et achat de films et séries.",
        "com.google.android.youtube.tv" to "YouTube pour Android TV.",
        "com.google.android.katniss" to "Recherche et Google Assistant sur Android TV.",
        "com.google.android.backdrop" to "Économiseur d'écran Ambient (photos/art) de Google TV.",
        "com.google.android.apps.mediashell" to "Récepteur Chromecast intégré (diffusion depuis d'autres appareils).",
        "com.amazon.venezia" to "Amazon Appstore, boutique d'applications concurrente du Play Store.",
        "com.netflix.ninja" to "Application Netflix.",
        "com.disney.disneyplus" to "Application Disney+.",
        "tv.twitch.android.app" to "Application Twitch.",
        "com.spotify.tv.android" to "Application Spotify.",
        "com.plexapp.android" to "Application Plex (serveur/lecteur multimédia personnel).",
        "com.google.android.play.games" to "Google Play Jeux."
    )

    fun listCandidates(): List<Candidate> {
        val pm = context.packageManager
        return pm.getInstalledApplications(PackageManager.GET_META_DATA)
            .filterNot { app -> protectedPrefixes.any { app.packageName.startsWith(it) } }
            .map { app -> toCandidate(pm, app) }
            .sortedByDescending { it.sizeBytes }
    }

    private fun toCandidate(pm: PackageManager, app: ApplicationInfo): Candidate {
        val label = try { pm.getApplicationLabel(app).toString() } catch (e: Exception) { app.packageName }
        val isSystemApp = (app.flags and ApplicationInfo.FLAG_SYSTEM) != 0
        val description = knownDescriptions[app.packageName] ?: genericDescription(app, isSystemApp)
        return Candidate(app.packageName, label, description, isSystemApp, estimateSize(app))
    }

    private fun genericDescription(app: ApplicationInfo, isSystemApp: Boolean): String {
        val kind = if (isSystemApp) "application système préinstallée" else "application installée par l'utilisateur"
        // app.category n'existe qu'à partir de l'API 26 : ne pas lire le champ en dessous
        // (accès direct au champ, pas seulement au "when" qui l'interprète).
        val category = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) categoryLabel(app.category) else null
        return buildString {
            append("Non répertoriée : $kind")
            if (category != null) append(", catégorie \"$category\"")
            append(" — vérifiez l'usage avant de désinstaller.")
        }
    }

    private fun categoryLabel(category: Int): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return null
        return when (category) {
            ApplicationInfo.CATEGORY_GAME -> "Jeu"
            ApplicationInfo.CATEGORY_AUDIO -> "Audio"
            ApplicationInfo.CATEGORY_VIDEO -> "Vidéo"
            ApplicationInfo.CATEGORY_IMAGE -> "Image"
            ApplicationInfo.CATEGORY_SOCIAL -> "Réseau social"
            ApplicationInfo.CATEGORY_NEWS -> "Actualités"
            ApplicationInfo.CATEGORY_MAPS -> "Cartes/Navigation"
            ApplicationInfo.CATEGORY_PRODUCTIVITY -> "Productivité"
            else -> null
        }
    }

    private fun estimateSize(app: ApplicationInfo): Long {
        val (_, out) = RootManager.exec("du -sk \"${app.sourceDir}\" \"${app.dataDir}\" 2>/dev/null | awk '{s+=$1} END {print s}'")
        val kb = out.firstOrNull()?.toLongOrNull() ?: 0L
        return kb * 1024
    }

    /** Désinstalle pour l'utilisateur courant uniquement (réversible). */
    fun uninstall(packageName: String): Pair<Boolean, String> {
        val (success, output) = RootManager.exec("pm uninstall --user 0 \"$packageName\"")
        val message = output.joinToString(" ").ifBlank { if (success) "Désinstallée" else "Échec" }
        return success to message
    }
}
