package com.elarbi.omniiptv.ui

import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import com.elarbi.omniiptv.data.Edits
import com.elarbi.omniiptv.data.Reminders
import com.elarbi.omniiptv.data.Repo
import com.elarbi.omniiptv.data.XtreamClient
import com.elarbi.omniiptv.model.Channel
import com.elarbi.omniiptv.model.Programme
import com.elarbi.omniiptv.model.SourceType

/** Guide TV en grille : regarder, replay des programmes passés, rappels des programmes à venir. */
class GuideActivity : FragmentActivity(), EpgGridView.Listener {
    private lateinit var grid: EpgGridView
    private lateinit var details: TextView
    private lateinit var groupBtn: android.widget.Button
    private var all: List<Channel> = emptyList()
    private var group = "Tout"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(Ui.dp(this@GuideActivity, 24), Ui.dp(this@GuideActivity, 12), Ui.dp(this@GuideActivity, 24), Ui.dp(this@GuideActivity, 8)) }
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        bar.addView(TextView(this).apply { text = "Guide TV"; textSize = 24f; setTextColor(0xFFFFFFFF.toInt()); setPadding(0, 0, Ui.dp(this@GuideActivity, 20), 0) })
        groupBtn = Ui.button(this, "Groupe : Tout", fullWidth = false) { chooseGroup() }
        bar.addView(groupBtn)
        bar.addView(Ui.button(this, "Maintenant", fullWidth = false) { grid.resetTime() })
        bar.addView(Ui.button(this, "Favoris", fullWidth = false) { group = "★ Favoris"; apply() })
        bar.addView(Ui.button(this, "⚙ Réglages", fullWidth = false) { startActivity(Intent(this, SettingsActivity::class.java)) })
        root.addView(bar)
        details = TextView(this).apply { textSize = 15f; setTextColor(0xFFB0BEC5.toInt()); maxLines = 3; setPadding(0, Ui.dp(this@GuideActivity, 6), 0, Ui.dp(this@GuideActivity, 6)) }
        root.addView(details)
        grid = EpgGridView(this)
        grid.listener = this
        root.addView(grid, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
        // Les chaînes sont lues en arrière-plan : l'écran s'ouvre tout de suite, même avec un gros portail.
        if (Repo.favorites.values.any { !it.isVod }) group = "★ Favoris"
        apply()
        grid.requestFocus()
        details.text = "Chargement des chaînes…"
        lifecycleScope.launch {
            all = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                try { com.elarbi.omniiptv.data.Shown.channels().filter { !it.isVod } }
                catch (e: Throwable) { com.elarbi.omniiptv.data.CrashLog.record("Guide TV", e); emptyList() }
            }
            if (isFinishing) return@launch
            if (group != "★ Favoris") apply()
            details.text = "${all.size} chaînes • « Groupe » pour choisir une catégorie"
        }
    }

    private fun apply() {
        val list = when (group) {
            "Tout" -> all
            "★ Favoris" -> Repo.favorites.values.filter { !it.isVod }.map { Edits.apply(it) }
            else -> all.filter { it.group == group }
        }
        groupBtn.text = "Groupe : $group"
        grid.setChannels(list)
        if (list.isEmpty()) details.text = "Aucune chaîne. Ajoutez une source ou des favoris."
    }

    private fun chooseGroup() {
        val groups = listOf("Tout", "★ Favoris") + all.map { it.group }.distinct()
        AlertDialog.Builder(this).setTitle("Groupe")
            .setItems(groups.toTypedArray()) { _, w -> group = groups[w]; apply(); grid.requestFocus() }
            .show()
    }

    override fun onSelect(ch: Channel, p: Programme?) {
        details.text = if (p == null) "${ch.name} — pas d'information de programme"
        else {
            val now = System.currentTimeMillis()
            val state = when {
                p.end <= now -> if (ch.archive > 0) "Terminé • replay disponible (OK)" else "Terminé"
                p.start <= now -> "En cours (OK pour regarder)"
                else -> if (Reminders.has(ch.key, p.start)) "Rappel programmé (OK pour annuler)" else "À venir (OK pour un rappel)"
            }
            "${ch.name} • ${Ui.hm(p.start)}–${Ui.hm(p.end)} • ${p.title} — $state\n${p.desc}"
        }
    }

    override fun onActivate(ch: Channel, p: Programme?) {
        val now = System.currentTimeMillis()
        when {
            p != null && p.start > now -> {
                if (Build.VERSION.SDK_INT >= 33 &&
                    checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                ) requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 3)
                val on = Reminders.toggle(this, Reminders.Reminder(ch.key, ch.name, p.title, p.start))
                Ui.toast(this, if (on) "Rappel programmé : ${p.title} à ${Ui.hm(p.start)}" else "Rappel annulé")
                onSelect(ch, p)
            }
            p != null && p.end <= now && ch.archive > 0 -> replay(ch, p)
            else -> play(ch, null, null)
        }
    }

    private fun replay(ch: Channel, p: Programme) {
        val src = Repo.sources.firstOrNull { it.id == ch.sourceId }
        val streamId = ch.key.substringAfter("|L", "")
        if (src == null || src.type != SourceType.XTREAM || streamId.isBlank()) {
            Ui.toast(this, "Replay indisponible pour cette chaîne.")
            return
        }
        play(ch, XtreamClient(src).timeshiftUrl(streamId, p.start, p.end), p.title)
    }

    private fun play(ch: Channel, url: String?, title: String?) {
        val src = Repo.activeSource()
        Repo.playContext = all.filter { it.group == ch.group }.ifEmpty { listOf(ch) }
        val i = Intent(this, PlayerActivity::class.java).putExtra("key", ch.key)
        if (url != null) i.putExtra("url", url).putExtra("title", title)
        startActivity(i)
    }
}
