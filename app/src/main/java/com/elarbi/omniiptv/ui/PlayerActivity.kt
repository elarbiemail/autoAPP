package com.elarbi.omniiptv.ui

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.ListView
import android.widget.TextView
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.elarbi.omniiptv.R
import com.elarbi.omniiptv.data.Repo
import com.elarbi.omniiptv.data.StreamResolver
import com.elarbi.omniiptv.data.YouTube
import com.elarbi.omniiptv.home.HomeScreen
import com.elarbi.omniiptv.home.HomeSyncWorker
import com.elarbi.omniiptv.model.Channel
import com.elarbi.omniiptv.player.PlayerFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
class PlayerActivity : FragmentActivity() {
    private lateinit var player: ExoPlayer
    private lateinit var playerView: PlayerView
    private lateinit var info: View
    private lateinit var tTitle: TextView
    private lateinit var tSub: TextView
    private lateinit var tNow: TextView
    private lateinit var tNext: TextView
    private lateinit var tStatus: TextView
    private lateinit var side: ListView

    private var list: List<Channel> = emptyList()
    private var index = 0
    private var currentUrl = ""
    private var retried = false
    private var longPressed = false
    private var job: Job? = null
    private val handler = Handler(Looper.getMainLooper())
    private val hideInfo = Runnable { info.visibility = View.GONE }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_player)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        playerView = findViewById(R.id.player_view)
        info = findViewById(R.id.info)
        tTitle = findViewById(R.id.info_title)
        tSub = findViewById(R.id.info_sub)
        tNow = findViewById(R.id.info_now)
        tNext = findViewById(R.id.info_next)
        tStatus = findViewById(R.id.info_status)
        side = findViewById(R.id.side_list)

        player = PlayerFactory.create(this)
        playerView.player = player
        playerView.setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
        player.addListener(listener)

        side.setOnItemClickListener { _, _, pos, _ ->
            side.visibility = View.GONE
            index = pos
            playCurrent()
        }
        start(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        start(intent)
    }

    private fun start(i: Intent) {
        val key = i.getStringExtra("key") ?: i.data?.getQueryParameter("key")
        val ch = key?.let { Repo.findChannel(it) }
        if (ch == null) {
            Ui.toast(this, "Chaîne introuvable. Actualisez la source.")
            finish()
            return
        }
        list = when {
            Repo.playContext.any { it.key == ch.key } -> Repo.playContext
            Repo.isFavorite(ch.key) -> Repo.favorites.values.toList()
            else -> Repo.channels(ch.sourceId).filter { it.group == ch.group }.ifEmpty { listOf(ch) }
        }
        index = list.indexOfFirst { it.key == ch.key }.coerceAtLeast(0)
        side.adapter = ArrayAdapter(this, R.layout.item_text, list.mapIndexed { n, c -> "${n + 1}. ${c.name}" })
        playCurrent()
    }

    private fun playCurrent() {
        val ch = list.getOrNull(index) ?: return
        retried = false
        currentUrl = ""
        playerView.useController = ch.isVod
        showInfo(ch, "Connexion…")
        lifecycleScope.launch(Dispatchers.IO) {
            Repo.addRecent(ch)
            HomeScreen.addWatchNext(applicationContext, ch)
        }
        job?.cancel()
        job = lifecycleScope.launch {
            try {
                val url = StreamResolver.resolve(ch)
                currentUrl = url
                PlayerFactory.play(
                    this@PlayerActivity, player, url, StreamResolver.userAgent(ch), ch.referer,
                    forceHls = YouTube.isYouTube(ch.url)
                )
            } catch (e: YouTube.NotLiveException) {
                showInfo(ch, "Pas de direct YouTube en cours : ouverture de l'application YouTube…")
                if (YouTube.openInApp(this@PlayerActivity, ch.url)) player.stop()
            } catch (e: Exception) {
                tStatus.text = "Erreur : ${e.message}"
            }
        }
    }

    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(state: Int) {
            when (state) {
                Player.STATE_READY -> { tStatus.text = ""; scheduleHide() }
                Player.STATE_BUFFERING -> tStatus.text = "Chargement…"
                else -> {}
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            val ch = list.getOrNull(index) ?: return
            if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                player.seekToDefaultPosition()
                player.prepare()
                return
            }
            if (!retried && currentUrl.isNotBlank() && !currentUrl.contains(".m3u8")) {
                retried = true
                val alt = if (currentUrl.endsWith(".ts")) currentUrl.removeSuffix(".ts") + ".m3u8" else currentUrl
                PlayerFactory.play(this@PlayerActivity, player, alt, StreamResolver.userAgent(ch), ch.referer, forceHls = true)
                return
            }
            showInfo(ch, "Lecture impossible (${error.errorCodeName}). Changez de chaîne avec ↑/↓.")
            handler.removeCallbacks(hideInfo)
        }
    }

    private fun showInfo(ch: Channel, status: String?) {
        tTitle.text = "${index + 1}. ${ch.name}"
        tSub.text = (if (Repo.isFavorite(ch.key)) "★ " else "") + ch.group
        val (now, next) = Repo.nowNext(ch)
        tNow.text = now?.let { "En cours  ${Ui.hm(it.start)}–${Ui.hm(it.end)}  ${it.title}" } ?: ""
        tNext.text = next?.let { "Ensuite  ${Ui.hm(it.start)}  ${it.title}" } ?: ""
        if (status != null) tStatus.text = status
        info.visibility = View.VISIBLE
        scheduleHide()
    }

    private fun scheduleHide() {
        handler.removeCallbacks(hideInfo)
        handler.postDelayed(hideInfo, 6000)
    }

    private fun zap(delta: Int) {
        if (list.size < 2) return
        index = (index + delta + list.size) % list.size
        playCurrent()
    }

    private fun toggleFav() {
        val ch = list.getOrNull(index) ?: return
        val added = Repo.toggleFavorite(ch)
        Ui.toast(this, if (added) "Ajoutée aux favoris" else "Retirée des favoris")
        showInfo(ch, null)
        HomeSyncWorker.runNow(this)
    }

    private fun isOk(k: Int) = k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER || k == KeyEvent.KEYCODE_NUMPAD_ENTER

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (side.visibility == View.VISIBLE) return super.onKeyDown(keyCode, event)
        when (keyCode) {
            KeyEvent.KEYCODE_CHANNEL_UP -> { zap(1); return true }
            KeyEvent.KEYCODE_CHANNEL_DOWN -> { zap(-1); return true }
            KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_BOOKMARK, KeyEvent.KEYCODE_PROG_RED -> { toggleFav(); return true }
            KeyEvent.KEYCODE_INFO, KeyEvent.KEYCODE_GUIDE -> { list.getOrNull(index)?.let { showInfo(it, null) }; return true }
        }
        val ch = list.getOrNull(index)
        if (ch != null && !ch.isVod) {
            when {
                keyCode == KeyEvent.KEYCODE_DPAD_UP -> { zap(-1); return true }
                keyCode == KeyEvent.KEYCODE_DPAD_DOWN -> { zap(1); return true }
                keyCode == KeyEvent.KEYCODE_DPAD_LEFT -> { openSide(); return true }
                isOk(keyCode) -> { event.startTracking(); return true }
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyLongPress(keyCode: Int, event: KeyEvent): Boolean {
        if (isOk(keyCode) && list.getOrNull(index)?.isVod == false) {
            longPressed = true
            toggleFav()
            return true
        }
        return super.onKeyLongPress(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (side.visibility != View.VISIBLE && isOk(keyCode) && list.getOrNull(index)?.isVod == false) {
            if (!longPressed) list.getOrNull(index)?.let { showInfo(it, null) }
            longPressed = false
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    private fun openSide() {
        side.visibility = View.VISIBLE
        side.setSelection(index)
        side.setItemChecked(index, true)
        side.requestFocus()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (side.visibility == View.VISIBLE) {
            side.visibility = View.GONE
            playerView.requestFocus()
            return
        }
        @Suppress("DEPRECATION")
        super.onBackPressed()
    }

    override fun onStop() {
        super.onStop()
        player.pause()
    }

    override fun onRestart() {
        super.onRestart()
        if (list.isNotEmpty()) playCurrent()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        player.release()
        super.onDestroy()
    }
}
