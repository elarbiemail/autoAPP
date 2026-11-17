package com.elarbi.omniiptv.ui

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.bumptech.glide.Glide
import com.elarbi.omniiptv.R
import com.elarbi.omniiptv.data.Library
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
import java.util.Locale

@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
class PlayerActivity : FragmentActivity() {
    private lateinit var player: ExoPlayer
    private lateinit var playerView: PlayerView
    private lateinit var info: View
    private lateinit var tTitle: TextView
    private lateinit var tSub: TextView
    private lateinit var tNow: TextView
    private lateinit var tNext: TextView
    private lateinit var tTech: TextView
    private lateinit var tStatus: TextView
    private lateinit var progress: ProgressBar
    private lateinit var logo: ImageView
    private lateinit var side: View
    private lateinit var sideList: ListView
    private lateinit var sideTitle: TextView
    private lateinit var numberView: TextView
    private lateinit var statsView: TextView
    private lateinit var rowAdapter: ChannelRowAdapter

    private var list: List<Channel> = emptyList()
    private var index = 0
    private var previousIndex = -1
    private var currentUrl = ""
    private var retried = false
    private var longPressed = false
    private var job: Job? = null
    private val handler = Handler(Looper.getMainLooper())
    private val hideInfo = Runnable { info.visibility = View.GONE }

    // Statistiques
    private var speedBps = 0L
    private var estimateBps = 0L
    private var droppedFrames = 0
    private var qualityLabel = "Auto"
    private var showStats = false
    private var resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT

    // Saisie numérique
    private val digits = StringBuilder()
    private val applyNumber = Runnable { jumpToNumber() }

    private val ticker = object : Runnable {
        override fun run() {
            updateTech()
            if (showStats) statsView.text = statsText()
            saveProgress(false)
            handler.postDelayed(this, 1000)
        }
    }
    private var tick = 0

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
        tTech = findViewById(R.id.info_tech)
        tStatus = findViewById(R.id.info_status)
        progress = findViewById(R.id.info_progress)
        logo = findViewById(R.id.info_logo)
        side = findViewById(R.id.side)
        sideList = findViewById(R.id.side_list)
        sideTitle = findViewById(R.id.side_title)
        numberView = findViewById(R.id.number)
        statsView = findViewById(R.id.stats)

        player = PlayerFactory.create(this)
        playerView.player = player
        playerView.setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
        player.addListener(listener)
        player.addAnalyticsListener(analytics)

        rowAdapter = ChannelRowAdapter(this, emptyList())
        sideList.adapter = rowAdapter
        sideList.setOnItemClickListener { _, _, pos, _ ->
            side.visibility = View.GONE
            goTo(pos)
        }
        start(intent)
        handler.post(ticker)
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
            else -> com.elarbi.omniiptv.data.Edits.view(ch.sourceId).filter { it.group == ch.group }.ifEmpty { listOf(ch) }
        }
        index = list.indexOfFirst { it.key == ch.key }.coerceAtLeast(0)
        previousIndex = -1
        rowAdapter.items = list
        rowAdapter.notifyDataSetChanged()
        playCurrent()
    }

    private fun goTo(i: Int) {
        if (i == index || i !in list.indices) return
        saveProgress(true)
        previousIndex = index
        index = i
        playCurrent()
    }

    private fun playCurrent() {
        val ch = list.getOrNull(index) ?: return
        retried = false
        currentUrl = ""
        droppedFrames = 0
        speedBps = 0
        qualityLabel = "Auto"
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .clearOverridesOfType(C.TRACK_TYPE_VIDEO).build()
        playerView.useController = ch.isVod
        showInfo(ch, "Connexion…")
        lifecycleScope.launch(Dispatchers.IO) {
            Repo.addRecent(ch)
            if (!ch.isVod) HomeScreen.addWatchNext(applicationContext, ch)
            Repo.fetchShortEpg(ch)
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
                if (ch.isVod) {
                    val pos = Library.position(ch.key)
                    if (pos != null && pos > 0) {
                        player.seekTo(pos)
                        tStatus.text = "Reprise à ${clock(pos)} — → Options > « Recommencer » pour revenir au début"
                    }
                }
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
                Player.STATE_READY -> { if (tStatus.text.startsWith("Chargement") || tStatus.text.startsWith("Connexion")) tStatus.text = ""; scheduleHide() }
                Player.STATE_BUFFERING -> tStatus.text = "Chargement…"
                Player.STATE_ENDED -> onEnded()
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

    private val analytics = object : AnalyticsListener {
        override fun onBandwidthEstimate(
            eventTime: AnalyticsListener.EventTime, totalLoadTimeMs: Int, totalBytesLoaded: Long, bitrateEstimate: Long
        ) {
            if (totalLoadTimeMs > 0) speedBps = totalBytesLoaded * 8000L / totalLoadTimeMs
            estimateBps = bitrateEstimate
        }

        override fun onDroppedVideoFrames(eventTime: AnalyticsListener.EventTime, droppedFrames: Int, elapsedMs: Long) {
            this@PlayerActivity.droppedFrames += droppedFrames
        }
    }

    private fun onEnded() {
        val ch = list.getOrNull(index) ?: return
        if (!ch.isVod) return
        Library.finish(ch)
        lifecycleScope.launch(Dispatchers.IO) { HomeScreen.removeWatchNext(applicationContext, ch.key) }
        if (index + 1 < list.size && list[index + 1].isVod) {
            showInfo(list[index + 1], "Lecture suivante dans 5 secondes…")
            handler.postDelayed({ if (player.playbackState == Player.STATE_ENDED) goTo(index + 1) }, 5000)
        }
    }

    // ---------------- Bandeau ----------------
    private fun showInfo(ch: Channel, status: String?) {
        tTitle.text = "${index + 1}. ${ch.name}"
        val marks = (if (Repo.isFavorite(ch.key)) "★ " else "") + (if (Library.isWatch(ch.key)) "◉ À regarder  " else "")
        tSub.text = marks + ch.group
        if (ch.isVod) {
            tNow.text = Library.progress(ch.key)?.let { "Déjà vu : $it %" } ?: ""
            tNext.text = list.getOrNull(index + 1)?.takeIf { it.isVod }?.let { "Suivant : ${it.name}" } ?: ""
            progress.visibility = View.GONE
        } else {
            val (now, next) = Repo.nowNext(ch)
            tNow.text = now?.let { "${Ui.hm(it.start)}–${Ui.hm(it.end)}  ${it.title}" } ?: "Pas de guide TV pour cette chaîne"
            tNext.text = next?.let { "Ensuite ${Ui.hm(it.start)}  ${it.title}" } ?: ""
            if (now != null) {
                val t = System.currentTimeMillis()
                progress.progress = (((t - now.start) * 100) / (now.end - now.start).coerceAtLeast(1)).toInt()
                progress.visibility = View.VISIBLE
            } else progress.visibility = View.GONE
        }
        if (ch.logo.isNotBlank()) Glide.with(this).load(ch.logo).error(R.drawable.ic_tv).into(logo)
        else logo.setImageResource(R.drawable.ic_tv)
        if (status != null) tStatus.text = status
        updateTech()
        info.visibility = View.VISIBLE
        scheduleHide()
    }

    private fun scheduleHide() {
        handler.removeCallbacks(hideInfo)
        handler.postDelayed(hideInfo, 7000)
    }

    private fun rate(bps: Long): String = when {
        bps <= 0 -> "—"
        bps >= 1_000_000 -> String.format(Locale.FRANCE, "%.1f Mb/s", bps / 1_000_000.0)
        else -> "${bps / 1000} kb/s"
    }

    private fun clock(ms: Long): String {
        val s = ms / 1000
        return if (s >= 3600) String.format(Locale.FRANCE, "%d:%02d:%02d", s / 3600, (s / 60) % 60, s % 60)
        else String.format(Locale.FRANCE, "%02d:%02d", s / 60, s % 60)
    }

    private fun codec(f: Format?): String {
        val m = f?.sampleMimeType ?: return "—"
        return when {
            m.contains("avc") -> "H.264"
            m.contains("hevc") -> "H.265"
            m.contains("av01") -> "AV1"
            m.contains("vp9") -> "VP9"
            m.contains("mp4a") -> "AAC"
            m.contains("ac3") -> if (m.contains("eac3")) "E-AC3" else "AC3"
            m.contains("mpeg") -> "MPEG"
            else -> m.substringAfter('/')
        }
    }

    private fun quality(f: Format?): String {
        if (f == null || f.height <= 0) return "—"
        val h = f.height
        val label = when {
            h >= 2160 -> "4K"
            h >= 1080 -> "Full HD"
            h >= 720 -> "HD"
            else -> "SD"
        }
        return "${h}p $label"
    }

    private fun updateTech() {
        val v = player.videoFormat
        tTech.text = "${quality(v)} · ${codec(v)} · débit ${rate(speedBps)} · qualité $qualityLabel"
    }

    private fun statsText(): String {
        val v = player.videoFormat
        val a = player.audioFormat
        val size = player.videoSize
        val fps = v?.frameRate?.takeIf { it > 0 }?.let { String.format(Locale.FRANCE, "%.0f i/s", it) } ?: "—"
        val audio = a?.let { "${codec(it)} ${it.channelCount} can. ${it.sampleRate / 1000} kHz ${it.language ?: ""}" } ?: "—"
        return buildString {
            appendLine("Vitesse réseau  : ${rate(speedBps)}")
            appendLine("Estimation      : ${rate(estimateBps)}")
            appendLine("Résolution      : ${if (size.width > 0) "${size.width}×${size.height}" else "—"} (${quality(v)})")
            appendLine("Débit du flux   : ${rate(v?.bitrate?.toLong() ?: 0L)}")
            appendLine("Vidéo           : ${codec(v)} · $fps")
            appendLine("Audio           : $audio")
            appendLine("Tampon          : ${player.totalBufferedDuration / 1000} s")
            appendLine("Images perdues  : $droppedFrames")
            append("Qualité choisie : $qualityLabel")
        }
    }

    // ---------------- Options ----------------
    private fun options() {
        val ch = list.getOrNull(index) ?: return
        val items = ArrayList<Pair<String, () -> Unit>>()
        items += "Qualité vidéo ($qualityLabel)" to { trackMenu(C.TRACK_TYPE_VIDEO) }
        items += "Piste audio" to { trackMenu(C.TRACK_TYPE_AUDIO) }
        items += "Sous-titres" to { trackMenu(C.TRACK_TYPE_TEXT) }
        items += "Format de l'image" to { aspectMenu() }
        items += (if (showStats) "Masquer les statistiques" else "Afficher vitesse et statistiques") to {
            showStats = !showStats
            statsView.visibility = if (showStats) View.VISIBLE else View.GONE
            statsView.text = statsText()
        }
        items += (if (Repo.isFavorite(ch.key)) "Retirer des favoris" else "Ajouter aux favoris") to { toggleFav() }
        items += (if (Library.isWatch(ch.key)) "Retirer de « À regarder »" else "Ajouter à « À regarder »") to {
            Ui.toast(this, if (Library.toggleWatch(ch)) "Ajouté à « À regarder »" else "Retiré de « À regarder »")
        }
        if (ch.isVod) items += "Recommencer depuis le début" to { Library.restart(ch.key); player.seekTo(0) }
        if (previousIndex in list.indices) items += "Chaîne précédente : ${list[previousIndex].name}" to { goTo(previousIndex) }
        items += "Liste des chaînes" to { openSide() }
        AlertDialog.Builder(this)
            .setTitle(ch.name)
            .setItems(items.map { it.first }.toTypedArray()) { _, w -> items[w].second() }
            .show()
    }

    private fun trackMenu(type: Int) {
        val labels = ArrayList<String>()
        val actions = ArrayList<() -> Unit>()
        val params = player.trackSelectionParameters
        if (type == C.TRACK_TYPE_TEXT) {
            labels += "Désactivés"
            actions += { player.trackSelectionParameters = params.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build() }
        } else {
            labels += "Automatique"
            actions += {
                player.trackSelectionParameters = params.buildUpon().clearOverridesOfType(type).build()
                if (type == C.TRACK_TYPE_VIDEO) qualityLabel = "Auto"
            }
        }
        for (g in player.currentTracks.groups) {
            if (g.type != type) continue
            for (i in 0 until g.length) {
                if (!g.isTrackSupported(i)) continue
                val f = g.getTrackFormat(i)
                val label = when (type) {
                    C.TRACK_TYPE_VIDEO -> "${quality(f)} · ${rate(f.bitrate.toLong())} · ${codec(f)}"
                    C.TRACK_TYPE_AUDIO -> listOfNotNull(f.label, f.language, codec(f), "${f.channelCount} can.").joinToString(" · ")
                    else -> listOfNotNull(f.label, f.language).joinToString(" · ").ifBlank { "Piste ${i + 1}" }
                }
                val selected = if (g.isTrackSelected(i)) "● " else ""
                labels += selected + label
                actions += {
                    player.trackSelectionParameters = params.buildUpon()
                        .setTrackTypeDisabled(type, false)
                        .setOverrideForType(TrackSelectionOverride(g.mediaTrackGroup, i))
                        .build()
                    if (type == C.TRACK_TYPE_VIDEO) qualityLabel = quality(f)
                }
            }
        }
        if (labels.size == 1 && type != C.TRACK_TYPE_TEXT) {
            Ui.toast(this, "Ce flux ne propose qu'une seule qualité.")
        }
        val title = when (type) {
            C.TRACK_TYPE_VIDEO -> "Qualité vidéo"
            C.TRACK_TYPE_AUDIO -> "Piste audio"
            else -> "Sous-titres"
        }
        AlertDialog.Builder(this).setTitle(title)
            .setItems(labels.toTypedArray()) { _, w -> actions[w]() }
            .show()
    }

    private fun aspectMenu() {
        val modes = listOf(
            "Adapter (sans déformation)" to AspectRatioFrameLayout.RESIZE_MODE_FIT,
            "Remplir l'écran (zoom)" to AspectRatioFrameLayout.RESIZE_MODE_ZOOM,
            "Étirer" to AspectRatioFrameLayout.RESIZE_MODE_FILL
        )
        AlertDialog.Builder(this).setTitle("Format de l'image")
            .setItems(modes.map { (if (it.second == resizeMode) "● " else "") + it.first }.toTypedArray()) { _, w ->
                resizeMode = modes[w].second
                playerView.resizeMode = resizeMode
            }.show()
    }

    // ---------------- Navigation ----------------
    private fun zap(delta: Int) {
        if (list.size < 2) return
        goTo((index + delta + list.size) % list.size)
    }

    private fun toggleFav() {
        val ch = list.getOrNull(index) ?: return
        val added = Repo.toggleFavorite(ch)
        Ui.toast(this, if (added) "Ajoutée aux favoris" else "Retirée des favoris")
        showInfo(ch, null)
        rowAdapter.notifyDataSetChanged()
        HomeSyncWorker.runNow(this)
    }

    private fun openSide() {
        sideTitle.text = list.getOrNull(index)?.group ?: ""
        rowAdapter.notifyDataSetChanged()
        side.visibility = View.VISIBLE
        sideList.setSelection(index)
        sideList.setItemChecked(index, true)
        sideList.requestFocus()
    }

    private fun digit(d: Int) {
        if (digits.length >= 4) digits.clear()
        digits.append(d)
        numberView.text = digits.toString()
        numberView.visibility = View.VISIBLE
        handler.removeCallbacks(applyNumber)
        handler.postDelayed(applyNumber, 1500)
    }

    private fun jumpToNumber() {
        numberView.visibility = View.GONE
        val n = digits.toString().toIntOrNull()
        digits.clear()
        if (n == null) return
        if (n in 1..list.size) goTo(n - 1) else Ui.toast(this, "Pas de chaîne n° $n dans cette liste")
    }

    private fun saveProgress(force: Boolean) {
        val ch = list.getOrNull(index) ?: return
        if (!ch.isVod || player.duration <= 0) return
        tick++
        if (!force && tick % 10 != 0) return
        val pos = player.currentPosition
        val dur = player.duration
        Library.savePosition(ch, pos, dur)
        lifecycleScope.launch(Dispatchers.IO) { HomeScreen.addWatchNext(applicationContext, ch, pos, dur) }
    }

    private fun isOk(k: Int) = k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER || k == KeyEvent.KEYCODE_NUMPAD_ENTER

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val ch = list.getOrNull(index)
        // Pour les films : ↑ ouvre les options quand la barre de lecture est masquée.
        if (event.action == KeyEvent.ACTION_DOWN && ch?.isVod == true && side.visibility != View.VISIBLE &&
            event.keyCode == KeyEvent.KEYCODE_DPAD_UP && !playerView.isControllerFullyVisible
        ) {
            options()
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (side.visibility == View.VISIBLE) return super.onKeyDown(keyCode, event)
        val d = when (keyCode) {
            in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> keyCode - KeyEvent.KEYCODE_0
            in KeyEvent.KEYCODE_NUMPAD_0..KeyEvent.KEYCODE_NUMPAD_9 -> keyCode - KeyEvent.KEYCODE_NUMPAD_0
            else -> -1
        }
        if (d >= 0) { digit(d); return true }
        when (keyCode) {
            KeyEvent.KEYCODE_CHANNEL_UP -> { zap(1); return true }
            KeyEvent.KEYCODE_CHANNEL_DOWN -> { zap(-1); return true }
            KeyEvent.KEYCODE_LAST_CHANNEL -> { if (previousIndex in list.indices) goTo(previousIndex); return true }
            KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_SETTINGS -> { options(); return true }
            KeyEvent.KEYCODE_PROG_RED, KeyEvent.KEYCODE_BOOKMARK -> { toggleFav(); return true }
            KeyEvent.KEYCODE_INFO, KeyEvent.KEYCODE_GUIDE -> { list.getOrNull(index)?.let { showInfo(it, null) }; return true }
            KeyEvent.KEYCODE_PROG_GREEN -> {
                showStats = !showStats
                statsView.visibility = if (showStats) View.VISIBLE else View.GONE
                return true
            }
        }
        val ch = list.getOrNull(index)
        if (ch != null && !ch.isVod) {
            when {
                keyCode == KeyEvent.KEYCODE_DPAD_UP -> { zap(-1); return true }
                keyCode == KeyEvent.KEYCODE_DPAD_DOWN -> { zap(1); return true }
                keyCode == KeyEvent.KEYCODE_DPAD_LEFT -> { openSide(); return true }
                keyCode == KeyEvent.KEYCODE_DPAD_RIGHT -> { options(); return true }
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

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (side.visibility == View.VISIBLE) {
            side.visibility = View.GONE
            playerView.requestFocus()
            return
        }
        if (showStats) {
            showStats = false
            statsView.visibility = View.GONE
            return
        }
        @Suppress("DEPRECATION")
        super.onBackPressed()
    }

    override fun onStop() {
        saveProgress(true)
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
