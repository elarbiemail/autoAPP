package com.elarbi.omniiptv.ui

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.elarbi.omniiptv.data.Edits
import com.elarbi.omniiptv.data.Prefs
import com.elarbi.omniiptv.data.Repo
import com.elarbi.omniiptv.data.StreamResolver
import com.elarbi.omniiptv.data.YouTube
import com.elarbi.omniiptv.model.Channel
import com.elarbi.omniiptv.player.PlayerFactory
import kotlinx.coroutines.launch

/**
 * Multi-écran : jusqu'à 4 chaînes en même temps. Le son suit la case sélectionnée.
 * Flèches : changer de case • OK : choisir la chaîne • Menu / OK maintenu : plein écran.
 * Tactile : toucher une case pour la sélectionner, toucher à nouveau pour choisir la chaîne.
 */
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
class MultiViewActivity : FragmentActivity() {
    private val cells = ArrayList<FrameLayout>()
    private val views = ArrayList<PlayerView>()
    private val labels = ArrayList<TextView>()
    private val players = arrayOfNulls<ExoPlayer>(4)
    private val channels = arrayOfNulls<Channel>(4)
    private var sel = 0
    private var count = 4

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.BLACK) }
        for (r in 0 until 2) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for (c in 0 until 2) {
                val i = r * 2 + c
                val cell = FrameLayout(this).apply { setPadding(4, 4, 4, 4) }
                val pv = PlayerView(this).apply {
                    useController = false
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
                }
                cell.addView(pv, FrameLayout.LayoutParams(-1, -1))
                val label = TextView(this).apply {
                    setTextColor(Color.WHITE); textSize = 15f; setBackgroundColor(0x99000000.toInt())
                    setPadding(12, 6, 12, 6); text = "Case ${i + 1} : OK pour choisir une chaîne"
                }
                cell.addView(label, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.START))
                pv.setOnTouchListener { _, e ->
                    if (e.action == android.view.MotionEvent.ACTION_UP) { if (sel == i) chooseChannel() else select(i) }
                    true
                }
                row.addView(cell, LinearLayout.LayoutParams(0, -1, 1f))
                cells += cell; views += pv; labels += label
            }
            root.addView(row, LinearLayout.LayoutParams(-1, 0, 1f))
        }
        setContentView(root)
        // Pré-remplissage avec les favoris (ou la liste en cours).
        val start = (Repo.favorites.values.filter { !it.isVod }.map { Edits.apply(it) }
            .ifEmpty { Repo.playContext.filter { !it.isVod } }).take(count)
        start.forEachIndexed { i, ch -> play(i, ch) }
        select(0)
        Ui.toast(this, "Multi-écran : la qualité dépend du nombre de décodeurs vidéo du téléviseur.")
    }

    private fun select(i: Int) {
        sel = i
        cells.forEachIndexed { n, c ->
            c.background = if (n == i) GradientDrawable().apply { setStroke(8, ThemeManager.accent) } else null
        }
        players.forEachIndexed { n, p -> p?.volume = if (n == i) 1f else 0f }
    }

    private fun play(i: Int, ch: Channel) {
        if (Prefs.isLocked(ch)) { Ui.askPin(this) { play(i, ch) }; return }
        channels[i] = ch
        val p = players[i] ?: PlayerFactory.create(this).also { players[i] = it; views[i].player = it }
        p.volume = if (i == sel) 1f else 0f
        labels[i].text = ch.name
        lifecycleScope.launch {
            try {
                val url = StreamResolver.resolve(ch)
                PlayerFactory.play(this@MultiViewActivity, p, url, StreamResolver.userAgent(ch), ch.referer, forceHls = YouTube.isYouTube(ch.url), headers = StreamResolver.headers(ch))
            } catch (e: Exception) {
                labels[i].text = "${ch.name} — ${e.message}"
            }
        }
    }

    private fun candidates(): List<Channel> {
        val fav = Repo.favorites.values.filter { !it.isVod }.map { Edits.apply(it) }
        val src = Repo.activeSource()
        val live = com.elarbi.omniiptv.data.Shown.channels().filter { !it.isVod }
        return (fav + Repo.playContext.filter { !it.isVod } + live).filter { !it.url.startsWith("tuner:") }.distinctBy { it.key }.take(1500)
    }

    private fun chooseChannel() {
        // Liste lue en arrière-plan (gros portails) pour ne pas bloquer l'écran.
        lifecycleScope.launch {
            val list = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                try { candidates() } catch (e: Throwable) { com.elarbi.omniiptv.data.CrashLog.record("Multi-écran", e); emptyList() }
            }
            if (!isFinishing) showChoices(list)
        }
    }

    private fun showChoices(list: List<Channel>) {
        if (list.isEmpty()) { Ui.toast(this, "Aucune chaîne disponible."); return }
        val names = list.map { (if (Repo.isFavorite(it.key)) "★ " else "") + it.name + "  ·  " + it.group }.toTypedArray()
        AlertDialog.Builder(this).setTitle("Chaîne pour la case ${sel + 1}")
            .setItems(names) { _, w -> play(sel, list[w]) }
            .setNeutralButton("Vider la case") { _, _ ->
                players[sel]?.stop(); channels[sel] = null; labels[sel].text = "Case ${sel + 1} : OK pour choisir une chaîne"
            }
            .show()
    }

    private fun fullscreen() {
        val ch = channels[sel] ?: return
        Repo.playContext = channels.filterNotNull()
        startActivity(Intent(this, PlayerActivity::class.java).putExtra("key", ch.key))
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> { select(if (sel % 2 == 1) sel - 1 else sel); return true }
            KeyEvent.KEYCODE_DPAD_RIGHT -> { select(if (sel % 2 == 0) sel + 1 else sel); return true }
            KeyEvent.KEYCODE_DPAD_UP -> { select(if (sel >= 2) sel - 2 else sel); return true }
            KeyEvent.KEYCODE_DPAD_DOWN -> { select(if (sel < 2) sel + 2 else sel); return true }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> { event.startTracking(); return true }
            KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_INFO -> { fullscreen(); return true }
        }
        return super.onKeyDown(keyCode, event)
    }

    private var long = false

    override fun onKeyLongPress(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) { long = true; fullscreen(); return true }
        return super.onKeyLongPress(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
            if (!long) chooseChannel()
            long = false
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    override fun onStop() {
        super.onStop()
        players.forEach { it?.pause() }
    }

    override fun onRestart() {
        super.onRestart()
        channels.forEachIndexed { i, ch -> if (ch != null) play(i, ch) }
    }

    override fun onDestroy() {
        players.forEach { it?.release() }
        super.onDestroy()
    }

    @Suppress("unused")
    private fun hideLabel(v: View) { v.visibility = View.GONE }
}
