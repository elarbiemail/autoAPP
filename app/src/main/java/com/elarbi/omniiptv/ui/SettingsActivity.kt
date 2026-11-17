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

    private fun onOff(b: Boolean) = if (b) "activé" else "désactivé"

    private fun render() {
        box.removeAllViews()
        box.addView(TextView(this).apply { text = "Réglages"; textSize = 28f; setTextColor(0xFFFFFFFF.toInt()) })

        title("Démarrage")
        item("Vue à l'ouverture : ${views.first { it.first == Prefs.startView }.second}") {
            val i = views.indexOfFirst { it.first == Prefs.startView }
            Prefs.startView = views[(i + 1) % views.size].first
        }
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
