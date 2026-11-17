# Omni IPTV — application Android TV / Google TV

Lecteur IPTV pour téléviseurs Google TV / Android TV (testé pour une cible type Daiko Smart Google TV, Android 8 minimum).

## Fonctions
- **Sources** : listes M3U/M3U8 (url-tvg, tvg-id, tvg-logo, group-title, #EXTVLCOPT, en-têtes « |User-Agent= »), **Xtream Codes** (direct + films), **portail MAC Stalker/MAG** (adresse MAC virtuelle 00:1A:79 générée par l'appareil, à déclarer chez le fournisseur).
- **Chaînes YouTube Live** : lien de chaîne (@pseudo) ou de direct ; lecture du direct dans l'appli, sinon ouverture de l'appli YouTube. Les liens YouTube placés dans une M3U sont aussi lus.
- **Protocoles de flux** : HLS, MPEG-TS sur HTTP, DASH, Smooth Streaming, RTSP, RTMP, UDP/RTP multicast.
- **Guide TV** : XMLTV (.xml / .xml.gz), automatique pour Xtream et portails MAC.
- **Listes gratuites** : catalogues publics iptv-org (par pays, langue, thème) et Free-TV, recherche par nom, test de disponibilité, enregistrement comme source.
- **Dépôts (à la CloudStream)** : un fichier JSON en ligne regroupe des listes M3U, comptes Xtream, portails MAC, chaînes YouTube Live et autres dépôts ; installation en un clic. Un dépôt intégré de chaînes gratuites est fourni.
- **Écran d'accueil** : rangée « Mes chaînes » (favoris + programme en cours), rangée « En ce moment », « Continuer à regarder », et **onglet Direct** (chaînes + programmes dans le guide système via TV Input Framework).
- Télécommande : ↑/↓ ou CH± zapper, ← liste des chaînes, OK infos, OK maintenu ou Menu = favori.

## Obtenir l'APK sans rien installer (GitHub)
1. Créer un dépôt sur github.com (ex. `omni-iptv`), puis « Add file > Upload files » et glisser **tout le contenu** du dossier (y compris le dossier `.github`).
2. Onglet **Actions** : la compilation démarre seule (≈ 5 min).
3. Onglet **Releases** : télécharger `OmniIPTV.apk` (lien direct).

## Ou avec Android Studio
Ouvrir le dossier, laisser Gradle synchroniser, puis Build > Build APK(s).

## Installer sur le téléviseur
- Paramètres > Système > À propos > cliquer 7 fois sur « Build du système d'exploitation » (mode développeur), puis autoriser les sources inconnues pour l'appli utilisée.
- Installer l'appli **Downloader** (Play Store), y saisir l'URL de la release GitHub de l'APK, puis Installer. (Autres options : clé USB + gestionnaire de fichiers, ou « Send Files to TV ».)

## Écran d'accueil
- La rangée « Mes chaînes » est créée automatiquement : ajoutez des favoris (OK maintenu sur une chaîne).
- « En ce moment » : menu Outils > Rangée « En ce moment ».
- Onglet Direct : menu Outils > Onglet Direct, puis dans l'onglet Direct / l'appli Chaînes en direct : Paramètres > Sources > Omni IPTV.
- Sur Google TV, l'emplacement des rangées d'applis dépend de la version du système (accueil ou onglet Applications).

## Créer son propre dépôt
Modèle : `depot-exemple.json`. Types d'éléments : `m3u`, `xtream`, `stalker`, `youtube`, `repo`.
- Hébergez le fichier sur GitHub (lien « raw ») ou GitHub Pages.
- Ajout dans l'appli : Outils > Dépôts, puis l'URL, ou le raccourci `utilisateur/depot` (fichier `omni-repo.json` sur la branche `main`), ou `utilisateur/depot/chemin/fichier.json`.
- Lien d'installation direct (à partager) : `omniiptv://repo?url=https://…/depot.json`
- Un élément `stalker` sans `mac` utilise l'adresse MAC de l'appareil. Évitez de publier des identifiants Xtream dans un dépôt public.
