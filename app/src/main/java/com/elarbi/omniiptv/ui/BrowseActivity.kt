package com.elarbi.omniiptv.ui

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.GridView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import androidx.fragment.app.FragmentActivity
import com.elarbi.omniiptv.R
import com.elarbi.omniiptv.data.Edits
import com.elarbi.omniiptv.data.Library
import com.elarbi.omniiptv.data.Repo
import com.elarbi.omniiptv.home.HomeSyncWorker
import com.elarbi.omniiptv.model.Channel

/**
 * Vues « Liste » et « Grille » avec onglets de contenus :
 * Direct, Films, Séries, Favoris, Continuer, À regarder, YouTube.
 */
class BrowseActivity : FragmentActivity() {
    private lateinit var groupsView: ListView
    private lateinit var listView: ListView
    private lateinit var gridView: GridView
    private lateinit var status: TextView
    private lateinit var tabsBox: LinearLayout
    private lateinit var rowAdapter: ChannelRowAdapter
    private lateinit var tileAdapter: TileAdapter

    private var grid = false
    private var tab = "live"
    private var groups: List<String> = emptyList()
    private var content: List<Channel> = emptyList()
    private var shown: List<Channel> = emptyList()
    private var builtVersion = -1

    private val tabs = listOf(
        "live" to "Direct", "movies" to "Films", "series" to "Séries", "fav" to "★ Favoris",
        "resume" to "Continuer", "watch" to "À regarder", "yt" to "YouTube"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_browse)
        grid = intent.getStringExtra("mode") == "grid"
        tab = intent.getStringExtra("tab") ?: "live"
        groupsView = findViewById(R.id.br_groups)
        listView = findViewById(R.id.br_list)
        gridView = findViewById(R.id.br_grid)
        status = findViewById(R.id.br_status)
        tabsBox = findViewById(R.id.br_tabs)
        rowAdapter = ChannelRowAdapter(this, emptyList())
        tileAdapter = TileAdapter(this, emptyList())
        listView.adapter = rowAdapter
        gridView.adapter = tileAdapter

        groupsView.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) = showGroup(position)
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        groupsView.setOnItemClickListener { _, _, pos, _ ->
            showGroup(pos)
            (if (grid) gridView else listView).requestFocus()
        }
        val open = AdapterView.OnItemClickListener { _, _, pos, _ -> shown.getOrNull(pos)?.let { openItem(it) } }
        val long = AdapterView.OnItemLongClickListener { _, _, pos, _ -> shown.getOrNull(pos)?.let { contextMenu(it) }; true }
        listView.onItemClickListener = open
        gridView.onItemClickListener = open
        listView.onItemLongClickListener = long
        gridView.onItemLongClickListener = long
        buildTabs()
        applyMode()
    }

    override fun onResume() {
        super.onResume()
        if (builtVersion != Repo.version) load()
    }

    private fun buildTabs() {
        tabsBox.removeAllViews()
        tabs.forEach { (id, label) ->
            tabsBox.addView(Ui.button(this, (if (id == tab) "● " else "") + label, fullWidth = false) { tab = id; buildTabs(); load() })
        }
        tabsBox.addView(Ui.button(this, if (grid) "Vue : Grille" else "Vue : Liste", fullWidth = false) {
            grid = !grid; applyMode(); buildTabs()
        })
        tabsBox.addView(Ui.button(this, "Rechercher", fullWidth = false) { startActivity(Intent(this, SearchActivity::class.java)) })
        tabsBox.addView(Ui.button(this, "Guide TV", fullWidth = false) { startActivity(Intent(this, GuideActivity::class.java)) })
        tabsBox.addView(Ui.button(this, "⚙ Réglages", fullWidth = false) { startActivity(Intent(this, SettingsActivity::class.java)) })
    }

    private fun applyMode() {
        listView.visibility = if (grid) View.GONE else View.VISIBLE
        gridView.visibility = if (grid) View.VISIBLE else View.GONE
        showGroup(groupsView.selectedItemPosition.coerceAtLeast(0))
    }

    private fun load() {
        builtVersion = Repo.version
        val src = Repo.activeSource()
        val all = if (src != null) Edits.view(src.id) else emptyList()
        content = when (tab) {
            "live" -> all.filter { !it.isVod }
            "movies" -> all.filter { it.isVod && !it.url.startsWith("xtseries:") }
            "series" -> all.filter { it.url.startsWith("xtseries:") }
            "fav" -> Repo.favorites.values.map { Edits.apply(it) }
            "resume" -> Library.resume.values.toList()
            "watch" -> Library.watchlist.values.toList()
            "yt" -> Repo.youtubeChannels()
            else -> all
        }
        groups = listOf("Tout") + content.map { it.group }.distinct()
        groupsView.adapter = ArrayAdapter(this, R.layout.item_text, groups.mapIndexed { i, g ->
            val n = if (i == 0) content.size else content.count { it.group == g }
            "$g ($n)"
        })
        status.text = "${src?.name ?: "Aucune source"} • ${tabs.first { it.first == tab }.second} : ${content.size} éléments" +
            if (content.isEmpty() && tab in listOf("movies", "series")) " (disponible avec un compte Xtream)" else ""
        groupsView.setSelection(0)
        showGroup(0)
    }

    private fun showGroup(i: Int) {
        val g = groups.getOrNull(i)
        shown = if (g == null || i == 0) content else content.filter { it.group == g }
        if (grid) { tileAdapter.items = shown; tileAdapter.notifyDataSetChanged() }
        else { rowAdapter.items = shown; rowAdapter.notifyDataSetChanged() }
    }

    private fun openItem(ch: Channel) {
        if (ch.url.startsWith("xtseries:")) {
            startActivity(Intent(this, SeriesActivity::class.java).putExtra("key", ch.key))
            return
        }
        Repo.playContext = shown.filter { !it.url.startsWith("xtseries:") }
        startActivity(Intent(this, PlayerActivity::class.java).putExtra("key", ch.key))
    }

    private fun contextMenu(ch: Channel) {
        val opts = ArrayList<Pair<String, () -> Unit>>()
        opts += (if (Repo.isFavorite(ch.key)) "Retirer des favoris" else "Ajouter aux favoris") to {
            Repo.toggleFavorite(ch); HomeSyncWorker.runNow(this)
        }
        opts += (if (Library.isWatch(ch.key)) "Retirer de « À regarder »" else "Ajouter à « À regarder »") to { Library.toggleWatch(ch) }
        if (Library.resume.containsKey(ch.key)) opts += "Retirer de « Continuer »" to { Library.finish(ch) }
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
