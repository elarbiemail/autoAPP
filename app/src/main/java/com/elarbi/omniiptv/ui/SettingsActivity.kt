package com.elarbi.omniiptv.ui

import android.app.AlertDialog
import android.os.Bundle
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.fragment.app.FragmentActivity
import com.elarbi.omniiptv.data.Backup
import com.elarbi.omniiptv.data.Edits
import com.elarbi.omniiptv.data.Prefs
import com.elarbi.omniiptv.data.Reminders
import com.elarbi.omniiptv.data.Repo
import com.elarbi.omniiptv.model.SourceType

/** Réglages : démarrage, lecteur, contrôle parental, rappels, sauvegarde. */
class SettingsActivity : FragmentActivity() {
    private lateinit var box: LinearLayout

    private val views = listOf(
        "rows" to "Accueil par rangées", "list" to "Liste", "grid" to "Grille",
        "guide" to "Guide TV", "multi" to "Multi-écran"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val scroll = ScrollView(this)
        box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(this@SettingsActivity, 64), Ui.dp(this@SettingsActivity, 28), Ui.dp(this@SettingsActivity, 64), Ui.dp(this@SettingsActivity, 28))
        }
        scroll.addView(box)
        setContentView(scroll)
        render()
    }

    private fun title(t: String) = box.addView(TextView(this).apply {
        text = t; textSize = 20f; setTextColor(0xFFFF9F1C.toInt()); setPadding(0, Ui.dp(this@SettingsActivity, 16), 0, Ui.dp(this@SettingsActivity, 4))
    })

    private fun item(label: String, action: () -> Unit) = box.addView(Ui.button(this, label) { action(); render() })

    private fun go(label: String, cls: Class<*>) =
        box.addView(Ui.button(this, label) { startActivity(android.content.Intent(this, cls)) })

    private fun add(label: String, t: SourceType) =
        box.addView(Ui.button(this, label) { startActivity(android.content.Intent(this, AddSourceActivity::class.java).putExtra("type", t.name)) })

    private fun browse(label: String, mode: String) =
        box.addView(Ui.button(this, label) { startActivity(android.content.Intent(this, BrowseActivity::class.java).putExtra("mode", mode)) })

    /** Action exécutée par l'accueil de l'application (actualisation, rangées, tuner…). */
    private fun main(label: String, id: Int) =
        box.addView(Ui.button(this, label) { MainActivity.runAction(this, id) })

    private fun onOff(b: Boolean) = if (b) "activé" else "désactivé"

    private fun render() {
        box.removeAllViews()
        box.addView(TextView(this).apply { text = "⚙ Réglages"; textSize = 28f; setTextColor(0xFFFFFFFF.toInt()) })
        box.addView(TextView(this).apply {
            text = "Source affichée : ${Repo.activeSource()?.name ?: "aucune"}  •  ${Repo.sources.size} source(s)"
            setTextColor(0x99FFFFFF.toInt())
        })

        title("Sources et contenus")
        go("Mes sources (afficher, actualiser, modifier, supprimer)", SourcesActivity::class.java)
        add("＋ Ajouter une liste M3U", SourceType.M3U)
        add("＋ Ajouter un compte Xtream Codes", SourceType.XTREAM)
        add("＋ Ajouter un portail MAC (Stalker)", SourceType.STALKER)
        main("＋ Chaînes du téléviseur (TNT, satellite, câble)", MainFragment.A_TUNER)
        go("Chaînes YouTube Live", YoutubeActivity::class.java)
        go("Dépôts (installer des listes en un clic)", ReposActivity::class.java)
        go("Listes gratuites et légales", FreeSearchActivity::class.java)
        main("Actualiser la source affichée (chaînes et guide)", MainFragment.A_REFRESH)

        title("Chaînes et guide TV")
        go("Gestionnaire de chaînes (ordre, groupes, noms, logos)", ChannelManagerActivity::class.java)
        go("Guides TV (EPG automatique et supplémentaire)", EpgActivity::class.java)
        go("Rechercher une chaîne ou un film", SearchActivity::class.java)

        title("Affichage")
        go("Guide TV en grille", GuideActivity::class.java)
        browse("Vue liste", "list")
        browse("Vue grille", "grid")
        go("Multi-écran (4 chaînes)", MultiViewActivity::class.java)
        item("Vue à l'ouverture : ${views.first { it.first == Prefs.startView }.second}") {
            val i = views.indexOfFirst { it.first == Prefs.startView }
            Prefs.startView = views[(i + 1) % views.size].first
        }

        title("Écran d'accueil du téléviseur")
        main("Rangée Omni IPTV : mettre à jour et explications", MainFragment.A_HOME)
        main("Ajouter la rangée « En ce moment »", MainFragment.A_HOME_LIVE)
        main("Publier les chaînes dans l'onglet Direct", MainFragment.A_LIVE_TAB)

        title("Démarrage")
        item("Lancer la dernière chaîne regardée : ${onOff(Prefs.autoPlayLast)}") { Prefs.autoPlayLast = !Prefs.autoPlayLast }
        item("Actualiser la source au démarrage : ${onOff(Prefs.refreshOnStart)}") { Prefs.refreshOnStart = !Prefs.refreshOnStart }

        title("Lecteur")
        val bufLabel = mapOf("low" to "faible (zapping rapide)", "normal" to "normal", "high" to "élevé (connexion instable)")
        item("Mémoire tampon : ${bufLabel[Prefs.buffer]}") {
            Prefs.buffer = when (Prefs.buffer) { "low" -> "normal"; "normal" -> "high"; else -> "low" }
        }
        item("Reconnexion automatique en cas de coupure : ${onOff(Prefs.autoReconnect)}") { Prefs.autoReconnect = !Prefs.autoReconnect }
        val aspects = listOf("adapter", "zoom", "étirer")
        item("Format d'image par défaut : ${aspects[Prefs.aspect.coerceIn(0, 2)]}") { Prefs.aspect = (Prefs.aspect + 1) % 3 }
        item("Afficher l'heure dans le bandeau : ${onOff(Prefs.showClock)}") { Prefs.showClock = !Prefs.showClock }

        title("Contrôle parental")
        item(if (Prefs.pin.isBlank()) "Définir un code parental" else "Changer ou supprimer le code parental") { pinDialog() }
        item("Masquer les catégories pour adultes : ${onOff(Prefs.hideAdult)}") { Prefs.hideAdult = !Prefs.hideAdult }
        item("Groupes verrouillés : ${Prefs.lockedGroups.size} (les groupes adultes le sont d'office avec un code)") { lockGroups() }

        title("Rappels de programmes")
        val rem = Reminders.list()
        if (rem.isEmpty()) box.addView(TextView(this).apply { text = "Aucun rappel. Programmez-en depuis le Guide TV (OK sur un programme à venir)."; setTextColor(0x99FFFFFF.toInt()) })
        rem.sortedBy { it.start }.forEach { r ->
            item("⏰ ${Ui.hm(r.start)} — ${r.title} (${r.channelName}) : OK pour annuler") { Reminders.toggle(this, r) }
        }

        title("Sauvegarde")
        item("Exporter la configuration (fichier dans Téléchargements)") {
            try {
                val where = Backup.export(this)
                AlertDialog.Builder(this).setTitle("Sauvegarde enregistrée")
                    .setMessage("Fichier : $where\n\nPour restaurer : Outils > Dépôts, puis saisir le chemin complet du fichier " +
                        "(ex. /storage/emulated/0/Download/omni-iptv-sauvegarde.json) ou son adresse en ligne.\n" +
                        "Le fichier contient vos identifiants : ne le publiez pas.")
                    .setPositiveButton("OK", null).show()
            } catch (e: Exception) {
                Ui.toast(this, "Échec : ${e.message}")
            }
        }
        title("Aide")
        main("Raccourcis de la télécommande et mode d'emploi", MainFragment.A_ABOUT)
        box.addView(TextView(this).apply {
            text = "Omni IPTV ${try { packageManager.getPackageInfo(packageName, 0).versionName } catch (e: Exception) { "" }}"
            setTextColor(0x66FFFFFF.toInt()); setPadding(0, Ui.dp(this@SettingsActivity, 16), 0, 0)
        })
        title("Réinitialisation")
        item("Réinitialiser les personnalisations de la source affichée") {
            Repo.activeSourceId?.let { Edits.reset(it) }
            Ui.toast(this, "Personnalisations réinitialisées")
        }
    }

    private fun pinDialog() {
        val e = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            hint = "Nouveau code (vide = supprimer)"
            setTextColor(0xFFFFFFFF.toInt())
        }
        val doApply = {
            AlertDialog.Builder(this).setTitle("Code parental").setView(e)
                .setPositiveButton("Enregistrer") { _, _ ->
                    Prefs.pin = e.text.toString().trim()
                    Prefs.unlocked = false
                    Ui.toast(this, if (Prefs.pin.isBlank()) "Code supprimé" else "Code enregistré")
                    render()
                }
                .setNegativeButton("Annuler", null).show()
            Unit
        }
        if (Prefs.pin.isBlank()) doApply() else Ui.askPin(this) { doApply() }
    }

    private fun lockGroups() {
        val src = Repo.activeSource() ?: run { Ui.toast(this, "Aucune source"); return }
        val groups = Edits.groups(src.id)
        val locked = Prefs.lockedGroups.toMutableSet()
        val checked = BooleanArray(groups.size) { groups[it] in locked }
        val go = {
            AlertDialog.Builder(this).setTitle("Groupes à verrouiller")
                .setMultiChoiceItems(groups.toTypedArray(), checked) { _, w, on -> if (on) locked.add(groups[w]) else locked.remove(groups[w]) }
                .setPositiveButton("Enregistrer") { _, _ ->
                    Prefs.lockedGroups = locked
                    if (Prefs.pin.isBlank()) Ui.toast(this, "Pensez à définir un code parental")
                    render()
                }
                .setNegativeButton("Annuler", null).show()
            Unit
        }
        if (Prefs.pin.isBlank()) go() else Ui.askPin(this) { go() }
    }
}
