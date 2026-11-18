package com.elarbi.omniiptv.ui

import android.app.AlertDialog
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.elarbi.omniiptv.data.Loading
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Fenêtre de progression du chargement d'une source (étape, pages, éléments reçus, durée). */
object ProgressUi {
    fun <T> run(a: FragmentActivity, title: String, block: suspend () -> T, done: (Result<T>) -> Unit) {
        val pad = Ui.dp(a, 24)
        val box = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad / 2, pad, 0) }
        val step = TextView(a).apply { textSize = 16f; setTextColor(0xFFFFFFFF.toInt()) }
        val bar = ProgressBar(a, null, android.R.attr.progressBarStyleHorizontal).apply { isIndeterminate = true; max = 1000 }
        val info = TextView(a).apply { textSize = 14f; setTextColor(0xB3FFFFFF.toInt()) }
        box.addView(step)
        box.addView(bar, LinearLayout.LayoutParams(-1, Ui.dp(a, 24)).apply { topMargin = Ui.dp(a, 10) })
        box.addView(info)
        val dlg = AlertDialog.Builder(a).setTitle(title).setView(box).setCancelable(false)
            .setPositiveButton("Continuer en arrière-plan", null).create()
        // Le chargement continue même si l'on ferme cette fenêtre ou que l'on quitte l'écran.
        dlg.show()
        val poll = a.lifecycleScope.launch {
            while (isActive) {
                step.text = Loading.step
                if (Loading.total > 0) {
                    bar.isIndeterminate = false
                    bar.progress = (Loading.done * 1000L / Loading.total).toInt().coerceIn(0, 1000)
                } else bar.isIndeterminate = true
                val secs = if (Loading.startedAt > 0) (System.currentTimeMillis() - Loading.startedAt) / 1000 else 0
                info.text = (if (Loading.items > 0) "${Loading.items} éléments reçus  •  " else "") + "${secs} s"
                info.visibility = View.VISIBLE
                delay(250)
            }
        }
        val app = a.applicationContext
        // Le travail tourne hors de l'écran : il continue (et s'enregistre) même si l'on quitte l'écran.
        val job = com.elarbi.omniiptv.data.Repo.scope.async {
            val r = runCatching { block() }
            if (a.isDestroyed || a.isFinishing) {
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    android.widget.Toast.makeText(app,
                        if (r.isSuccess) "Chargement terminé et enregistré : $title" else "Chargement interrompu : ${r.exceptionOrNull()?.message}",
                        android.widget.Toast.LENGTH_LONG).show()
                }
            }
            r
        }
        a.lifecycleScope.launch {
            val r = job.await()
            poll.cancel()
            try { dlg.dismiss() } catch (_: Exception) {}
            done(r)
        }
    }
}
