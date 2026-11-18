package com.elarbi.omniiptv.ui

import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import androidx.tvprovider.media.tv.TvContractCompat
import com.elarbi.omniiptv.data.Prefs
import com.elarbi.omniiptv.data.Repo
import com.elarbi.omniiptv.data.TunerSource
import com.elarbi.omniiptv.home.HomeScreen
import com.elarbi.omniiptv.home.HomeSyncWorker
import com.elarbi.omniiptv.tif.TifSync
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Actions communes aux interfaces TV et téléphone (appelées depuis les Réglages). */
object AppActions {
    fun isTv(a: android.content.Context) = a.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)

    /** Interface téléphone : réglage « Interface », ou détection automatique (pas de mode TV). */
    fun usePhoneUi(a: android.content.Context): Boolean = when (Prefs.uiMode) {
        "phone" -> true
        "tv" -> false
        else -> !isTv(a)
    }

    fun run(a: FragmentActivity, id: Int, tuner: (() -> Unit)? = null) {
        when (id) {
            MainFragment.A_REFRESH -> refresh(a)
            MainFragment.A_TUNER -> tuner?.invoke() ?: loadTuner(a)
            MainFragment.A_HOME -> {
                HomeScreen.setEnabled(a, true)
                HomeSyncWorker.runNow(a, epg = true)
                AlertDialog.Builder(a).setTitle("Rangée Omni IPTV sur l'écran d'accueil")
                    .setMessage(
                        "La rangée « Omni IPTV » est en cours de mise à jour : programmes en cours de vos favoris, " +
                            "films et épisodes à reprendre, liste « À regarder ».\n\n" +
                            "Sur Google TV, elle apparaît dans l'onglet « Applications » si Omni IPTV fait partie de vos " +
                            "applications favorites (maintenir OK sur l'icône > Déplacer au début).\n\n" +
                            "Sur Android TV classique : accueil > Personnaliser les chaînes > activer Omni IPTV.\n\n" +
                            "Sur téléphone : appui long sur l'icône d'Omni IPTV pour les raccourcis (Favoris, Guide, Rechercher)."
                    )
                    .setPositiveButton("OK", null).show()
            }
            MainFragment.A_HOME_LIVE -> a.lifecycleScope.launch {
                val chId = withContext(Dispatchers.IO) {
                    val rows = com.elarbi.omniiptv.home.HomeRows.rows(a)
                    val now = rows.firstOrNull { it.kind == "now" }
                    if (now == null) com.elarbi.omniiptv.home.HomeRows.add(a, "now", "", HomeScreen.NAME_LIVE)
                    else if (!now.enabled) com.elarbi.omniiptv.home.HomeRows.update(a, now.copy(enabled = true))
                    HomeScreen.sync(a)
                    val r = com.elarbi.omniiptv.home.HomeRows.rows(a).first { it.kind == "now" }
                    HomeScreen.ensureChannel(a, r.id, r.name, false)
                }
                if (chId == null) { Ui.toast(a, "Cet appareil ne gère pas les rangées d'accueil TV."); return@launch }
                try {
                    a.startActivity(Intent(TvContractCompat.ACTION_REQUEST_CHANNEL_BROWSABLE).putExtra(TvContractCompat.EXTRA_CHANNEL_ID, chId))
                } catch (e: Exception) {
                    Ui.toast(a, "Ajoutez la rangée depuis l'accueil : Personnaliser les chaînes > Omni IPTV.")
                }
            }
            MainFragment.A_LIVE_TAB -> {
                Ui.toast(a, "Synchronisation des chaînes avec l'onglet Direct…")
                a.lifecycleScope.launch {
                    val n = withContext(Dispatchers.IO) { try { TifSync.sync(a) } catch (e: Exception) { -1 } }
                    AlertDialog.Builder(a).setTitle("Onglet Direct / Chaînes en direct")
                        .setMessage(
                            (if (n >= 0) "$n chaînes et leurs programmes ont été publiés.\n\n" else "La synchronisation a échoué (fonction réservée aux téléviseurs).\n\n") +
                                "Sur Google TV : onglet « Direct » (ou « Chaînes en direct ») > Paramètres > Sources > activer Omni IPTV."
                        )
                        .setPositiveButton("OK", null).show()
                }
            }
            MainFragment.A_ABOUT -> AlertDialog.Builder(a).setTitle("Aide")
                .setMessage(
                    "TÉLÉCOMMANDE\n" +
                        "• OK maintenu sur une chaîne : favoris. ↑/↓ ou CH± : zapper. ← : liste des chaînes. → ou Menu : options et qualité. 0-9 : numéro.\n\n" +
                        "ÉCRAN TACTILE\n" +
                        "• Glisser à gauche / à droite sur la vidéo : chaîne suivante / précédente (film : ±30 s).\n" +
                        "• Glisser verticalement : volume (à droite), luminosité (à gauche).\n" +
                        "• Toucher : infos • double toucher : options • appui long : liste des chaînes.\n" +
                        "• Téléphone en portrait : vidéo en haut, chaînes en dessous ; tourner le téléphone ou ⛶ pour le plein écran.\n\n" +
                        "SOURCES\n" +
                        "• Portail MAC : donnez à votre fournisseur l'adresse MAC indiquée dans « Mes sources ».\n" +
                        "• Guide TV : automatique pour Xtream et portails MAC ; pour M3U, via url-tvg ou un guide XMLTV."
                )
                .setPositiveButton("OK", null).show()
        }
    }

    fun refresh(a: FragmentActivity, done: (() -> Unit)? = null) {
        val list = com.elarbi.omniiptv.data.Shown.sources()
        if (list.isEmpty()) { Ui.toast(a, "Ajoutez d'abord une source."); done?.invoke(); return }
        ProgressUi.run(a, "Chargement des chaînes", {
            val msgs = ArrayList<String>()
            for (s in list) {
                msgs += try { Repo.refresh(s); "${s.name} : ${Repo.lastDiff(s.id)}" } catch (e: Exception) { "${s.name} : échec (${e.message}) — liste enregistrée conservée" }
            }
            msgs
        }) { r ->
            Ui.toast(a, r.getOrNull()?.joinToString("\n") ?: "Échec : ${r.exceptionOrNull()?.message}")
            HomeSyncWorker.runNow(a)
            done?.invoke()
        }
    }

    fun loadTuner(a: FragmentActivity) {
        TunerSource.ensureSource()
        Repo.activeSourceId = TunerSource.ID
        a.lifecycleScope.launch {
            try {
                val n = Repo.refresh(TunerSource.source())
                Ui.toast(a, "$n chaînes du téléviseur ajoutées (TNT / satellite / câble)")
                HomeSyncWorker.runNow(a)
            } catch (e: Exception) {
                AlertDialog.Builder(a).setTitle("Chaînes du téléviseur")
                    .setMessage((e.message ?: "Erreur") + "\n\nFonction réservée aux téléviseurs équipés d'un tuner.")
                    .setPositiveButton("OK", null).show()
            }
        }
    }
}
