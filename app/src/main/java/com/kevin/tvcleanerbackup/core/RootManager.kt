package com.kevin.tvcleanerbackup.core

import com.topjohnwu.superuser.Shell

/**
 * Cette build de l'application n'a de sens que sur un appareil rooté : toutes
 * les opérations fichiers/système passent par un shell root (su), ce qui
 * contourne les restrictions de stockage cloisonné et permet d'agir sur
 * d'autres applications (cache, désinstallation).
 */
object RootManager {

    init {
        Shell.enableVerboseLogging = false
        Shell.setDefaultBuilder(
            Shell.Builder.create().setTimeout(15)
        )
    }

    /** Demande l'accès root si besoin et indique s'il a été obtenu. */
    fun hasRoot(): Boolean = try {
        Shell.getShell().isRoot
    } catch (e: Exception) {
        false
    }

    /** Exécute une commande shell en root. Retourne (succès, sortie). */
    fun exec(command: String): Pair<Boolean, List<String>> {
        val result = Shell.cmd(command).exec()
        return result.isSuccess to result.out
    }

    fun exec(commands: List<String>): Pair<Boolean, List<String>> {
        val result = Shell.cmd(*commands.toTypedArray()).exec()
        return result.isSuccess to result.out
    }
}
