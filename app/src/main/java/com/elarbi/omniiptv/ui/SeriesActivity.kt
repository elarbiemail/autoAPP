package com.elarbi.omniiptv.ui

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.ListView
import android.widget.TextView
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.elarbi.omniiptv.R
import com.elarbi.omniiptv.data.Library
import com.elarbi.omniiptv.data.Repo
import com.elarbi.omniiptv.data.XtreamClient
import com.elarbi.omniiptv.model.Channel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Épisodes d'une série Xtream, avec reprise et enchaînement automatique. */
class SeriesActivity : FragmentActivity() {
    private lateinit var listView: ListView
    private lateinit var status: TextView
    private var episodes: List<Channel> = emptyList()
    private lateinit var adapter: ChannelRowAdapter
    private var series: Channel? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_series)
        listView = findViewById(R.id.se_list)
        status = findViewById(R.id.se_status)
        adapter = ChannelRowAdapter(this, emptyList())
        listView.adapter = adapter
        val key = intent.getStringExtra("key") ?: return finish()
        val s = Repo.findChannel(key) ?: return finish()
        series = s
        findViewById<TextView>(R.id.se_title).text = s.name
        val watchBtn = findViewById<Button>(R.id.se_watch)
        fun label() { watchBtn.text = if (Library.isWatch(s.key)) "Retirer de « À regarder »" else "Ajouter la série à « À regarder »" }
        label()
        watchBtn.setOnClickListener { Library.toggleWatch(s); label() }
        findViewById<Button>(R.id.se_resume).setOnClickListener {
            val i = episodes.indexOfFirst { Library.progress(it.key) != null }.takeIf { it >= 0 } ?: 0
            play(i)
        }
        listView.setOnItemClickListener { _, _, pos, _ -> play(pos) }
        load(s)
    }

    override fun onResume() {
        super.onResume()
        adapter.notifyDataSetChanged()
    }

    private fun load(s: Channel) {
        val src = Repo.sources.firstOrNull { it.id == s.sourceId } ?: return
        status.text = "Chargement des épisodes…"
        lifecycleScope.launch {
            try {
                episodes = withContext(Dispatchers.IO) { XtreamClient(src).episodes(s.url.removePrefix("xtseries:"), s.name) }
                adapter.items = episodes
                adapter.notifyDataSetChanged()
                status.text = "${episodes.size} épisodes"
                listView.requestFocus()
            } catch (e: Exception) {
                status.text = "Erreur : ${e.message}"
            }
        }
    }

    private fun play(i: Int) {
        val ep = episodes.getOrNull(i) ?: return
        Repo.playContext = episodes
        startActivity(Intent(this, PlayerActivity::class.java).putExtra("key", ep.key))
    }
}
