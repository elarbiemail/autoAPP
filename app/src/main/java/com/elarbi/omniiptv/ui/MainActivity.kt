package com.elarbi.omniiptv.ui

import android.os.Bundle
import androidx.fragment.app.FragmentActivity
import com.elarbi.omniiptv.R
import android.content.Intent
import androidx.lifecycle.lifecycleScope
import com.elarbi.omniiptv.data.Prefs
import com.elarbi.omniiptv.data.Repo
import com.elarbi.omniiptv.home.HomeSyncWorker
import kotlinx.coroutines.launch

class MainActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_frame)
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction().replace(R.id.frame, MainFragment()).commit()
            HomeSyncWorker.runNow(this)
            startup()
        }
    }

    /** Comportement au lancement : actualisation, vue préférée, dernière chaîne. */
    private fun startup() {
        if (intent?.data?.host == "home") return
        if (Prefs.refreshOnStart) Repo.activeSource()?.let { s ->
            lifecycleScope.launch { try { Repo.refresh(s) } catch (_: Exception) {} }
        }
        when (Prefs.startView) {
            "list" -> startActivity(Intent(this, BrowseActivity::class.java).putExtra("mode", "list"))
            "grid" -> startActivity(Intent(this, BrowseActivity::class.java).putExtra("mode", "grid"))
            "guide" -> startActivity(Intent(this, GuideActivity::class.java))
            "multi" -> startActivity(Intent(this, MultiViewActivity::class.java))
        }
        if (Prefs.autoPlayLast) Repo.recents.firstOrNull()?.let { ch ->
            Repo.playContext = listOf(ch)
            startActivity(Intent(this, PlayerActivity::class.java).putExtra("key", ch.key))
        }
    }
}
