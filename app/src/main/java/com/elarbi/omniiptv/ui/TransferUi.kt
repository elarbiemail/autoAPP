package com.elarbi.omniiptv.ui

import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.text.InputType
import android.view.Gravity
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.elarbi.omniiptv.data.Repo
import com.elarbi.omniiptv.data.SourceTransfer
import com.elarbi.omniiptv.model.Source
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Écrans d'envoi (téléphone) et de réception (téléviseur) des sources sur le réseau local. */
object TransferUi {

    /** Téléviseur : attend les sources envoyées par le téléphone ; [done] reçoit les sources nouvelles. */
    fun receive(a: FragmentActivity, done: (List<Source>) -> Unit) {
        val code = (1000..9999).random().toString()
        val before = Repo.sources.map { it.id }.toSet()
        var dlg: AlertDialog? = null
        val rcv = SourceTransfer.Receiver(a, code) { summary ->
            a.runOnUiThread {
                dlg?.dismiss()
                val fresh = Repo.sources.filter { it.id !in before }
                AlertDialog.Builder(a).setTitle("Sources reçues")
                    .setMessage(summary + if (fresh.isNotEmpty()) "\n\nCharger maintenant leurs chaînes, films et séries ?" else "")
                    .setPositiveButton(if (fresh.isNotEmpty()) "Charger" else "OK") { _, _ -> done(fresh) }
                    .setNegativeButton(if (fresh.isNotEmpty()) "Plus tard" else "Fermer") { _, _ -> done(emptyList()) }
                    .show()
            }
        }
        try { rcv.start() } catch (e: Exception) { Ui.toast(a, "Réception impossible : ${e.message}"); return }
        val ip = SourceTransfer.localAddress()
        val d = { v: Int -> Ui.dp(a, v) }
        val box = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(d(24), d(12), d(24), d(8)) }
        box.addView(TextView(a).apply {
            text = "Sur le téléphone : Omni IPTV › Plus (Réglages) › Sources › « 📺 Envoyer mes sources vers le téléviseur »."
            textSize = 17f; setTextColor(Color.WHITE)
        })
        box.addView(TextView(a).apply { text = "Code à saisir sur le téléphone"; textSize = 15f; setTextColor(0x99FFFFFF.toInt()); setPadding(0, d(16), 0, 0) })
        box.addView(TextView(a).apply {
            text = code.toCharArray().joinToString("  ")
            textSize = 46f; setTypeface(typeface, Typeface.BOLD); setTextColor(ThemeManager.accent); gravity = Gravity.CENTER
        })
        box.addView(TextView(a).apply {
            text = "Le téléphone trouve ce téléviseur tout seul s'il est sur le même réseau Wi-Fi.\n" +
                "Sinon, saisissez sur le téléphone l'adresse : ${ip ?: "(réseau introuvable)"}:${rcv.port}"
            textSize = 14f; setTextColor(0x99FFFFFF.toInt()); setPadding(0, d(8), 0, 0)
        })
        dlg = AlertDialog.Builder(a).setTitle("📲 Recevoir des sources du téléphone")
            .setView(box)
            .setNegativeButton("Fermer", null)
            .setOnDismissListener { rcv.stop() }
            .show()
    }

    /** Téléphone : cherche les téléviseurs, demande le code et envoie les sources. */
    fun send(a: FragmentActivity) {
        if (Repo.sources.none { it.type == com.elarbi.omniiptv.model.SourceType.M3U || it.type == com.elarbi.omniiptv.model.SourceType.XTREAM || it.type == com.elarbi.omniiptv.model.SourceType.STALKER }) {
            Ui.toast(a, "Aucune source à envoyer : ajoutez d'abord une liste M3U, un compte Xtream ou un portail MAC.")
            return
        }
        Ui.toast(a, "Recherche des téléviseurs sur le réseau…")
        a.lifecycleScope.launch {
            val tvs = withContext(Dispatchers.IO) { SourceTransfer.discover(a) }
            val labels = tvs.map { "📺 ${it.name}   (${it.host})" } + "⌨ Saisir l'adresse affichée sur le téléviseur…"
            AlertDialog.Builder(a)
                .setTitle(if (tvs.isEmpty()) "Aucun téléviseur trouvé — ouvrez « Recevoir » sur le téléviseur" else "Envoyer vers…")
                .setItems(labels.toTypedArray()) { _, w ->
                    if (w < tvs.size) askCode(a, tvs[w].host, tvs[w].port) else askAddress(a)
                }
                .setNegativeButton("Annuler", null).show()
        }
    }

    private fun askAddress(a: FragmentActivity) {
        val e = EditText(a).apply {
            hint = "ex. 192.168.1.23:47860"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        }
        AlertDialog.Builder(a).setTitle("Adresse du téléviseur").setView(e)
            .setPositiveButton("Suivant") { _, _ ->
                val t = e.text.toString().trim().removePrefix("http://").trimEnd('/')
                val host = t.substringBefore(':')
                val port = t.substringAfter(':', "${SourceTransfer.PORT}").toIntOrNull() ?: SourceTransfer.PORT
                if (host.isBlank()) Ui.toast(a, "Adresse vide") else askCode(a, host, port)
            }.setNegativeButton("Annuler", null).show()
    }

    private fun askCode(a: FragmentActivity, host: String, port: Int) {
        val e = EditText(a).apply {
            hint = "Code à 4 chiffres"
            inputType = InputType.TYPE_CLASS_NUMBER
        }
        AlertDialog.Builder(a).setTitle("Code affiché sur le téléviseur").setView(e)
            .setPositiveButton("Envoyer sources et listes de favoris") { _, _ -> doSend(a, host, port, e.text.toString().trim(), true) }
            .setNeutralButton("Sources seulement") { _, _ -> doSend(a, host, port, e.text.toString().trim(), false) }
            .setNegativeButton("Annuler", null).show()
    }

    private fun doSend(a: FragmentActivity, host: String, port: Int, code: String, withLists: Boolean) {
        if (code.length != 4) { Ui.toast(a, "Le code fait 4 chiffres."); return }
        a.lifecycleScope.launch {
            val res = withContext(Dispatchers.IO) {
                try { Result.success(SourceTransfer.send(host, port, code, withLists)) } catch (e: Exception) { Result.failure(e) }
            }
            res.onSuccess { AlertDialog.Builder(a).setTitle("Envoyé au téléviseur").setMessage(it).setPositiveButton("OK", null).show() }
                .onFailure {
                    AlertDialog.Builder(a).setTitle("Envoi impossible")
                        .setMessage((it.message ?: "Erreur") + "\n\nVérifiez que le téléviseur affiche « Recevoir des sources » et que les deux appareils sont sur le même réseau.")
                        .setPositiveButton("OK", null).show()
                }
        }
    }
}
