package com.elarbi.omniiptv.ui

import android.content.Intent
import android.os.Bundle
import androidx.fragment.app.FragmentActivity
import androidx.leanback.app.SearchSupportFragment
import androidx.leanback.widget.ArrayObjectAdapter
import androidx.leanback.widget.HeaderItem
import androidx.leanback.widget.ListRow
import androidx.leanback.widget.ListRowPresenter
import androidx.leanback.widget.ObjectAdapter
import androidx.leanback.widget.OnItemViewClickedListener
import androidx.lifecycle.lifecycleScope
import com.elarbi.omniiptv.R
import com.elarbi.omniiptv.data.Repo
import com.elarbi.omniiptv.model.Channel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SearchActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_frame)
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction().replace(R.id.frame, ChannelSearchFragment()).commit()
        }
    }
}

class ChannelSearchFragment : SearchSupportFragment(), SearchSupportFragment.SearchResultProvider {
    private val rows = ArrayObjectAdapter(ListRowPresenter())
    private var job: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setSearchResultProvider(this)
        setOnItemViewClickedListener(OnItemViewClickedListener { _, item, _, row ->
            if (item is Channel && item.url.startsWith("xtseries:")) {
                startActivity(Intent(requireContext(), SeriesActivity::class.java).putExtra("key", item.key))
            } else if (item is Channel) {
                val a = (row as? ListRow)?.adapter
                Repo.playContext = if (a != null) (0 until a.size()).mapNotNull { a.get(it) as? Channel } else listOf(item)
                startActivity(Intent(requireContext(), PlayerActivity::class.java).putExtra("key", item.key))
            }
        })
    }

    override fun getResultsAdapter(): ObjectAdapter = rows

    override fun onQueryTextChange(newQuery: String): Boolean { search(newQuery, 400); return true }

    override fun onQueryTextSubmit(query: String): Boolean { search(query, 0); return true }

    private fun search(q: String, wait: Long) {
        job?.cancel()
        job = lifecycleScope.launch {
            if (wait > 0) delay(wait)
            val n = Ui.norm(q.trim())
            if (n.length < 2) { rows.clear(); return@launch }
            val bySource = withContext(Dispatchers.Default) {
                Repo.sources.map { s ->
                    s.name to com.elarbi.omniiptv.data.Edits.view(s.id).filter { Ui.norm(it.name).contains(n) }.take(150)
                }.filter { it.second.isNotEmpty() }
            }
            rows.clear()
            val presenter = CardPresenter { }
            bySource.forEachIndexed { i, (name, list) ->
                val a = ArrayObjectAdapter(presenter)
                a.addAll(0, list)
                rows.add(ListRow(HeaderItem(i.toLong(), "$name (${list.size})"), a))
            }
        }
    }
}
