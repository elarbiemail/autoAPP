package com.elarbi.omniiptv.ui

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.elarbi.omniiptv.R
import com.elarbi.omniiptv.data.Repos
import com.elarbi.omniiptv.home.HomeSyncWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Gestion des dépôts : ajout par URL, raccourci GitHub ou lien omniiptv://repo?url=… */
class ReposActivity : FragmentActivity() {
    private lateinit var fUrl: EditText
    private lateinit var status: TextView
    private lateinit var list: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_repos)
        fUrl = findViewById(R.id.repo_url)
        status = findViewById(R.id.repo_status)
        list = findViewById(R.id.repo_list)
        findViewById<Button>(R.id.repo_add).setOnClickListener { addRepo(fUrl.text.toString()) }
        findViewById<Button>(R.id.repo_refresh).setOnClickListener { refreshAll() }
        render()
        handleLink(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleLink(intent)
    }

    private fun handleLink(i: Intent?) {
        val url = i?.data?.getQueryParameter("url") ?: return
        AlertDialog.Builder(this).setTitle("Ajouter ce dépôt ?").setMessage(url)
            .setPositiveButton("Ajouter") { _, _ -> addRepo(url) }
            .setNegativeButton("Annuler", null).show()
    }

    private fun addRepo(input: String) {
        if (input.isBlank()) { status.text = "Saisissez l'adresse du dépôt."; return }
        status.text = "Lecture du dépôt…"
        lifecycleScope.launch {
            try {
                val r = Repos.add(this@ReposActivity, input)
                status.text = "Dépôt « ${r.name} » ajouté : ${r.items.size} éléments."
                fUrl.setText("")
                render()
                openRepo(r.url)
            } catch (e: Exception) {
                status.text = "Impossible de lire ce dépôt : ${e.message}"
            }
        }
    }

    private fun refreshAll() {
        status.text = "Mise à jour des dépôts…"
        lifecycleScope.launch {
            val n = Repos.refreshAll(this@ReposActivity)
            status.text = "$n dépôt(s) mis à jour."
            render()
        }
    }

    private fun render() {
        list.removeAllViews()
        Repos.list(this).forEach { r ->
            val installed = r.items.count { Repos.isInstalled(it) }
            val by = if (r.author.isNotBlank()) " — ${r.author}" else ""
            list.addView(Ui.button(this, "${r.name}$by   ($installed/${r.items.size} installés)") { openRepo(r.url) })
        }
    }

    private fun openRepo(url: String) {
        val r = Repos.list(this).firstOrNull { it.url == url } ?: return
        val labels = r.items.map { i ->
            val mark = if (Repos.isInstalled(i)) "✓ " else "＋ "
            val kind = when (i.type) {
                "m3u" -> "M3U"; "xtream" -> "Xtream"; "stalker" -> "Portail MAC"
                "youtube" -> "YouTube"; "repo" -> "Dépôt"; else -> i.type
            }
            "$mark${i.name}  [$kind]" + if (i.group.isNotBlank()) "  · ${i.group}" else ""
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(r.name + if (r.description.isNotBlank()) "\n${r.description}" else "")
            .setItems(labels) { _, w -> install(listOf(r.items[w])) }
            .setPositiveButton("Tout installer") { _, _ -> install(r.items) }
            .setNeutralButton("Supprimer le dépôt") { _, _ -> Repos.remove(this, r.url); render() }
            .setNegativeButton("Fermer", null)
            .show()
    }

    private fun install(items: List<Repos.RepoItem>) {
        lifecycleScope.launch {
            val msgs = ArrayList<String>()
            items.forEachIndexed { n, item ->
                status.text = "Installation ${n + 1}/${items.size} : ${item.name}…"
                msgs += try {
                    withContext(Dispatchers.IO) { Repos.install(this@ReposActivity, item) }
                } catch (e: Exception) {
                    "« ${item.name} » : échec (${e.message})"
                }
            }
            status.text = if (msgs.size == 1) msgs[0] else "${items.size} éléments traités.\n" + msgs.takeLast(6).joinToString("\n")
            HomeSyncWorker.runNow(this@ReposActivity)
            render()
        }
    }
}
