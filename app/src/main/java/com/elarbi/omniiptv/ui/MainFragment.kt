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
import com.elarbi.omniiptv.data.Edits
import com.elarbi.omniiptv.data.Library
import com.elarbi.omniiptv.data.Repo
import com.elarbi.omniiptv.data.TunerSource
import com.elarbi.omniiptv.home.HomeScreen
import com.elarbi.omniiptv.home.HomeSyncWorker
import com.elarbi.omniiptv.model.ActionItem
import com.elarbi.omniiptv.model.Channel
import com.elarbi.omniiptv.model.isSeries
import com.elarbi.omniiptv.model.SourceType
import com.elarbi.omniiptv.tif.TifSync
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainFragment : BrowseSupportFragment() {
    companion object {
        const val A_M3U = 1; const val A_XTREAM = 2; const val A_STALKER = 3; const val A_SOURCES = 4
        const val A_FREE = 5; const val A_SEARCH = 6; const val A_REFRESH = 7; const val A_HOME = 8
        const val A_HOME_LIVE = 9; const val A_LIVE_TAB = 10; const val A_ABOUT = 11; const val A_YT = 12; const val A_REPOS = 13; const val A_MANAGER = 14; const val A_EPG = 15
        const val A_LIST = 16; const val A_GRID = 17; const val A_GUIDE = 18; const val A_MULTI = 19; const val A_SETTINGS = 20; const val A_TUNER = 21
    }

    private val rowsAdapter = ArrayObjectAdapter(ListRowPresenter())
    private var builtVersion = -1
    private var autoRefreshTried = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = getString(R.string.app_name)
        headersState = HEADERS_ENABLED
        isHeadersTransitionOnBackEnabled = true
        brandColor = ThemeManager.brand
        searchAffordanceColor = ThemeManager.accent
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

    /** Rangée de raccourcis en tête ; toutes les options sont regroupées dans ⚙ Réglages. */
    private fun actions(): List<ActionItem> = listOf(
        ActionItem(A_SETTINGS, "⚙ Réglages", "Toutes les options de l'application", R.drawable.ic_info),
        ActionItem(A_SEARCH, "Rechercher", "Dans toutes les sources", R.drawable.ic_search),
        ActionItem(A_GUIDE, "Guide TV", "Grille des programmes", R.drawable.ic_tv),
        ActionItem(A_LIST, "Vue liste", "Direct, films, séries…", R.drawable.ic_list),
        ActionItem(A_GRID, "Vue grille", "Vignettes et affiches", R.drawable.ic_home),
        ActionItem(A_MULTI, "Multi-écran", "4 chaînes à la fois", R.drawable.ic_tv),
        ActionItem(A_SOURCES, "Mes sources", Repo.activeSource()?.name ?: "Aucune source", R.drawable.ic_list)
    )

    /** Exécute une action demandée depuis l'écran Réglages. */
    fun runAction(id: Int) {
        if (!isAdded) return
        handleAction(ActionItem(id, "", "", 0))
    }

    private fun build() {
        builtVersion = Repo.version
        rowsAdapter.clear()
        var id = 0L
        val presenter = CardPresenter { onLong(it) }
        val menu = ArrayObjectAdapter(presenter)
        menu.addAll(0, actions())
        rowsAdapter.add(ListRow(HeaderItem(id++, "Menu"), menu))
        fun addRow(title: String, items: List<Any>) {
            if (items.isEmpty()) return
            val a = ArrayObjectAdapter(presenter)
            a.addAll(0, items)
            rowsAdapter.add(ListRow(HeaderItem(id++, title), a))
        }
        addRow("★ Favoris", Repo.favorites.values.map { Edits.apply(it) })
        addRow("Continuer la lecture", Library.resume.values.toList())
        addRow("À regarder", Library.watchlist.values.toList())
        addRow("Récents", Repo.recents.map { Edits.apply(it) })
        val src = Repo.activeSource()
        if (src != null) {
            val groups = LinkedHashMap<String, MutableList<Channel>>()
            com.elarbi.omniiptv.data.Shown.channels().forEach { groups.getOrPut(it.group.ifBlank { "Sans catégorie" }) { mutableListOf() }.add(it) }
            groups.forEach { (g, l) -> addRow(g, l) }
        }
        if (com.elarbi.omniiptv.data.Shown.sources().none { it.id == Repo.YT_ID }) addRow("YouTube Live", Repo.youtubeChannels())
    }

    private fun onClick(item: Any?, row: ListRow?) {
        when (item) {
            is Channel -> {
                if (item.isSeries) {
                    startActivity(Intent(requireContext(), SeriesActivity::class.java).putExtra("key", item.key))
                    return
                }
                val a = row?.adapter
                Repo.playContext = if (a != null) (0 until a.size()).mapNotNull { a.get(it) as? Channel } else listOf(item)
                startActivity(Intent(requireContext(), PlayerActivity::class.java).putExtra("key", item.key))
            }
            is ActionItem -> handleAction(item)
        }
    }

    /** Appui long : menu contextuel de la chaîne / du film / de la série. */
    private fun onLong(item: Any) {
        if (item !is Channel) return
        val ctx = requireContext()
        val opts = ArrayList<Pair<String, () -> Unit>>()
        opts += (if (Repo.isFavorite(item.key)) "Retirer des favoris" else "Ajouter aux favoris") to {
            val added = Repo.toggleFavorite(item)
            Ui.toast(ctx, if (added) "Ajoutée aux favoris" else "Retirée des favoris")
            HomeSyncWorker.runNow(ctx)
        }
        opts += (if (Library.isWatch(item.key)) "Retirer de « À regarder »" else "Ajouter à « À regarder »") to {
            Ui.toast(ctx, if (Library.toggleWatch(item)) "Ajouté à « À regarder »" else "Retiré de « À regarder »")
        }
        if (Library.resume.containsKey(item.key)) opts += "Retirer de « Continuer la lecture »" to { Library.finish(item) }
        opts += "Ajouter un raccourci sur l'écran d'accueil" to {
            if (!com.elarbi.omniiptv.home.Shortcuts.pin(ctx, item)) {
                Ui.toast(ctx, "Sur le téléviseur : ajoutez-le aux favoris, il apparaîtra dans la rangée Omni IPTV de l'accueil.")
            }
        }
        if (Repo.sources.any { it.id == item.sourceId }) {
            opts += "Masquer" to { Edits.update(item.sourceId, item.key) { it.hidden = true } }
            opts += "Gérer les chaînes…" to {
                startActivity(Intent(ctx, ChannelManagerActivity::class.java).putExtra("source", item.sourceId))
            }
        }
        android.app.AlertDialog.Builder(ctx).setTitle(item.name)
            .setItems(opts.map { it.first }.toTypedArray()) { _, w -> opts[w].second(); build() }
            .show()
    }

    private fun addSource(t: SourceType) =
        startActivity(Intent(requireContext(), AddSourceActivity::class.java).putExtra("type", t.name))

    private fun handleAction(a: ActionItem) {
        val ctx = requireContext()
        when (a.id) {
            A_M3U -> addSource(SourceType.M3U)
            A_XTREAM -> addSource(SourceType.XTREAM)
            A_STALKER -> addSource(SourceType.STALKER)
            A_TUNER -> tunerPermission.launch(TunerSource.permissions)
            A_GUIDE -> startActivity(Intent(ctx, GuideActivity::class.java))
            A_LIST -> startActivity(Intent(ctx, BrowseActivity::class.java).putExtra("mode", "list"))
            A_GRID -> startActivity(Intent(ctx, BrowseActivity::class.java).putExtra("mode", "grid"))
            A_MULTI -> startActivity(Intent(ctx, MultiViewActivity::class.java))
            A_SETTINGS -> startActivity(Intent(ctx, SettingsActivity::class.java))
            A_MANAGER -> startActivity(Intent(ctx, ChannelManagerActivity::class.java))
            A_EPG -> startActivity(Intent(ctx, EpgActivity::class.java))
            A_REPOS -> startActivity(Intent(ctx, ReposActivity::class.java))
            A_YT -> startActivity(Intent(ctx, YoutubeActivity::class.java))
            A_SOURCES -> startActivity(Intent(ctx, SourcesActivity::class.java))
            A_FREE -> startActivity(Intent(ctx, FreeSearchActivity::class.java))
            A_SEARCH -> startActivity(Intent(ctx, SearchActivity::class.java))
            A_REFRESH -> refreshActive()
            A_HOME -> {
                HomeSyncWorker.runNow(ctx, epg = true)
                AlertDialog.Builder(ctx).setTitle("Rangée Omni IPTV sur l'écran d'accueil")
                    .setMessage(
                        "La rangée « Omni IPTV » est en cours de mise à jour : programmes en cours de vos favoris, " +
                            "films et épisodes à reprendre, liste « À regarder ».\n\n" +
                            "Sur Google TV, elle apparaît dans l'onglet « Applications », comme YouTube ou CloudStream, " +
                            "si Omni IPTV fait partie de vos applications favorites : dans « Vos applications », " +
                            "maintenez OK sur l'icône Omni IPTV, puis « Déplacer » vers le début de la liste.\n\n" +
                            "Sur Android TV classique : accueil > Personnaliser les chaînes > activer Omni IPTV."
                    )
                    .setPositiveButton("OK", null).show()
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

    private val tunerPermission = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()
    ) { _ -> loadTuner() }

    /** Ajoute la source « Chaînes du téléviseur » et l'affiche. */
    private fun loadTuner() {
        val ctx = context ?: return
        TunerSource.ensureSource()
        Repo.activeSourceId = TunerSource.ID
        progressBarManager.show()
        lifecycleScope.launch {
            try {
                val n = Repo.refresh(TunerSource.source())
                Ui.toast(ctx, "$n chaînes du téléviseur ajoutées (TNT / satellite / câble)")
                HomeSyncWorker.runNow(ctx)
            } catch (e: Exception) {
                AlertDialog.Builder(ctx).setTitle("Chaînes du téléviseur")
                    .setMessage(
                        (e.message ?: "Erreur") + "\n\nSi besoin : Paramètres du téléviseur > Applications > Omni IPTV > " +
                            "Autorisations > activer « Chaînes et programmes TV »."
                    )
                    .setPositiveButton("OK", null).show()
            } finally {
                progressBarManager.hide()
                if (isAdded) build()
            }
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
