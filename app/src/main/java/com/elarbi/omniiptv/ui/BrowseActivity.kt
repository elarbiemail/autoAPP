package com.elarbi.omniiptv.ui

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.GridView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import androidx.fragment.app.FragmentActivity
import com.elarbi.omniiptv.R
import com.elarbi.omniiptv.data.Edits
import com.elarbi.omniiptv.data.EpgNorm
import com.elarbi.omniiptv.data.Library
import com.elarbi.omniiptv.data.Prefs
import com.elarbi.omniiptv.data.Repo
import com.elarbi.omniiptv.data.Shown
import com.elarbi.omniiptv.home.HomeSyncWorker
import com.elarbi.omniiptv.model.Channel
import com.elarbi.omniiptv.model.SourceType
import com.elarbi.omniiptv.model.isSeries

/**
 * Médiathèque (TV et grand écran) :
 * choix du portail (Stalker, Xtream, M3U…), sections Direct / Films / Séries,
 * sous-catégories filtrables, recherche instantanée dans la catégorie ou dans toute la section.
 */
class BrowseActivity : FragmentActivity() {
    private lateinit var groupsView: ListView
    private lateinit var listView: ListView
    private lateinit var gridView: GridView
    private lateinit var status: TextView
    private lateinit var empty: TextView
    private lateinit var tabsBox: LinearLayout
    private lateinit var catFilter: EditText
    private lateinit var search: EditText
    private lateinit var scopeChip: TextView
    private lateinit var rowAdapter: ChannelRowAdapter
    private lateinit var tileAdapter: TileAdapter

    private val gridFor = HashMap<String, Boolean>()
    private var tab = "live"
    private var content: List<Channel> = emptyList()
    private var groups: List<String> = emptyList()     // noms complets des groupes
    private var visibleGroups: List<String> = emptyList()
    private var group: String? = null                  // null = tout
    private var searchAll = true                       // recherche dans toute la section
    private var shown: List<Channel> = emptyList()
    private var builtVersion = -1
    private val handler = Handler(Looper.getMainLooper())
    private val applySearch = Runnable { showContent() }

    private val tabs = listOf(
        "live" to "Direct", "movies" to "Films", "series" to "Séries", "fav" to "★ Favoris",
        "resume" to "Continuer", "watch" to "À regarder"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_browse)
        tab = intent.getStringExtra("tab") ?: "live"
        gridFor["movies"] = true
        gridFor["series"] = true
        gridFor["live"] = intent.getStringExtra("mode") == "grid"
        groupsView = findViewById(R.id.br_groups)
        listView = findViewById(R.id.br_list)
        gridView = findViewById(R.id.br_grid)
        status = findViewById(R.id.br_status)
        empty = findViewById(R.id.br_empty)
        tabsBox = findViewById(R.id.br_tabs)
        catFilter = findViewById(R.id.br_cat_filter)
        search = findViewById(R.id.br_search)
        scopeChip = findViewById(R.id.br_scope)
        rowAdapter = ChannelRowAdapter(this, emptyList())
        tileAdapter = TileAdapter(this, emptyList())
        listView.adapter = rowAdapter
        gridView.adapter = tileAdapter

        groupsView.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) = selectGroup(position)
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        groupsView.setOnItemClickListener { _, _, pos, _ ->
            selectGroup(pos)
            (if (isGrid()) gridView else listView).requestFocus()
        }
        val open = AdapterView.OnItemClickListener { _, _, pos, _ -> shown.getOrNull(pos)?.let { openItem(it) } }
        val long = AdapterView.OnItemLongClickListener { _, _, pos, _ -> shown.getOrNull(pos)?.let { contextMenu(it) }; true }
        listView.onItemClickListener = open
        gridView.onItemClickListener = open
        listView.onItemLongClickListener = long
        gridView.onItemLongClickListener = long

        catFilter.addTextChangedListener(watcher { buildGroups() })
        search.addTextChangedListener(watcher { handler.removeCallbacks(applySearch); handler.postDelayed(applySearch, 250) })
        scopeChip.setOnClickListener { searchAll = !searchAll; updateScope(); showContent() }
        findViewById<View>(R.id.br_clear).setOnClickListener {
            search.setText(""); catFilter.setText(""); group = null; buildGroups(); showContent()
        }
        updateScope()
        buildTabs()
    }

    override fun onResume() {
        super.onResume()
        if (builtVersion != Repo.version) load()
    }

    private fun watcher(f: () -> Unit) = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        override fun afterTextChanged(s: Editable?) = f()
    }

    private fun isGrid() = gridFor[tab] ?: false

    private fun updateScope() {
        scopeChip.text = if (searchAll) "Dans : toute la section" else "Dans : la catégorie"
    }

    // ---------------- Source ----------------
    /** Source choisie dans la médiathèque (mémorisée) ; vide = sources affichées. */
    private fun sourceId(): String? = Prefs.browseSource.takeIf { id -> Repo.sources.any { it.id == id } }

    private fun sourceLabel(): String = sourceId()?.let { id -> Repo.sources.first { it.id == id }.name } ?: Shown.title()

    private fun chooseSource() {
        val list = Repo.sources.filter { it.type != SourceType.YOUTUBE }
        val labels = listOf("Toutes les sources affichées (${Shown.title()})") + list.map { s ->
            val n = Repo.channels(s.id)
            val vod = n.count { it.isVod }
            (if (s.id == sourceId()) "● " else "") + "${s.name} — ${s.typeLabel} (${n.size - vod} chaînes" +
                (if (vod > 0) ", $vod films/séries" else "") + ")"
        }
        AlertDialog.Builder(this).setTitle("Choisir le portail / la source")
            .setItems(labels.toTypedArray()) { _, w ->
                Prefs.browseSource = if (w == 0) "" else list[w - 1].id
                group = null
                load()
            }
            .setNeutralButton("✎ Modifier un serveur") { _, _ ->
                val id = sourceId()
                if (id != null) SourcePicker.edit(this, id) else SourcePicker.editOne(this)
            }.show()
    }

    // ---------------- Barre du haut ----------------
    private fun buildTabs() {
        tabsBox.removeAllViews()
        tabsBox.addView(Ui.button(this, "📡 ${sourceLabel()}  ▾", fullWidth = false) { chooseSource() })
        tabs.forEach { (id, label) ->
            tabsBox.addView(Ui.button(this, (if (id == tab) "● " else "") + label, fullWidth = false) {
                tab = id; group = null; catFilter.setText(""); buildTabs(); load()
            })
        }
        tabsBox.addView(Ui.button(this, if (isGrid()) "Vue : Grille" else "Vue : Liste", fullWidth = false) {
            gridFor[tab] = !isGrid(); buildTabs(); showContent()
        })
        tabsBox.addView(Ui.button(this, "Guide TV", fullWidth = false) { startActivity(Intent(this, GuideActivity::class.java)) })
        tabsBox.addView(Ui.button(this, "⚙ Réglages", fullWidth = false) { startActivity(Intent(this, SettingsActivity::class.java)) })
    }

    // ---------------- Contenu ----------------
    private fun load() {
        builtVersion = Repo.version
        buildTabs()
        val sid = sourceId()
        val all = if (sid != null) Edits.view(sid) else Shown.channels()
        content = when (tab) {
            "live" -> all.filter { !it.isVod }
            "movies" -> all.filter { it.isVod && !it.isSeries && !it.key.contains("|E") }
            "series" -> all.filter { it.isSeries }
            "fav" -> Repo.favorites.values.map { Edits.apply(it) }
            "resume" -> Library.resume.values.toList()
            "watch" -> Library.watchlist.values.toList()
            else -> all
        }
        groups = content.map { it.group }.distinct()
        buildGroups()
        showContent()
    }

    /** Nom affiché d'un groupe (sans le préfixe « Films · » / « Séries · »). */
    private fun label(g: String) = g.removePrefix("Films · ").removePrefix("Séries · ")

    private fun buildGroups() {
        val f = EpgNorm.norm(catFilter.text.toString())
        visibleGroups = if (f.isEmpty()) groups else groups.filter { EpgNorm.norm(label(it)).contains(f) }
        val labels = listOf("Tout (${content.size})") + visibleGroups.map { g -> "${label(g)} (${content.count { it.group == g }})" }
        groupsView.adapter = ArrayAdapter(this, R.layout.item_text, labels)
        val pos = group?.let { visibleGroups.indexOf(it) + 1 }?.takeIf { it > 0 } ?: 0
        if (pos == 0) group = null
        groupsView.setSelection(pos)
        groupsView.setItemChecked(pos, true)
    }

    private fun selectGroup(pos: Int) {
        val g = if (pos <= 0) null else visibleGroups.getOrNull(pos - 1)
        if (g == group && shown.isNotEmpty()) return
        group = g
        showContent()
    }

    private fun showContent() {
        val q = EpgNorm.norm(search.text.toString())
        var list = if (group == null || (q.isNotEmpty() && searchAll)) content else content.filter { it.group == group }
        if (q.isNotEmpty()) list = list.filter { EpgNorm.norm(it.name).contains(q) }
        shown = list
        listView.visibility = if (isGrid()) View.GONE else View.VISIBLE
        gridView.visibility = if (isGrid()) View.VISIBLE else View.GONE
        if (isGrid()) { tileAdapter.items = shown; tileAdapter.notifyDataSetChanged() }
        else { rowAdapter.items = shown; rowAdapter.notifyDataSetChanged() }
        empty.visibility = if (shown.isEmpty()) View.VISIBLE else View.GONE
        empty.text = when {
            Repo.sources.isEmpty() -> "Aucune source : ⚙ Réglages > Sources pour ajouter un portail Stalker, un compte Xtream ou une liste M3U."
            q.isNotEmpty() -> "Aucun résultat pour « ${search.text} »."
            tab == "movies" || tab == "series" -> "Aucun contenu dans cette section pour « ${sourceLabel()} »."
            else -> "Aucun élément."
        }
        val tabLabel = tabs.first { it.first == tab }.second
        val where = when {
            q.isNotEmpty() && (searchAll || group == null) -> " • recherche dans toute la section"
            group != null -> " • ${label(group!!)}"
            else -> ""
        }
        status.text = "${sourceLabel()} • $tabLabel$where : ${shown.size} élément(s)"
    }

    private fun openItem(ch: Channel) {
        if (ch.isSeries) {
            startActivity(Intent(this, SeriesActivity::class.java).putExtra("key", ch.key))
            return
        }
        Repo.playContext = shown.filter { !it.isSeries }
        startActivity(Intent(this, PlayerActivity::class.java).putExtra("key", ch.key))
    }

    private fun contextMenu(ch: Channel) {
        val opts = ArrayList<Pair<String, () -> Unit>>()
        opts += (if (Repo.isFavorite(ch.key)) "Retirer des favoris" else "Ajouter aux favoris") to {
            Repo.toggleFavorite(ch); HomeSyncWorker.runNow(this)
        }
        opts += (if (Library.isWatch(ch.key)) "Retirer de « À regarder »" else "Ajouter à « À regarder »") to { Library.toggleWatch(ch) }
        if (Library.resume.containsKey(ch.key)) opts += "Retirer de « Continuer »" to { Library.finish(ch) }
        opts += "Ajouter un raccourci sur l'écran d'accueil" to {
            if (!com.elarbi.omniiptv.home.Shortcuts.pin(this, ch)) {
                Ui.toast(this, "Sur ce téléviseur, les favoris et films en cours apparaissent dans la rangée Omni IPTV de l'accueil.")
            }
        }
        if (Repo.sources.any { it.id == ch.sourceId }) {
            opts += "Masquer" to { Edits.update(ch.sourceId, ch.key) { it.hidden = true } }
            opts += "Gérer les chaînes…" to {
                startActivity(Intent(this, ChannelManagerActivity::class.java).putExtra("source", ch.sourceId))
            }
        }
        AlertDialog.Builder(this).setTitle(ch.name)
            .setItems(opts.map { it.first }.toTypedArray()) { _, w -> opts[w].second(); load() }
            .show()
    }
}
