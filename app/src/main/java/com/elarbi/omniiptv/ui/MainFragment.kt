package com.elarbi.omniiptv.ui

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import androidx.core.content.ContextCompat
import androidx.leanback.app.BrowseSupportFragment
import androidx.leanback.widget.ArrayObjectAdapter
import androidx.leanback.widget.HeaderItem
import androidx.leanback.widget.ListRow
import androidx.leanback.widget.ListRowPresenter
import androidx.leanback.widget.OnItemViewClickedListener
import androidx.lifecycle.lifecycleScope
import androidx.tvprovider.media.tv.TvContractCompat
import com.elarbi.omniiptv.R
import com.elarbi.omniiptv.data.Repo
import com.elarbi.omniiptv.home.HomeScreen
import com.elarbi.omniiptv.home.HomeSyncWorker
import com.elarbi.omniiptv.model.ActionItem
import com.elarbi.omniiptv.model.Channel
import com.elarbi.omniiptv.model.SourceType
import com.elarbi.omniiptv.tif.TifSync
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainFragment : BrowseSupportFragment() {
    companion object {
        const val A_M3U = 1; const val A_XTREAM = 2; const val A_STALKER = 3; const val A_SOURCES = 4
        const val A_FREE = 5; const val A_SEARCH = 6; const val A_REFRESH = 7; const val A_HOME = 8
        const val A_HOME_LIVE = 9; const val A_LIVE_TAB = 10; const val A_ABOUT = 11; const val A_YT = 12; const val A_REPOS = 13
    }

    private val rowsAdapter = ArrayObjectAdapter(ListRowPresenter())
    private var builtVersion = -1
    private var autoRefreshTried = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = getString(R.string.app_name)
        headersState = HEADERS_ENABLED
        isHeadersTransitionOnBackEnabled = true
        brandColor = ContextCompat.getColor(requireContext(), R.color.brand)
        searchAffordanceColor = ContextCompat.getColor(requireContext(), R.color.accent)
        adapter = rowsAdapter
        setOnSearchClickedListener { startActivity(Intent(requireContext(), SearchActivity::class.java)) }
        onItemViewClickedListener = OnItemViewClickedListener { _, item, _, row -> onClick(item, row as? ListRow) }
    }

    override fun onResume() {
        super.onResume()
        if (builtVersion != Repo.version) build()
        val s = Repo.activeSource()
        if (s != null && !autoRefreshTried && Repo.channels(s.id).isEmpty()) {
            autoRefreshTried = true
            refreshActive()
        }
    }

    private fun actions(): List<ActionItem> = listOf(
        ActionItem(A_M3U, "Ajouter une liste M3U", "Lien m3u / m3u8", R.drawable.ic_add),
        ActionItem(A_XTREAM, "Ajouter Xtream Codes", "Serveur + identifiants", R.drawable.ic_add),
        ActionItem(A_STALKER, "Ajouter un portail MAC", "Stalker / MAG", R.drawable.ic_add),
        ActionItem(A_REPOS, "Dépôts", "Installer des listes en un clic", R.drawable.ic_list),
        ActionItem(A_YT, "Chaînes YouTube Live", "Ajouter un direct YouTube", R.drawable.ic_add),
        ActionItem(A_SOURCES, "Mes sources", Repo.activeSource()?.name ?: "Aucune source", R.drawable.ic_list),
        ActionItem(A_FREE, "Listes gratuites", "Chercher des chaînes en libre accès", R.drawable.ic_globe),
        ActionItem(A_SEARCH, "Rechercher une chaîne", "Dans toutes les sources", R.drawable.ic_search),
        ActionItem(A_REFRESH, "Actualiser", "Chaînes et guide TV", R.drawable.ic_refresh),
        ActionItem(A_HOME, "Écran d'accueil", "Mettre à jour les rangées", R.drawable.ic_home),
        ActionItem(A_HOME_LIVE, "Rangée « En ce moment »", "Programmes en cours sur l'accueil", R.drawable.ic_home),
        ActionItem(A_LIVE_TAB, "Onglet Direct", "Chaînes dans le guide Google TV", R.drawable.ic_tv),
        ActionItem(A_ABOUT, "Aide", "Raccourcis télécommande", R.drawable.ic_info)
    )

    private fun build() {
        builtVersion = Repo.version
        rowsAdapter.clear()
        var id = 0L
        val presenter = CardPresenter { onLong(it) }
        fun addRow(title: String, items: List<Any>) {
            if (items.isEmpty()) return
            val a = ArrayObjectAdapter(presenter)
            a.addAll(0, items)
            rowsAdapter.add(ListRow(HeaderItem(id++, title), a))
        }
        addRow("★ Favoris", Repo.favorites.values.toList())
        addRow("Récents", Repo.recents.toList())
        val src = Repo.activeSource()
        if (src != null) {
            val groups = LinkedHashMap<String, MutableList<Channel>>()
            Repo.channels(src.id).forEach { groups.getOrPut(it.group.ifBlank { "Sans catégorie" }) { mutableListOf() }.add(it) }
            groups.forEach { (g, l) -> addRow(g, l) }
        }
        if (src?.id != Repo.YT_ID) addRow("YouTube Live", Repo.youtubeChannels())
        addRow("Outils et réglages", actions())
    }

    private fun onClick(item: Any?, row: ListRow?) {
        when (item) {
            is Channel -> {
                val a = row?.adapter
                Repo.playContext = if (a != null) (0 until a.size()).mapNotNull { a.get(it) as? Channel } else listOf(item)
                startActivity(Intent(requireContext(), PlayerActivity::class.java).putExtra("key", item.key))
            }
            is ActionItem -> handleAction(item)
        }
    }

    private fun onLong(item: Any) {
        if (item is Channel) {
            val added = Repo.toggleFavorite(item)
            Ui.toast(requireContext(), if (added) "« ${item.name} » ajoutée aux favoris" else "« ${item.name} » retirée des favoris")
            HomeSyncWorker.runNow(requireContext())
        }
    }

    private fun addSource(t: SourceType) =
        startActivity(Intent(requireContext(), AddSourceActivity::class.java).putExtra("type", t.name))

    private fun handleAction(a: ActionItem) {
        val ctx = requireContext()
        when (a.id) {
            A_M3U -> addSource(SourceType.M3U)
            A_XTREAM -> addSource(SourceType.XTREAM)
            A_STALKER -> addSource(SourceType.STALKER)
            A_REPOS -> startActivity(Intent(ctx, ReposActivity::class.java))
            A_YT -> startActivity(Intent(ctx, YoutubeActivity::class.java))
            A_SOURCES -> startActivity(Intent(ctx, SourcesActivity::class.java))
            A_FREE -> startActivity(Intent(ctx, FreeSearchActivity::class.java))
            A_SEARCH -> startActivity(Intent(ctx, SearchActivity::class.java))
            A_REFRESH -> refreshActive()
            A_HOME -> {
                HomeSyncWorker.runNow(ctx, epg = true)
                Ui.toast(ctx, "Rangées de l'écran d'accueil en cours de mise à jour (favoris, programmes).")
            }
            A_HOME_LIVE -> lifecycleScope.launch {
                val id = withContext(Dispatchers.IO) {
                    HomeScreen.sync(ctx)
                    HomeScreen.ensureChannel(ctx, HomeScreen.SLOT_LIVE, HomeScreen.NAME_LIVE, false)
                }
                if (id == null) { Ui.toast(ctx, "Ce téléviseur ne gère pas les rangées d'accueil."); return@launch }
                try {
                    startActivity(
                        Intent(TvContractCompat.ACTION_REQUEST_CHANNEL_BROWSABLE)
                            .putExtra(TvContractCompat.EXTRA_CHANNEL_ID, id)
                    )
                } catch (e: Exception) {
                    Ui.toast(ctx, "Ajoutez la rangée depuis l'accueil : Personnaliser les chaînes > Omni IPTV.")
                }
            }
            A_LIVE_TAB -> {
                Ui.toast(ctx, "Synchronisation des chaînes avec l'onglet Direct…")
                lifecycleScope.launch {
                    val n = withContext(Dispatchers.IO) { try { TifSync.sync(ctx) } catch (e: Exception) { -1 } }
                    AlertDialog.Builder(ctx)
                        .setTitle("Onglet Direct / Chaînes en direct")
                        .setMessage(
                            (if (n >= 0) "$n chaînes et leurs programmes ont été publiés.\n\n" else "La synchronisation a échoué.\n\n") +
                                "Sur Google TV : onglet « Direct » (ou application « Chaînes en direct » / « Live Channels ») " +
                                "> Paramètres > Sources > activer Omni IPTV.\n\n" +
                                "Les favoris sont publiés en priorité ; sans favoris, les 800 premières chaînes de la source active."
                        )
                        .setPositiveButton("OK", null)
                        .show()
                }
            }
            A_ABOUT -> AlertDialog.Builder(ctx)
                .setTitle("Aide")
                .setMessage(
                    "• OK maintenu sur une chaîne : ajouter / retirer des favoris.\n" +
                        "• Pendant la lecture : ↑/↓ ou CH+/CH− pour zapper, ← pour la liste des chaînes, OK pour les infos, " +
                        "OK maintenu ou Menu pour le favori.\n" +
                        "• Portail MAC : communiquez à votre fournisseur l'adresse MAC indiquée dans « Mes sources ».\n" +
                        "• Les favoris apparaissent sur l'écran d'accueil et dans « Continuer à regarder ».\n" +
                        "• Guide TV : automatique pour Xtream et portails MAC ; pour M3U, via l'en-tête url-tvg ou une URL XMLTV."
                )
                .setPositiveButton("OK", null)
                .show()
        }
    }

    private fun refreshActive() {
        val ctx = requireContext()
        val s = Repo.activeSource() ?: run { Ui.toast(ctx, "Ajoutez d'abord une source."); return }
        progressBarManager.show()
        lifecycleScope.launch {
            try {
                val n = Repo.refresh(s)
                Ui.toast(ctx, "$n chaînes chargées depuis « ${s.name} »")
                HomeSyncWorker.runNow(ctx)
            } catch (e: Exception) {
                Ui.toast(ctx, "Échec du chargement : ${e.message}")
            } finally {
                progressBarManager.hide()
                if (isAdded) build()
            }
        }
    }
}
