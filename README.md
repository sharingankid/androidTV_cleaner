# TV Cleaner & Backup

Application Android TV (Kotlin, Leanback) permettant de :

1. **Sauvegarder vers une clé USB** (via Storage Access Framework) : Téléchargements, Images, DCIM, Films, Musique, Documents, données propres à l'application, et un instantané des infos système (modèle, version Android, liste des apps installées).
2. **Nettoyer le stockage accessible** : vide le cache de l'application, détecte les fichiers temporaires (`.tmp`, `.log`, `.bak`, `.old`), les caches de miniatures et les dossiers vides dans le stockage partagé.

## Limite importante (sans root)

Android empêche une application tierce d'accéder aux données internes des **autres** applications ou aux fichiers système protégés. Sans root, il est donc impossible de :
- vider le cache d'autres applications (l'utilisateur doit le faire depuis *Paramètres > Applications*),
- sauvegarder l'état système complet (comme le ferait `adb backup` ou une image de firmware),
- supprimer des fichiers dans `Android/data` d'autres apps (bloqué même avec la permission "Tous les fichiers" depuis Android 11).

Cette appli fait donc le maximum possible **légalement et sans root** : stockage partagé + données de l'app elle-même. Si vous obtenez un accès root sur l'appareil plus tard, on peut étendre `StorageCleaner` et `UsbBackupManager` avec des commandes shell privilégiées (`Runtime.exec("su -c ...")`) pour un vrai nettoyage système et une vraie sauvegarde complète.

## Ouvrir le projet

1. Ouvrir le dossier `AndroidTVCleanerBackup` dans Android Studio (Koala ou plus récent).
2. Laisser Android Studio générer le wrapper Gradle et synchroniser le projet.
3. Lancer sur un émulateur Android TV ou un appareil TV réel (Paramètres > À propos > activer le débogage USB/réseau, `adb connect <ip>:5555`).

## Structure

```
app/src/main/java/com/kevin/tvcleanerbackup/
├── core/
│   ├── UsbBackupManager.kt   # copie vers la clé USB via SAF
│   └── StorageCleaner.kt     # scan + nettoyage du stockage accessible
├── ui/
│   └── MainActivity.kt       # écran TV, navigation D-pad
└── utils/
    └── FormatUtils.kt        # formatage des tailles de fichiers
```

## Utilisation

1. Brancher la clé USB en OTG sur le boîtier Android TV.
2. Dans l'appli, cliquer sur **"Choisir la clé USB"** → sélectionner la clé dans le sélecteur de dossiers système.
3. Cliquer sur **"Lancer la sauvegarde"**.
4. Pour le nettoyage : **"Analyser l'espace utilisé"** puis **"Nettoyer les fichiers inutiles"**.
