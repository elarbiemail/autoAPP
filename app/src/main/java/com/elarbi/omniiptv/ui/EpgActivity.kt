package com.elarbi.omniiptv.ui

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.elarbi.omniiptv.R
import com.elarbi.omniiptv.data.Repo
import com.elarbi.omniiptv.home.HomeSyncWorker
import com.elarbi.omniiptv.model.SourceType
import kotlinx.coroutines.launch

/** Guides TV : automatique (source), guides supplémentaires, suggestions publiques. */
class EpgActivity : FragmentActivity() {
    private lateinit var info: TextView
    private lateinit var status: TextView
    private lateinit var list: LinearLayout
    private lateinit var fUrl: EditText

    private val suggestions = listOf(
        "France" to "https://epgshare01.online/epgshare01/epg_ripper_FR1.xml.gz",
        "Chaînes arabes / Émirats" to "https://epgshare01.online/epgshare01/epg_ripper_AE1.xml.gz",
        "Al Jazeera" to "https://epgshare01.online/epgshare01/epg_ripper_ALJAZEERA1.xml.gz",
        "beIN Sports" to "https://epgshare01.online/epgshare01/epg_ripper_BEIN1.xml.gz",
        "Royaume-Uni" to "https://epgshare01.online/epgshare01/epg_ripper_UK1.xml.gz",
        "Espagne" to "https://epgshare01.online/epgshare01/epg_ripper_ES1.xml.gz",
        "Canada" to "https://epgshare01.online/epgshare01/epg_ripper_CA2.xml.gz"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_epg)
        info = findViewById(R.id.epg_info)
        status = findViewById(R.id.epg_status)
        list = findViewById(R.id.epg_list)
        fUrl = findViewById(R.id.epg_url)
        findViewById<Button>(R.id.epg_add).setOnClickListener { add(fUrl.text.toString()) }
        findViewById<Button>(R.id.epg_update).setOnClickListener { updateAll() }
        val sug = findViewById<LinearLayout>(R.id.epg_suggest)
        suggestions.forEach { (label, url) -> sug.addView(Ui.button(this, "＋ $label") { add(url) }) }
        render()
    }

    private fun render() {
        val lines = Repo.sources.filter { it.type != SourceType.YOUTUBE }.joinToString("\n") { s ->
            val auto = when {
                s.epgUrl.isNotBlank() -> "guide personnalisé"
                s.type == SourceType.STALKER -> "guide du portail"
                Repo.autoEpgUrl(s.id).isNotBlank() -> "guide automatique (${if (s.type == SourceType.XTREAM) "serveur Xtream" else "en-tête url-tvg"})"
                else -> "pas de guide automatique"
            }
            val stat = Repo.epgStat(s.id).ifBlank { "non chargé" }
            "• ${s.name} : $auto — chaînes reconnues : $stat"
        }
        info.text = "Le guide est chargé automatiquement depuis chaque source (Xtream, portail MAC, url-tvg des listes M3U), " +
            "complété par vos guides supplémentaires. Les chaînes sont reconnues par identifiant puis par nom ; " +
            "l'association manuelle se fait dans le gestionnaire de chaînes. Mise à jour automatique toutes les 6 heures.\n\n$lines"
        list.removeAllViews()
        val urls = Repo.globalEpgUrls()
        if (urls.isEmpty()) list.addView(TextView(this).apply { text = "Aucun."; setTextColor(0x99FFFFFF.toInt()) })
        urls.forEach { u ->
            list.addView(Ui.button(this, u) {
                Repo.setGlobalEpgUrls(Repo.globalEpgUrls() - u)
                status.text = "Guide retiré."
                render()
            })
        }
    }

    private fun add(raw: String) {
        val u = raw.trim()
        if (!u.startsWith("http")) { status.text = "Adresse invalide."; return }
        Repo.setGlobalEpgUrls(Repo.globalEpgUrls() + u)
        fUrl.setText("")
        status.text = "Guide ajouté. Appuyez sur « Mettre à jour » pour le charger."
        render()
    }

    private fun updateAll() {
        val srcs = Repo.sources.filter { it.type != SourceType.YOUTUBE }
        status.text = "Mise à jour des guides (peut prendre une minute)…"
        lifecycleScope.launch {
            val msgs = ArrayList<String>()
            for (s in srcs) {
                status.text = "Guide de « ${s.name} »…"
                msgs += try { Repo.refreshEpg(s); "${s.name} : ${Repo.epgStat(s.id)}" } catch (e: Exception) { "${s.name} : ${e.message}" }
            }
            status.text = "Terminé.\n" + msgs.joinToString("\n")
            HomeSyncWorker.runNow(this@EpgActivity)
            render()
        }
    }
}
