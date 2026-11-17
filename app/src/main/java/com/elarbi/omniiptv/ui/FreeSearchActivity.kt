package com.elarbi.omniiptv.ui

import android.content.Intent
import android.os.Bundle
import android.view.inputmethod.EditorInfo
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.elarbi.omniiptv.R
import com.elarbi.omniiptv.data.FreeCatalog
import com.elarbi.omniiptv.data.Http
import com.elarbi.omniiptv.data.Repo
import com.elarbi.omniiptv.home.HomeSyncWorker
import com.elarbi.omniiptv.model.Channel
import com.elarbi.omniiptv.model.Source
import com.elarbi.omniiptv.model.SourceType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/** Recherche de chaînes gratuites dans des catalogues publics, avec test de disponibilité. */
class FreeSearchActivity : FragmentActivity() {
    private lateinit var query: EditText
    private lateinit var status: TextView
    private lateinit var listView: ListView
    private lateinit var adapter: ArrayAdapter<String>
    private var results: List<Channel> = emptyList()
    private val alive = HashMap<String, Boolean>()
    private var label = ""
    private var catalogUrl: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_free)
        query = findViewById(R.id.free_query)
        status = findViewById(R.id.free_status)
        listView = findViewById(R.id.free_list)
        adapter = ArrayAdapter(this, R.layout.item_text, ArrayList())
        listView.adapter = adapter
        listView.setOnItemClickListener { _, _, pos, _ ->
            results.getOrNull(pos)?.let { ch ->
                Repo.playContext = results
                startActivity(Intent(this, PlayerActivity::class.java).putExtra("key", ch.key))
            }
        }
        val presets = findViewById<LinearLayout>(R.id.free_presets)
        FreeCatalog.presets.forEach { p -> presets.addView(Ui.button(this, p.label, fullWidth = false) { loadPreset(p) }) }

        findViewById<Button>(R.id.btn_free_search).setOnClickListener { keywordSearch() }
        query.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_SEARCH || action == EditorInfo.IME_ACTION_DONE) { keywordSearch(); true } else false
        }
        findViewById<Button>(R.id.btn_free_test).setOnClickListener { testAll() }
        findViewById<Button>(R.id.btn_free_save).setOnClickListener { saveSelection() }
        findViewById<Button>(R.id.btn_free_catalog).setOnClickListener { followCatalog() }
        status.text = "Choisissez un pays, une langue ou un thème, ou tapez un nom de chaîne."
    }

    private fun loadPreset(p: FreeCatalog.Preset) {
        label = p.label
        catalogUrl = p.url
        runSearch("Chargement du catalogue « ${p.label} »…") { FreeCatalog.load(p.url) }
    }

    private fun keywordSearch() {
        val k = query.text.toString().trim()
        if (k.length < 2) { status.text = "Tapez au moins 2 caractères."; return }
        label = k
        catalogUrl = null
        runSearch("Recherche de « $k » dans le catalogue mondial (≈10 000 chaînes)…") { FreeCatalog.search(k) }
    }

    private fun runSearch(msg: String, block: suspend () -> List<Channel>) {
        status.text = msg
        lifecycleScope.launch {
            try {
                results = block()
                alive.clear()
                show()
                status.text = "${results.size} chaînes trouvées. OK pour regarder, ou « Tester la disponibilité »."
                if (results.isNotEmpty()) listView.requestFocus()
            } catch (e: Exception) {
                status.text = "Erreur : ${e.message}"
            }
        }
    }

    private fun show() {
        adapter.clear()
        adapter.addAll(results.map { ch ->
            val mark = when (alive[ch.key]) { true -> "✅ "; false -> "❌ "; null -> "" }
            "$mark${ch.name}   ·   ${ch.group}"
        })
        adapter.notifyDataSetChanged()
    }

    private fun testAll() {
        if (results.isEmpty()) { status.text = "Aucun résultat à tester."; return }
        val todo = results.take(500)
        status.text = "Test de ${todo.size} flux en cours…"
        lifecycleScope.launch {
            val sem = Semaphore(12)
            var done = 0
            coroutineScope {
                todo.forEach { ch ->
                    launch {
                        val ok = sem.withPermit { withContext(Dispatchers.IO) { Http.isAlive(ch.url, ch.userAgent) } }
                        alive[ch.key] = ok
                        done++
                        if (done % 25 == 0 || done == todo.size) {
                            status.text = "Testés : $done/${todo.size} — en ligne : ${alive.values.count { it }}"
                        }
                    }
                }
            }
            results = results.sortedByDescending { alive[it.key] == true }
            show()
            status.text = "Terminé : ${alive.values.count { it }} chaînes en ligne sur ${todo.size} testées (en tête de liste)."
        }
    }

    private fun saveSelection() {
        val chosen = if (alive.isNotEmpty()) results.filter { alive[it.key] == true } else results
        if (chosen.isEmpty()) { status.text = "Rien à enregistrer."; return }
        val id = UUID.randomUUID().toString().take(8)
        val f = File(filesDir, "free_$id.m3u")
        f.writeText(buildString {
            append("#EXTM3U\n")
            chosen.forEach { c ->
                append("#EXTINF:-1 tvg-id=\"${c.epgId}\" tvg-logo=\"${c.logo}\" group-title=\"${c.group}\",${c.name}\n")
                if (c.userAgent.isNotBlank()) append("#EXTVLCOPT:http-user-agent=${c.userAgent}\n")
                if (c.referer.isNotBlank()) append("#EXTVLCOPT:http-referrer=${c.referer}\n")
                append(c.url).append('\n')
            }
        })
        addSource(Source(id, SourceType.M3U, "Gratuit · $label", f.absolutePath))
    }

    private fun followCatalog() {
        val url = catalogUrl ?: run { status.text = "Choisissez d'abord un catalogue (bouton pays / langue / thème)."; return }
        addSource(Source(UUID.randomUUID().toString().take(8), SourceType.M3U, "Catalogue · $label", url))
    }

    private fun addSource(s: Source) {
        Repo.upsertSource(s)
        Repo.activeSourceId = s.id
        status.text = "Enregistrement de « ${s.name} »…"
        ProgressUi.run(this, "Chargement de « ${s.name} »", { Repo.refresh(s) }) { r ->
            try {
                val n = r.getOrThrow()
                status.text = "Source « ${s.name} » ajoutée ($n chaînes). Elle est maintenant affichée à l'accueil de l'application."
                HomeSyncWorker.runNow(this)
            } catch (e: Exception) {
                status.text = "Échec : ${e.message}"
            }
        }
    }
}
