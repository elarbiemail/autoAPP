package com.elarbi.omniiptv.ui

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.AdapterView
import android.widget.EditText
import android.widget.GridView
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.elarbi.omniiptv.R
import com.elarbi.omniiptv.data.Edits
import com.elarbi.omniiptv.data.EpgNorm
import com.elarbi.omniiptv.data.Library
import com.elarbi.omniiptv.data.Prefs
import com.elarbi.omniiptv.data.Repo
import com.elarbi.omniiptv.home.HomeSyncWorker
import com.elarbi.omniiptv.model.Channel
import com.elarbi.omniiptv.model.isSeries
import com.elarbi.omniiptv.model.isMovie

/**
 * Accueil optimisé pour téléphone et tablette :
 * barre de navigation en bas (Direct, Films, Séries, Ma liste, Réglages),
 * filtres par catégorie, recherche instantanée, glisser vers le bas pour actualiser.
 */
class MobileActivity : FragmentActivity() {
    private lateinit var titleView: TextView
    private lateinit var filter: EditText
    private lateinit var chips: LinearLayout
    private lateinit var status: TextView
    private lateinit var refresh: SwipeRefreshLayout
    private lateinit var listView: ListView
    private lateinit var gridView: GridView
    private lateinit var empty: TextView
    private lateinit var nav: LinearLayout
    private lateinit var rowAdapter: ChannelRowAdapter
    private lateinit var tileAdapter: TileAdapter

    private var tab = "live"
    private var chip = "Tout"
    private var gridForTab = HashMap<String, Boolean>()
    private var content: List<Channel> = emptyList()
    private var shown: List<Channel> = emptyList()
    private var builtVersion = -1

    private val navItems = listOf(
        Triple("live", "Direct", R.drawable.ic_tv),
        Triple("movies", "Films", R.drawable.ic_home),
        Triple("series", "Séries", R.drawable.ic_list),
        Triple("mine", "Ma liste", R.drawable.ic_star),
        Triple("more", "Réglages", R.drawable.ic_info)
    )
    private val mineChips = listOf("Favoris", "Continuer", "À regarder", "Récents", "YouTube")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_mobile)
        titleView = findViewById(R.id.m_title)
        filter = findViewById(R.id.m_filter)
        chips = findViewById(R.id.m_chips)
        status = findViewById(R.id.m_status)
        refresh = findViewById(R.id.m_refresh)
        listView = findViewById(R.id.m_list)
        gridView = findViewById(R.id.m_grid)
        empty = findViewById(R.id.m_empty)
        nav = findViewById(R.id.m_nav)
        rowAdapter = ChannelRowAdapter(this, emptyList())
        tileAdapter = TileAdapter(this, emptyList())
        listView.adapter = rowAdapter
        gridView.adapter = tileAdapter
        gridForTab["movies"] = true
        gridForTab["series"] = true

        titleView.setOnClickListener { chooseSource() }
        findViewById<ImageButton>(R.id.m_search).setOnClickListener { toggleFilter() }
        findViewById<ImageButton>(R.id.m_guide).setOnClickListener { startActivity(Intent(this, GuideActivity::class.java)) }
        findViewById<ImageButton>(R.id.m_view).setOnClickListener {
            gridForTab[tab] = !(gridForTab[tab] ?: false)
            showContent()
        }
        filter.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) { showContent() }
        })

        refresh.setColorSchemeColors(ThemeManager.accent)
        refresh.setProgressBackgroundColorSchemeColor(ThemeManager.brand)
        refresh.setOnChildScrollUpCallback { _, _ ->
            val v = if (gridView.visibility == View.VISIBLE) gridView else listView
            v.canScrollVertically(-1)
        }
        refresh.setOnRefreshListener { AppActions.refresh(this) { refresh.isRefreshing = false; load() } }

        val open = AdapterView.OnItemClickListener { _, _, pos, _ -> shown.getOrNull(pos)?.let { openItem(it) } }
        val long = AdapterView.OnItemLongClickListener { _, _, pos, _ -> shown.getOrNull(pos)?.let { contextMenu(it) }; true }
        listView.onItemClickListener = open
        gridView.onItemClickListener = open
        listView.onItemLongClickListener = long
        gridView.onItemLongClickListener = long

        buildNav()
        handleIntent(intent)
        if (savedInstanceState == null) startup()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(i: Intent?) {
        when (i?.data?.lastPathSegment) {
            "fav" -> { tab = "mine"; chip = "Favoris"; buildNav(); load() }
            "last" -> Repo.recents.firstOrNull()?.let { ch ->
                Repo.playContext = Repo.recents.toList()
                startActivity(Intent(this, PlayerActivity::class.java).putExtra("key", ch.key))
            }
        }
    }

    private fun startup() {
        if (Prefs.refreshOnStart) AppActions.refresh(this) { load() }
        else Repo.activeSource()?.let { s -> if (Repo.channels(s.id).isEmpty()) { refresh.isRefreshing = true; AppActions.refresh(this) { refresh.isRefreshing = false; load() } } }
        if (Prefs.autoPlayLast) Repo.recents.firstOrNull()?.let { ch ->
            Repo.playContext = listOf(ch)
            startActivity(Intent(this, PlayerActivity::class.java).putExtra("key", ch.key))
        }
        HomeSyncWorker.runNow(this)
    }

    override fun onResume() {
        super.onResume()
        if (builtVersion != Repo.version) load()
    }

    // ---------------- Navigation ----------------
    private fun buildNav() {
        nav.removeAllViews()
        navItems.forEach { (id, label, icon) ->
            val selected = id == tab
            val color = if (selected) ThemeManager.accent else Color.parseColor("#B0BEC5")
            val tv = TextView(this).apply {
                text = label
                textSize = 12f
                gravity = Gravity.CENTER
                setTextColor(color)
                val d = ContextCompat.getDrawable(this@MobileActivity, icon)?.mutate()
                d?.setTint(color)
                d?.setBounds(0, 0, Ui.dp(this@MobileActivity, 24), Ui.dp(this@MobileActivity, 24))
                setCompoundDrawables(null, d, null, null)
                compoundDrawablePadding = Ui.dp(this@MobileActivity, 2)
                setPadding(0, Ui.dp(this@MobileActivity, 6), 0, Ui.dp(this@MobileActivity, 4))
                background = ContextCompat.getDrawable(this@MobileActivity, R.drawable.bg_item)
                isFocusable = true
                setOnClickListener {
                    if (id == "more") {
                        startActivity(Intent(this@MobileActivity, SettingsActivity::class.java))
                    } else {
                        tab = id
                        chip = if (id == "mine") "Favoris" else "Tout"
                        buildNav()
                        load()
                    }
                }
            }
            nav.addView(tv, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
        }
    }

    private fun chooseSource() {
        val list = Repo.sources
        if (list.isEmpty()) { startActivity(Intent(this, SettingsActivity::class.java)); return }
        val shown = com.elarbi.omniiptv.data.Shown.sources().map { it.id }
        val names = list.map { (if (it.id in shown) "● " else "") + it.name + " — " + it.typeLabel }
        AlertDialog.Builder(this).setTitle("Source à afficher")
            .setItems((names + "☰ Afficher plusieurs sources…" + "＋ Ajouter une source…").toTypedArray()) { _, w ->
                when {
                    w < list.size -> { com.elarbi.omniiptv.data.Shown.showOnly(list[w].id); chip = "Tout"; load() }
                    w == list.size -> SourcePicker.pickSeveral(this) { chip = "Tout"; load() }
                    else -> startActivity(Intent(this, SettingsActivity::class.java))
                }
            }.show()
    }

    private fun toggleFilter() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        if (filter.visibility == View.VISIBLE) {
            filter.setText("")
            filter.visibility = View.GONE
            imm.hideSoftInputFromWindow(filter.windowToken, 0)
        } else {
            filter.visibility = View.VISIBLE
            filter.requestFocus()
            imm.showSoftInput(filter, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    // ---------------- Contenu ----------------
    private fun load() {
        builtVersion = Repo.version
        val src = Repo.activeSource()
        titleView.text = (if (src == null) "Omni IPTV" else com.elarbi.omniiptv.data.Shown.title()) + "  ▾"
        val all = com.elarbi.omniiptv.data.Shown.channels()
        content = when (tab) {
            "live" -> all.filter { !it.isVod }
            "movies" -> all.filter { it.isMovie }
            "series" -> all.filter { it.isSeries }
            else -> when (chip) {
                "Continuer" -> Library.resume.values.toList()
                "À regarder" -> Library.watchlist.values.toList()
                "Récents" -> Repo.recents.map { Edits.apply(it) }
                "YouTube" -> Repo.youtubeChannels()
                else -> Repo.favorites.values.map { Edits.apply(it) }
            }
        }
        buildChips()
        showContent()
    }

    private fun buildChips() {
        chips.removeAllViews()
        val labels = if (tab == "mine") mineChips else listOf("Tout") + content.map { it.group }.distinct()
        if (tab != "mine" && chip !in labels) chip = "Tout"
        labels.forEach { l -> chips.addView(chipView(l, l == chip) { chip = l; if (tab == "mine") load() else { buildChips(); showContent() } }) }
    }

    private fun chipView(label: String, selected: Boolean, onClick: () -> Unit): TextView = TextView(this).apply {
        text = label
        textSize = 14f
        setTextColor(if (selected) Color.BLACK else Color.WHITE)
        setPadding(Ui.dp(this@MobileActivity, 14), Ui.dp(this@MobileActivity, 7), Ui.dp(this@MobileActivity, 14), Ui.dp(this@MobileActivity, 7))
        background = GradientDrawable().apply {
            cornerRadius = Ui.dp(this@MobileActivity, 18).toFloat()
            setColor(if (selected) ThemeManager.accent else Color.parseColor("#33FFFFFF"))
        }
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            .apply { marginEnd = Ui.dp(this@MobileActivity, 8) }
    }

    private fun showContent() {
        val q = EpgNorm.norm(filter.text.toString())
        var list = if (tab == "mine" || chip == "Tout") content else content.filter { it.group == chip }
        if (q.isNotEmpty()) list = list.filter { EpgNorm.norm(it.name).contains(q) }
        shown = list
        val grid = gridForTab[tab] ?: false
        listView.visibility = if (grid) View.GONE else View.VISIBLE
        gridView.visibility = if (grid) View.VISIBLE else View.GONE
        if (grid) { tileAdapter.items = shown; tileAdapter.notifyDataSetChanged() }
        else { rowAdapter.items = shown; rowAdapter.notifyDataSetChanged() }
        empty.visibility = if (shown.isEmpty()) View.VISIBLE else View.GONE
        empty.text = when {
            Repo.sources.isEmpty() -> "Aucune source.\nTouchez le titre en haut ou « Réglages » pour ajouter une liste M3U, un compte Xtream, un portail MAC, des chaînes YouTube ou un dépôt."
            q.isNotEmpty() -> "Aucun résultat pour cette recherche."
            tab in listOf("movies", "series") -> "Aucun contenu. Les films et séries sont disponibles avec un compte Xtream."
            tab == "mine" -> "Liste vide. Appui long sur une chaîne ou un film pour l'ajouter."
            else -> "Aucune chaîne. Glissez vers le bas pour actualiser."
        }
        status.text = "${shown.size} éléments" + if (tab == "live") " • glissez vers le bas pour actualiser" else ""
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

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (filter.visibility == View.VISIBLE) { toggleFilter(); return }
        if (tab != "live") { tab = "live"; chip = "Tout"; buildNav(); load(); return }
        @Suppress("DEPRECATION")
        super.onBackPressed()
    }
}
