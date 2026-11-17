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
import com.elarbi.omniiptv.data.Repo
import com.elarbi.omniiptv.data.YouTube
import com.elarbi.omniiptv.home.HomeSyncWorker
import com.elarbi.omniiptv.model.Channel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class YoutubeActivity : FragmentActivity() {
    private lateinit var fName: EditText
    private lateinit var fUrl: EditText
    private lateinit var fGroup: EditText
    private lateinit var status: TextView
    private lateinit var list: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_youtube)
        fName = findViewById(R.id.yt_name)
        fUrl = findViewById(R.id.yt_url)
        fGroup = findViewById(R.id.yt_group)
        status = findViewById(R.id.yt_status)
        list = findViewById(R.id.yt_list)
        findViewById<Button>(R.id.yt_add).setOnClickListener { add(fName.text.toString(), fUrl.text.toString(), fGroup.text.toString()) }
        findViewById<Button>(R.id.yt_test).setOnClickListener { test(fUrl.text.toString()) }
        val presets = findViewById<LinearLayout>(R.id.yt_presets)
        YouTube.presets.forEach { p ->
            presets.addView(Ui.button(this, "＋ ${p.name}") { add(p.name, p.url, p.group) })
        }
        render()
        fUrl.requestFocus()
    }

    private fun add(name: String, url: String, group: String) {
        if (!YouTube.isYouTube(url) && !url.trim().startsWith("@")) {
            status.text = "Lien YouTube non reconnu."
            return
        }
        val ch = Repo.addYoutube(name.trim().ifBlank { url.trim().substringAfter("@").substringBefore('/') }, url, group.trim())
        status.text = "« ${ch.name} » ajoutée. Elle apparaît dans la source « YouTube Live »."
        fName.setText(""); fUrl.setText("")
        HomeSyncWorker.runNow(this)
        render()
    }

    private fun test(url: String) {
        if (url.isBlank()) { status.text = "Saisissez d'abord un lien."; return }
        status.text = "Recherche du direct…"
        lifecycleScope.launch {
            status.text = try {
                val st = withContext(Dispatchers.IO) { YouTube.resolveStream(url) }
                if (st.url != null) "✅ ${if (st.isLive) "Direct en cours" else "Vidéo"} lisible dans le lecteur Omni IPTV."
                else "✅ Lisible avec le lecteur YouTube intégré à Omni IPTV."
            } catch (e: YouTube.NotLiveException) {
                "⚠ Pas de direct en ce moment sur cette chaîne."
            } catch (e: Exception) {
                "❌ Erreur : ${e.message}"
            }
        }
    }

    private fun render() {
        list.removeAllViews()
        val items = Repo.youtubeChannels()
        if (items.isEmpty()) {
            list.addView(TextView(this).apply { text = "Aucune chaîne pour l'instant."; setTextColor(0x99FFFFFF.toInt()); textSize = 16f })
        }
        items.forEach { ch -> list.addView(Ui.button(this, "${ch.name}  ·  ${ch.group}") { options(ch, items) }) }
    }

    private fun options(ch: Channel, all: List<Channel>) {
        AlertDialog.Builder(this).setTitle(ch.name)
            .setItems(arrayOf("Regarder", "Ouvrir dans l'application YouTube (facultatif)", "Supprimer")) { _, w ->
                when (w) {
                    0 -> {
                        Repo.playContext = all
                        startActivity(Intent(this, PlayerActivity::class.java).putExtra("key", ch.key))
                    }
                    1 -> YouTube.openInApp(this, ch.url)
                    2 -> { Repo.removeYoutube(ch.key); render() }
                }
            }.show()
    }
}
