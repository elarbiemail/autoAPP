package com.elarbi.omniiptv.ui

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.Glide
import com.elarbi.omniiptv.R
import com.elarbi.omniiptv.data.Edits
import com.elarbi.omniiptv.data.EpgNorm
import com.elarbi.omniiptv.data.Library
import com.elarbi.omniiptv.data.LogoFinder
import com.elarbi.omniiptv.data.Repo
import com.elarbi.omniiptv.home.HomeSyncWorker
import com.elarbi.omniiptv.model.Channel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Gestion complète des chaînes : ordre, groupes, noms, masquage, guide TV, logos. */
class ChannelManagerActivity : FragmentActivity() {
    private lateinit var groupsView: ListView
    private lateinit var channelsView: ListView
    private lateinit var status: TextView
    private lateinit var btnSource: Button
    private var sid: String? = null
    private var groups: List<String> = emptyList()
    private var group: String? = null
    private var channels: List<Channel> = emptyList()
    private lateinit var channelAdapter: ChannelRowAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_manager)
        groupsView = findViewById(R.id.mg_groups)
        channelsView = findViewById(R.id.mg_channels)
        status = findViewById(R.id.mg_status)
        btnSource = findViewById(R.id.mg_source)
        channelAdapter = ChannelRowAdapter(this, emptyList())
        channelsView.adapter = channelAdapter
        sid = intent.getStringExtra("source") ?: Repo.activeSourceId

        btnSource.setOnClickListener { chooseSource() }
        findViewById<Button>(R.id.mg_logos).setOnClickListener { fillLogos() }
        findViewById<Button>(R.id.mg_prefetch).setOnClickListener { prefetchLogos() }
        findViewById<Button>(R.id.mg_epg).setOnClickListener { startActivity(Intent(this, EpgActivity::class.java)) }
        findViewById<Button>(R.id.mg_reset).setOnClickListener {
            val s = sid ?: return@setOnClickListener
            AlertDialog.Builder(this).setTitle("Réinitialiser toutes les personnalisations de cette source ?")
                .setPositiveButton("Réinitialiser") { _, _ -> Edits.reset(s); reload() }
                .setNegativeButton("Annuler", null).show()
        }
        groupsView.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                selectGroup(groups.getOrNull(position))
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        groupsView.setOnItemClickListener { _, _, pos, _ -> groups.getOrNull(pos)?.let { groupMenu(it) } }
        channelsView.setOnItemClickListener { _, _, pos, _ -> channels.getOrNull(pos)?.let { channelMenu(it) } }
        reload()
    }

    override fun onResume() {
        super.onResume()
        if (sid != null) reload()
    }

    private fun reload() {
        val s = sid
        val src = Repo.sources.firstOrNull { it.id == s }
        btnSource.text = "Source : ${src?.name ?: "aucune"}"
        if (src == null) { status.text = "Ajoutez d'abord une source."; return }
        groups = Edits.groups(src.id)
        val all = Edits.view(src.id, true)
        val labels = groups.map { g ->
            val n = all.count { it.group == g }
            (if (Edits.isGroupHidden(src.id, g)) "🚫 " else "") + "$g ($n)"
        }
        val keep = groupsView.selectedItemPosition.coerceAtLeast(0)
        groupsView.adapter = ArrayAdapter(this, R.layout.item_text, labels)
        if (groups.isNotEmpty()) {
            val sel = group?.let { groups.indexOf(it) }?.takeIf { it >= 0 } ?: keep.coerceAtMost(groups.size - 1)
            groupsView.setSelection(sel)
            selectGroup(groups[sel])
        }
        val stat = Repo.epgStat(src.id)
        status.text = "${all.size} chaînes, ${groups.size} groupes" + (if (stat.isNotBlank()) " • guide TV : $stat chaînes reconnues" else "")
    }

    private fun selectGroup(g: String?) {
        val s = sid ?: return
        group = g
        channels = if (g == null) emptyList() else Edits.view(s, true).filter { it.group == g }
        channelAdapter.items = channels.map { c -> if (Edits.isHidden(c)) c.copy(name = "🚫 " + c.name) else c }
        channelAdapter.notifyDataSetChanged()
    }

    private fun chooseSource() {
        val list = Repo.sources
        if (list.isEmpty()) return
        AlertDialog.Builder(this).setTitle("Source à gérer")
            .setItems(list.map { "${it.name} — ${it.typeLabel}" }.toTypedArray()) { _, w ->
                sid = list[w].id; group = null; reload()
            }.show()
    }

    private fun groupMenu(g: String) {
        val s = sid ?: return
        val hidden = Edits.isGroupHidden(s, g)
        val items = arrayOf("Monter", "Descendre", "Renommer", if (hidden) "Afficher le groupe" else "Masquer le groupe", "Mettre en tête")
        AlertDialog.Builder(this).setTitle(g).setItems(items) { _, w ->
            when (w) {
                0 -> Edits.moveGroup(s, g, -1)
                1 -> Edits.moveGroup(s, g, 1)
                2 -> prompt("Nouveau nom du groupe", g) { n -> if (n.isNotBlank()) { Edits.renameGroup(s, g, n); group = n } }
                3 -> Edits.toggleGroupHidden(s, g)
                4 -> repeat(groups.indexOf(g)) { Edits.moveGroup(s, g, -1) }
            }
            reload()
        }.show()
    }

    private fun channelMenu(ch: Channel) {
        val s = sid ?: return
        val hidden = Edits.isHidden(ch)
        val items = ArrayList<Pair<String, () -> Unit>>()
        items += "Regarder" to {
            Repo.playContext = channels
            startActivity(Intent(this, PlayerActivity::class.java).putExtra("key", ch.key))
        }
        items += "Renommer" to { prompt("Nom de la chaîne", ch.name) { n -> if (n.isNotBlank()) Edits.update(s, ch.key) { it.name = n }; reload() } }
        items += "Monter" to { Edits.moveChannel(s, ch.key, -1); reload() }
        items += "Descendre" to { Edits.moveChannel(s, ch.key, 1); reload() }
        items += "Déplacer vers un autre groupe" to { moveToGroup(ch) }
        items += (if (hidden) "Afficher la chaîne" else "Masquer la chaîne") to { Edits.update(s, ch.key) { it.hidden = !hidden }; reload() }
        items += (if (Repo.isFavorite(ch.key)) "Retirer des favoris" else "Ajouter aux favoris") to { Repo.toggleFavorite(ch); HomeSyncWorker.runNow(this); reload() }
        if (ch.isVod) items += (if (Library.isWatch(ch.key)) "Retirer de « À regarder »" else "Ajouter à « À regarder »") to { Library.toggleWatch(ch); reload() }
        items += "Associer au guide TV…" to { mapEpg(ch) }
        items += "Changer le logo (URL)…" to { prompt("Adresse de l'image du logo", ch.logo) { u -> Edits.update(s, ch.key) { it.logo = u.ifBlank { null } }; reload() } }
        items += "Rétablir les valeurs d'origine" to { Edits.clear(s, ch.key); reload() }
        AlertDialog.Builder(this).setTitle(ch.name)
            .setItems(items.map { it.first }.toTypedArray()) { _, w -> items[w].second() }
            .show()
    }

    private fun moveToGroup(ch: Channel) {
        val s = sid ?: return
        val opts = groups + "＋ Nouveau groupe…"
        AlertDialog.Builder(this).setTitle("Déplacer « ${ch.name} » vers")
            .setItems(opts.toTypedArray()) { _, w ->
                if (w == groups.size) prompt("Nom du nouveau groupe", "") { n -> if (n.isNotBlank()) { Edits.update(s, ch.key) { it.group = n }; reload() } }
                else { Edits.update(s, ch.key) { it.group = groups[w] }; reload() }
            }.show()
    }

    /** Association manuelle à une chaîne du guide TV, avec recherche par nom. */
    private fun mapEpg(ch: Channel) {
        val s = sid ?: return
        val index = Repo.epgIndex(s)
        if (index.isEmpty()) {
            Ui.toast(this, "Aucun guide TV chargé pour cette source. Ajoutez un guide dans « Guides TV » puis mettez à jour.")
            return
        }
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(40, 20, 40, 0) }
        val search = EditText(this).apply { setText(ch.name); setSingleLine(); setTextColor(0xFFFFFFFF.toInt()) }
        val results = ListView(this)
        box.addView(search)
        box.addView(results, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(this, 360)))
        var shown: List<Pair<String, String>> = emptyList()
        fun applyFilter(q: String) {
            val n = EpgNorm.norm(q)
            shown = index.filter { EpgNorm.norm(it.second).contains(n) || it.first.lowercase().contains(q.lowercase()) }.take(200)
            results.adapter = ArrayAdapter(this, R.layout.item_text, shown.map { "${it.second}   [${it.first}]" })
        }
        applyFilter(ch.name)
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(e: Editable?) { applyFilter(e?.toString().orEmpty()) }
        })
        val dlg = AlertDialog.Builder(this).setTitle("Guide TV pour « ${ch.name} »").setView(box)
            .setNegativeButton("Annuler", null)
            .setNeutralButton("Retirer l'association") { _, _ -> Edits.update(s, ch.key) { it.epgId = null }; reload() }
            .create()
        results.setOnItemClickListener { _, _, pos, _ ->
            val id = shown[pos].first
            Edits.update(s, ch.key) { it.epgId = id }
            dlg.dismiss()
            status.text = "Association enregistrée ($id). Mise à jour du guide…"
            refreshEpg()
        }
        dlg.show()
    }

    private fun refreshEpg() {
        val src = Repo.sources.firstOrNull { it.id == sid } ?: return
        lifecycleScope.launch {
            status.text = try {
                Repo.refreshEpg(src)
                "Guide TV mis à jour : ${Repo.epgStat(src.id)} chaînes reconnues."
            } catch (e: Exception) {
                "Guide TV : ${e.message}"
            }
            reload()
        }
    }

    private fun fillLogos() {
        val s = sid ?: return
        status.text = "Recherche des logos manquants dans la base iptv-org…"
        lifecycleScope.launch {
            status.text = try {
                val n = LogoFinder.fill(s)
                "$n logos ajoutés."
            } catch (e: Exception) {
                "Échec : ${e.message}"
            }
            reload()
        }
    }

    /** Télécharge toutes les icônes dans le cache pour un affichage instantané. */
    private fun prefetchLogos() {
        val s = sid ?: return
        val urls = Edits.view(s).map { it.logo }.filter { it.startsWith("http") }.distinct()
        status.text = "Téléchargement de ${urls.size} icônes…"
        lifecycleScope.launch {
            var ok = 0
            withContext(Dispatchers.IO) {
                urls.forEachIndexed { i, u ->
                    try {
                        Glide.with(applicationContext).downloadOnly().load(u).submit().get()
                        ok++
                    } catch (_: Exception) {
                    }
                    if (i % 20 == 0) withContext(Dispatchers.Main) { status.text = "Icônes : ${i + 1}/${urls.size}" }
                }
            }
            status.text = "$ok icônes enregistrées sur l'appareil."
        }
    }

    private fun prompt(title: String, value: String, onOk: (String) -> Unit) {
        val e = EditText(this).apply { setText(value); setSingleLine(); setTextColor(0xFFFFFFFF.toInt()); selectAll() }
        AlertDialog.Builder(this).setTitle(title).setView(e)
            .setPositiveButton("OK") { _, _ -> onOk(e.text.toString().trim()) }
            .setNegativeButton("Annuler", null).show()
    }
}
