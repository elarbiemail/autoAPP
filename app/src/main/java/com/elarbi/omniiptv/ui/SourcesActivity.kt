package com.elarbi.omniiptv.ui

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.elarbi.omniiptv.R
import com.elarbi.omniiptv.data.Repo
import com.elarbi.omniiptv.home.HomeSyncWorker
import com.elarbi.omniiptv.model.Source
import com.elarbi.omniiptv.model.SourceType
import kotlinx.coroutines.launch

class SourcesActivity : FragmentActivity() {
    private lateinit var container: LinearLayout
    private lateinit var info: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_sources)
        container = findViewById(R.id.src_list)
        info = findViewById(R.id.src_info)
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun render() {
        container.removeAllViews()
        info.text = "Adresse MAC de cet appareil (à donner à votre fournisseur pour un portail MAC) : ${Repo.deviceMac}\n" +
            "Sources affichées : ${com.elarbi.omniiptv.data.Shown.title()}"
        val active = Repo.activeSourceId
        val shownIds = com.elarbi.omniiptv.data.Shown.sources().map { it.id }
        Repo.sources.forEach { s ->
            val mark = if (s.id in shownIds) "● " else "○ "
            container.addView(Ui.button(this, "$mark${s.name}  —  ${s.typeLabel}  (${Repo.channels(s.id).size} chaînes)") { options(s) })
        }
        container.addView(Ui.button(this, "☰ Afficher plusieurs sources ensemble…") { SourcePicker.pickSeveral(this) { render() } })
        container.addView(Ui.button(this, "＋ Ajouter une liste M3U") { add(SourceType.M3U) })
        container.addView(Ui.button(this, "＋ Ajouter Xtream Codes") { add(SourceType.XTREAM) })
        container.addView(Ui.button(this, "＋ Ajouter un portail MAC") { add(SourceType.STALKER) })
        container.addView(Ui.button(this, "Dépôts…") { startActivity(Intent(this, ReposActivity::class.java)) })
        container.addView(Ui.button(this, "＋ Ajouter une chaîne YouTube Live") { startActivity(Intent(this, YoutubeActivity::class.java)) })
        container.addView(Ui.button(this, "Listes gratuites…") { startActivity(Intent(this, FreeSearchActivity::class.java)) })
        container.addView(Ui.button(this, "Générer une nouvelle adresse MAC") {
            AlertDialog.Builder(this).setTitle("Nouvelle adresse MAC")
                .setMessage("Les portails déjà enregistrés avec l'ancienne adresse ne fonctionneront plus. Continuer ?")
                .setPositiveButton("Oui") { _, _ -> Repo.regenerateMac(); render() }
                .setNegativeButton("Non", null).show()
        })
        container.getChildAt(0)?.requestFocus()
    }

    private fun add(t: SourceType) = startActivity(Intent(this, AddSourceActivity::class.java).putExtra("type", t.name))

    private fun options(s: Source) {
        AlertDialog.Builder(this)
            .setTitle(s.name)
            .setItems(
                arrayOf("Afficher cette source", "Actualiser chaînes et guide TV", "Modifier", "Supprimer") +
                    if (s.type == SourceType.STALKER) arrayOf("Infos du compte et diagnostic du portail") else emptyArray()
            ) { _, which ->
                when (which) {
                    0 -> { com.elarbi.omniiptv.data.Shown.showOnly(s.id); render() }
                    1 -> refresh(s)
                    2 -> if (s.type == SourceType.YOUTUBE) startActivity(Intent(this, YoutubeActivity::class.java))
                    else startActivity(Intent(this, AddSourceActivity::class.java).putExtra("id", s.id))
                    4 -> diagnostic(s)
                    3 -> AlertDialog.Builder(this).setTitle("Supprimer « ${s.name} » ?")
                        .setPositiveButton("Supprimer") { _, _ -> Repo.removeSource(s.id); render() }
                        .setNegativeButton("Annuler", null).show()
                }
            }
            .show()
    }

    private fun diagnostic(s: Source) {
        Ui.toast(this, "Connexion au portail…")
        lifecycleScope.launch {
            val text = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                try { com.elarbi.omniiptv.data.StalkerClient(s).diagnostic() } catch (e: Exception) { "Erreur : ${e.message}" }
            }
            AlertDialog.Builder(this@SourcesActivity).setTitle(s.name).setMessage(text).setPositiveButton("OK", null).show()
        }
    }

    private fun refresh(s: Source) {
        ProgressUi.run(this, "Actualisation de « ${s.name} »", { Repo.refresh(s) }) { r ->
            r.onSuccess { n -> Ui.toast(this, "$n éléments chargés"); HomeSyncWorker.runNow(this) }
                .onFailure { e -> Ui.toast(this, "Échec : ${e.message}") }
            render()
        }
    }
}
