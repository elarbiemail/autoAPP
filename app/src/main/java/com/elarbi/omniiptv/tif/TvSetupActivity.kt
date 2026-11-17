package com.elarbi.omniiptv.tif

import android.os.Bundle
import android.widget.TextView
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.elarbi.omniiptv.data.Repo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Écran appelé par l'application TV système pour configurer la source Omni IPTV. */
class TvSetupActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val tv = TextView(this).apply {
            text = "Omni IPTV : synchronisation des chaînes et du guide TV…"
            textSize = 22f
            setPadding(96, 96, 96, 96)
        }
        setContentView(tv)
        lifecycleScope.launch {
            val n = withContext(Dispatchers.IO) {
                try {
                    Repo.activeSource()?.let { s -> if (Repo.channels(s.id).isEmpty()) Repo.refresh(s) }
                    TifSync.sync(this@TvSetupActivity)
                } catch (e: Exception) {
                    -1
                }
            }
            tv.text = if (n > 0) "$n chaînes ajoutées." else "Aucune chaîne : ajoutez d'abord une source dans Omni IPTV."
            delay(1500)
            setResult(if (n > 0) RESULT_OK else RESULT_CANCELED)
            finish()
        }
    }
}
