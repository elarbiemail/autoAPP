package com.elarbi.omniiptv.ui

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.app.PictureInPictureParams
import android.content.pm.PackageManager
import android.media.AudioManager
import android.util.Rational
import android.view.GestureDetector
import android.view.MotionEvent
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
import com.elarbi.omniiptv.data.Prefs
import com.elarbi.omniiptv.data.Repo
import com.elarbi.omniiptv.data.StreamResolver
import com.elarbi.omniiptv.data.TunerSource
import com.elarbi.omniiptv.data.YouTube
import com.elarbi.omniiptv.home.HomeScreen
import com.elarbi.omniiptv.home.HomeSyncWorker
import com.elarbi.omniiptv.model.Channel
import com.elarbi.omniiptv.model.isEpisode
import com.elarbi.omniiptv.model.isSeries
import com.elarbi.omniiptv.player.PlayerFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
    private lateinit var tvView: android.media.tv.TvView
    private var tunerFailed = false

    private var list: List<Channel> = emptyList()
    private var index = 0
    private var previousIndex = -1
    private var currentUrl = ""
    private var retried = false
    /** Relances automatiques consécutives sans lecture stable. */
    private var liveRetries = 0
    private var pendingReconnect = false
    /** Méthode de lien (portail MAC) utilisée pour la lecture en cours. */
    private var lastAttempt = 0
    private val stallRun = Runnable {
        val ch = list.getOrNull(index)
        if (ch != null && player.playbackState == Player.STATE_BUFFERING && player.playWhenReady)
            reconnect(ch, if (ch.isVod) "plus de données depuis 30 s" else "plus d'images depuis 15 s")
    }
    private val stableRun = Runnable { if (player.playbackState == Player.STATE_READY) liveRetries = 0 }
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

    // Reconnexion, minuterie, replay
    private var reconnects = 0
    private var sleepAt = 0L
    private val sleepRunnable = Runnable {
        player.pause()
        Ui.toast(this, "Minuterie de mise en veille : lecture arrêtée.")
        finish()
    }
    private var overrideUrl: String? = null
    private var overrideTitle: String? = null

    // Saisie numérique
    private val digits = StringBuilder()
    private val applyNumber = Runnable { jumpToNumber() }

    private val ticker = object : Runnable {
        override fun run() {
            updateTech()
            if (showStats) statsView.text = statsText()
            saveProgress(false)
            watchdogTick++
            if (watchdogTick % 60 == 0) list.getOrNull(index)?.let { ch ->
                if (ch.url.startsWith("stalker:") || ch.url.startsWith("stkvod:")) {
                    lifecycleScope.launch(Dispatchers.IO) { try { Repo.stalker(ch.sourceId).watchdog() } catch (_: Exception) {} }
                }
            }
            handler.postDelayed(this, 1000)
        }
    }
    private var tick = 0
    private var watchdogTick = 0

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
        tvView = findViewById(R.id.tv_view)
        tvView.setCallback(object : android.media.tv.TvView.TvInputCallback() {
            override fun onVideoAvailable(inputId: String) {
                tStatus.text = ""
                scheduleHide()
            }

            override fun onVideoUnavailable(inputId: String, reason: Int) {
                tStatus.text = when (reason) {
                    android.media.tv.TvInputManager.VIDEO_UNAVAILABLE_REASON_TUNING -> "Réglage de la chaîne…"
                    android.media.tv.TvInputManager.VIDEO_UNAVAILABLE_REASON_WEAK_SIGNAL -> "Signal faible ou absent (antenne / parabole ?)"
                    android.media.tv.TvInputManager.VIDEO_UNAVAILABLE_REASON_BUFFERING -> "Chargement…"
                    android.media.tv.TvInputManager.VIDEO_UNAVAILABLE_REASON_AUDIO_ONLY -> "Radio (audio seul)"
                    else -> "Image indisponible"
                }
            }

            override fun onConnectionFailed(inputId: String) = tunerFallback()
            override fun onDisconnected(inputId: String) = tunerFallback()
        })

        player = PlayerFactory.create(this)
        playerView.player = player
        playerView.setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
        player.addListener(listener)
        player.addAnalyticsListener(analytics)

        resizeMode = when (Prefs.aspect) {
            1 -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            2 -> AspectRatioFrameLayout.RESIZE_MODE_FILL
            else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
        }
        playerView.resizeMode = resizeMode
        setupGestures()
        addOnPictureInPictureModeChangedListener { info ->
            if (!info.isInPictureInPictureMode) applyLayout()
            if (info.isInPictureInPictureMode) {
                this.info.visibility = View.GONE
                side.visibility = View.GONE
                statsView.visibility = View.GONE
            }
        }

        rowAdapter = ChannelRowAdapter(this, emptyList())
        sideList.adapter = rowAdapter
        setupPanel()
        sideList.setOnItemClickListener { _, _, pos, _ ->
            when {
                sideGroups -> pickSideGroup(pos)
                sideItems === list -> { side.visibility = View.GONE; goTo(pos) }
                else -> playFromOtherCategory(pos)
            }
        }
        // Touche gauche dans la liste : chaînes → catégories → fermeture.
        sideList.setOnKeyListener { _, code, ev ->
            if (code != KeyEvent.KEYCODE_DPAD_LEFT || ev.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            if (sideGroups) { side.visibility = View.GONE; playerView.requestFocus() } else showSideGroups()
            true
        }
        start(intent)
        applyLayout()
        handler.post(ticker)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        start(intent)
    }

    private fun start(i: Intent) {
        val key = i.getStringExtra("key") ?: i.data?.getQueryParameter("key")
        overrideUrl = i.getStringExtra("url")
        overrideTitle = i.getStringExtra("title")
        val ch = key?.let { Repo.findChannelQuick(it) }
        if (ch == null) {
            // Chaîne absente des listes courtes (ouverture depuis l'accueil du téléviseur) :
            // la liste de la source est lue en arrière-plan, sans bloquer l'écran.
            if (key == null) { Ui.toast(this, "Chaîne introuvable. Actualisez la source."); finish(); return }
            lifecycleScope.launch {
                val found = withContext(Dispatchers.Default) {
                    try {
                        Repo.findChannel(key)?.let { c ->
                            c to com.elarbi.omniiptv.data.Edits.view(c.sourceId).filter { it.group == c.group }.ifEmpty { listOf(c) }
                        }
                    } catch (e: Throwable) { null }
                }
                if (found == null) { Ui.toast(this@PlayerActivity, "Chaîne introuvable. Actualisez la source."); finish(); return@launch }
                Repo.playContext = found.second
                start(i)
            }
            return
        }
        list = when {
            Repo.playContext.any { it.key == ch.key } -> Repo.playContext
            Repo.isFavorite(ch.key) -> Repo.favorites.values.toList()
            else -> com.elarbi.omniiptv.data.FavLists.lists().map { com.elarbi.omniiptv.data.FavLists.items(it.id) }
                .firstOrNull { l -> l.any { it.key == ch.key } } ?: listOf(ch)
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
        if (Prefs.isLocked(ch)) {
            player.stop()
            showInfo(ch, "Chaîne verrouillée (contrôle parental).")
            Ui.askPin(this) { playCurrent() }
            return
        }
        if (ch.url.startsWith("tuner:")) {
            playTuner(ch)
            return
        }
        if (tvView.visibility == View.VISIBLE) {
            tvView.reset()
            tvView.visibility = View.GONE
            playerView.visibility = View.VISIBLE
        }
        retried = false
        reconnects = 0
        liveRetries = 0
        pendingReconnect = false
        handler.removeCallbacks(stallRun)
        if (ch.url.startsWith("stalker:")) lifecycleScope.launch(Dispatchers.IO) {
            try { Repo.stalker(ch.sourceId).watchdog() } catch (_: Throwable) {}
        }
        stalkerHls = false
        currentUrl = ""
        droppedFrames = 0
        speedBps = 0
        qualityLabel = "Auto"
        audioNotified = false
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .clearOverridesOfType(C.TRACK_TYPE_VIDEO)
            .clearOverridesOfType(C.TRACK_TYPE_AUDIO)
            .setPreferredAudioLanguage(Prefs.channelAudio(ch.key).ifBlank { Prefs.audioLang.ifBlank { java.util.Locale.getDefault().language } })
            .build()
        playerView.useController = ch.isVod
        showInfo(ch, "Connexion…")
        lifecycleScope.launch(Dispatchers.IO) {
            Repo.addRecent(ch)
            if (!ch.isVod) HomeScreen.addWatchNext(applicationContext, ch)
            com.elarbi.omniiptv.home.Shortcuts.update(applicationContext)
            Repo.fetchShortEpg(ch)
        }
        job?.cancel()
        job = lifecycleScope.launch {
            try {
                val replay = overrideUrl
                overrideUrl = null
                val attempt = if (StreamResolver.isStalker(ch)) goodAttempt[ch.sourceId] ?: 0 else 0
                lastAttempt = attempt
                val url = replay ?: StreamResolver.resolve(ch, attempt)
                if (replay != null) tStatus.text = "Replay : ${overrideTitle.orEmpty()}"
                currentUrl = url
                PlayerFactory.play(
                    this@PlayerActivity, player, url, StreamResolver.userAgent(ch), ch.referer,
                    forceHls = YouTube.isYouTube(ch.url) || StreamResolver.looksHls(url), headers = StreamResolver.headers(ch, attempt)
                )
                if (ch.isVod) {
                    val pos = Library.position(ch.key)
                    if (pos != null && pos > 0) {
                        player.seekTo(pos)
                        tStatus.text = "Reprise à ${clock(pos)} — → Options > « Recommencer » pour revenir au début"
                    }
                }
            } catch (e: YouTube.EmbedRequired) {
                openEmbed(ch, e.videoId)
            } catch (e: YouTube.NotLiveException) {
                player.stop()
                showInfo(ch, "Pas de direct en cours sur cette chaîne YouTube. Changez de chaîne avec ↑/↓.")
                handler.removeCallbacks(hideInfo)
            } catch (e: Exception) {
                tStatus.text = "Erreur : ${e.message}"
            }
        }
    }

    private var audioNotified = false

    private val listener = object : Player.Listener {
        override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
            if (audioNotified) return
            val langs = audioTracks().mapNotNull { langName(it.second.language) }.distinct()
            if (langs.size >= 2) {
                audioNotified = true
                android.widget.Toast.makeText(this@PlayerActivity,
                    "🔊 ${langs.size} langues : ${langs.joinToString(", ")} — touche Audio, ou Options > Langue audio",
                    android.widget.Toast.LENGTH_LONG).show()
            }
        }

        override fun onPlaybackStateChanged(state: Int) {
            when (state) {
                Player.STATE_READY -> {
                    reconnects = 0
                    handler.removeCallbacks(stallRun)
                    list.getOrNull(index)?.let { c -> if (StreamResolver.isStalker(c)) goodAttempt[c.sourceId] = lastAttempt }
                    // Lecture stable pendant 20 s : le compteur de relances repart à zéro.
                    handler.removeCallbacks(stableRun); handler.postDelayed(stableRun, 20_000)
                    if (tStatus.text.startsWith("Chargement") || tStatus.text.startsWith("Connexion") || tStatus.text.startsWith("Reconnexion")) tStatus.text = ""
                    scheduleHide()
                }
                Player.STATE_BUFFERING -> {
                    tStatus.text = "Chargement…"
                    // Flux figé (le serveur n'envoie plus rien sans couper) : relance après 15 s (30 s pour un film).
                    handler.removeCallbacks(stallRun)
                    handler.postDelayed(stallRun, if (list.getOrNull(index)?.isVod == true) 30_000L else 15_000L)
                }
                Player.STATE_ENDED -> {
                    handler.removeCallbacks(stallRun)
                    val c = list.getOrNull(index)
                    // Un direct n'a pas de fin : si le serveur ferme le flux, on le relance.
                    if (c != null && !c.isVod) reconnect(c, "le serveur a fermé le flux") else onEnded()
                }
                else -> handler.removeCallbacks(stallRun)
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            val ch = list.getOrNull(index) ?: return
            if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                player.seekToDefaultPosition()
                player.prepare()
                return
            }
            handler.removeCallbacks(stallRun)
            // Portail MAC : relance avec un lien neuf, en alternant HLS / MPEG-TS si le format n'est pas reconnu.
            if (StreamResolver.isStalker(ch)) {
                if (isFormatError(error)) stalkerHls = !stalkerHls
                reconnect(ch, explain(error))
                return
            }
            if (!retried && !StreamResolver.isStalker(ch) && currentUrl.isNotBlank() && !currentUrl.contains(".m3u8")) {
                retried = true
                val alt = if (currentUrl.endsWith(".ts")) currentUrl.removeSuffix(".ts") + ".m3u8" else currentUrl
                PlayerFactory.play(this@PlayerActivity, player, alt, StreamResolver.userAgent(ch), ch.referer, forceHls = true)
                return
            }
            if (YouTube.isYouTube(ch.url)) {
                YouTube.videoId(currentUrl)?.let { openEmbed(ch, it); return }
                lifecycleScope.launch {
                    try {
                        val st = kotlinx.coroutines.withContext(Dispatchers.IO) { YouTube.resolveStream(ch.url) }
                        openEmbed(ch, st.videoId)
                    } catch (_: Exception) {
                        showInfo(ch, "Lecture YouTube impossible. Changez de chaîne avec ↑/↓.")
                    }
                }
                return
            }
            if (currentUrl.isNotBlank() && Prefs.autoReconnect) { reconnect(ch, explain(error)); return }
            val hint = if (StreamResolver.isStalker(ch)) " Réglages > Mes sources > diagnostic du portail pour le détail." else ""
            showInfo(ch, "Lecture impossible : ${explain(error)}.$hint")
            handler.removeCallbacks(hideInfo)
        }
    }

    /** Chaîne du téléviseur (TNT / satellite / câble) jouée dans l'application via TvView. */
    private fun playTuner(ch: Channel) {
        val (input, uri) = TunerSource.parse(ch.url) ?: return
        player.stop()
        playerView.visibility = View.INVISIBLE
        tvView.visibility = View.VISIBLE
        tunerFailed = false
        showInfo(ch, "Réglage de la chaîne…")
        lifecycleScope.launch(Dispatchers.IO) { Repo.addRecent(ch) }
        try {
            tvView.tune(input, android.net.Uri.parse(uri))
        } catch (e: Exception) {
            tunerFallback()
        }
    }

    /** Si le téléviseur refuse la lecture dans l'application : ouverture dans l'application TV du système. */
    private fun tunerFallback() {
        if (tunerFailed) return
        tunerFailed = true
        val ch = list.getOrNull(index) ?: return
        val (_, uri) = TunerSource.parse(ch.url) ?: return
        Ui.toast(this, "Le téléviseur réserve ce tuner à son application TV : ouverture de la chaîne…")
        try {
            startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(uri)))
        } catch (e: Exception) {
            showInfo(ch, "Impossible d'ouvrir cette chaîne du téléviseur.")
        }
    }

    /** Explication lisible d'une erreur de lecture (code HTTP, serveur, format). */
    private fun explain(error: PlaybackException): String {
        var c: Throwable? = error
        while (c != null) {
            if (c is androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException) {
                val host = try { android.net.Uri.parse(c.dataSpec.uri.toString()).host } catch (e: Exception) { null }
                val why = when (c.responseCode) {
                    401, 403 -> "accès refusé (lien expiré, MAC non autorisée ou connexion déjà utilisée ailleurs)"
                    404 -> "flux introuvable"
                    429, 458, 509 -> "nombre maximal de connexions atteint (abonnement utilisé ailleurs ?)"
                    in 500..599 -> "erreur du serveur de diffusion"
                    else -> "réponse inattendue"
                }
                return "le serveur ${host ?: ""} répond HTTP ${c.responseCode} — $why"
            }
            if (c is androidx.media3.datasource.HttpDataSource.HttpDataSourceException && c !is androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException) {
                val host = try { android.net.Uri.parse(c.dataSpec.uri.toString()).host } catch (e: Exception) { null }
                return "serveur de diffusion ${host ?: ""} injoignable (${c.cause?.javaClass?.simpleName ?: "réseau"})"
            }
            if (c is androidx.media3.common.ParserException) return "format du flux non reconnu"
            c = c.cause
        }
        return when (error.errorCode) {
            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED, PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES -> "format vidéo non pris en charge par ce téléviseur (H.265 / 4K ?)"
            else -> error.errorCodeName
        }
    }

    private var stalkerHls = false

    private fun isFormatError(e: PlaybackException): Boolean {
        var c: Throwable? = e
        while (c != null) {
            if (c is androidx.media3.common.ParserException) return true
            c = c.cause
        }
        return e.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED ||
            e.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED ||
            e.errorCode == PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED ||
            e.errorCode == PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED
    }

    /** Redemande un lien de lecture au portail MAC et relance la lecture. */
    /**
     * Relance automatique après une coupure : lien neuf, pause courte puis croissante (0,5 s → 5 s),
     * sans limite tant que le flux finit par repartir (30 échecs d'affilée au plus).
     * Les films reprennent à la position où ils se sont arrêtés.
     */
    private fun reconnect(ch: Channel, reason: String) {
        if (pendingReconnect) return
        if (!Prefs.autoReconnect) {
            showInfo(ch, "Lecture interrompue : $reason. (Reconnexion automatique désactivée dans Réglages > Lecture.)")
            handler.removeCallbacks(hideInfo)
            return
        }
        if (liveRetries >= 30) {
            showInfo(ch, "Flux indisponible après 30 relances ($reason). Changez de chaîne ou revenez-y plus tard." +
                if (StreamResolver.isStalker(ch)) " Sources > portail > diagnostic pour le détail." else "")
            handler.removeCallbacks(hideInfo)
            return
        }
        liveRetries++
        pendingReconnect = true
        val resumeAt = if (ch.isVod) player.currentPosition.takeIf { it > 0 } ?: (Library.position(ch.key) ?: 0L) else -1L
        val delay = when (liveRetries) { 1 -> 500L; 2 -> 1_500L; 3 -> 3_000L; else -> 5_000L }
        showInfo(ch, "Reconnexion automatique ($liveRetries)… $reason")
        tStatus.text = "Reconnexion…"
        handler.postDelayed({
            pendingReconnect = false
            if (list.getOrNull(index)?.key != ch.key || isFinishing ||
                !lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) return@postDelayed
            if (StreamResolver.isStalker(ch)) restartStalker(ch, resumeAt) else restartStream(ch, resumeAt)
        }, delay)
    }

    /** Relance d'une source ordinaire (Xtream, M3U, YouTube…) : lien résolu à nouveau (jetons à jour). */
    private fun restartStream(ch: Channel, resumeAt: Long) {
        lifecycleScope.launch {
            try {
                val url = StreamResolver.resolve(ch)
                currentUrl = url
                PlayerFactory.play(this@PlayerActivity, player, url, StreamResolver.userAgent(ch), ch.referer,
                    forceHls = YouTube.isYouTube(ch.url) || StreamResolver.looksHls(url), headers = StreamResolver.headers(ch))
                if (resumeAt > 0) player.seekTo(resumeAt)
            } catch (e: Throwable) {
                reconnect(ch, e.message ?: "erreur réseau")
            }
        }
    }

    private fun restartStalker(ch: Channel, resumeAt: Long = -1L) {
        lifecycleScope.launch {
            try {
                // Chaque essai change de stratégie (lien temporaire, lien listé, lien construit),
                // puis d'en-têtes (boîtier MAG complet, puis User-Agent seul).
                // Méthode qui a marché pour ce portail d'abord ; les relances suivantes essaient les autres.
                val attempt = (goodAttempt[ch.sourceId] ?: 0) + (liveRetries - 1).coerceAtLeast(0)
                lastAttempt = attempt
                val url = StreamResolver.resolve(ch, attempt)
                currentUrl = url
                PlayerFactory.play(this@PlayerActivity, player, url, StreamResolver.userAgent(ch), ch.referer,
                    forceHls = stalkerHls || StreamResolver.looksHls(url), headers = StreamResolver.headers(ch, attempt))
                if (resumeAt > 0) player.seekTo(resumeAt)
            } catch (e: Throwable) {
                // Portail injoignable un instant (réseau, session) : on réessaie plus tard.
                reconnect(ch, e.message ?: "portail injoignable")
            }
        }
    }

    /** Lecteur YouTube intégré à l'application (sans passer par l'application YouTube). */
    private val embedLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { r ->
        val delta = r.data?.getIntExtra("zap", 0) ?: 0
        if (delta != 0) zap(delta)
    }

    private fun openEmbed(ch: Channel, videoId: String) {
        player.stop()
        tStatus.text = "Lecture dans le lecteur YouTube intégré…"
        embedLauncher.launch(
            Intent(this, YouTubeEmbedActivity::class.java).putExtra("videoId", videoId).putExtra("title", ch.name)
        )
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
        val clock = if (Prefs.showClock) "${Ui.hm(System.currentTimeMillis())}  •  " else ""
        tSub.text = clock + marks + ch.group
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
        updatePanel(ch)
        if (portraitPhone()) {
            info.visibility = View.GONE
            return
        }
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
        if (::mpList.isInitialized) findViewById<TextView>(R.id.mp_tech).text = tTech.text
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
            com.elarbi.omniiptv.player.AdaptiveLoadControl.current?.let { appendLine("Mémoire tampon  : ${it.describe()}") }
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
        val audios = audioTracks()
        val cur = audios.firstOrNull { it.third }?.second
        items += "🔊 Langue audio" + (if (audios.size > 1) " (${audios.size} pistes" + (cur?.let { " • " + audioLabel(it) } ?: "") + ")" else "") to { audioMenu() }
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
        items += "⚙ Réglages de l'application" to { startActivity(Intent(this, SettingsActivity::class.java)) }
        val left = if (sleepAt > System.currentTimeMillis()) " (reste ${(sleepAt - System.currentTimeMillis()) / 60000 + 1} min)" else ""
        items += "Minuterie de mise en veille$left" to { sleepMenu() }
        if (packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)) {
            items += "Image dans l'image" to { enterPip() }
        }
        AlertDialog.Builder(this)
            .setTitle(ch.name)
            .setItems(items.map { it.first }.toTypedArray()) { _, w -> items[w].second() }
            .show()
    }

    /** Pistes audio lisibles du flux : (groupe, format, sélectionnée). */
    private fun audioTracks(): List<Triple<Pair<androidx.media3.common.Tracks.Group, Int>, Format, Boolean>> {
        val out = ArrayList<Triple<Pair<androidx.media3.common.Tracks.Group, Int>, Format, Boolean>>()
        for (g in player.currentTracks.groups) {
            if (g.type != C.TRACK_TYPE_AUDIO) continue
            for (i in 0 until g.length) if (g.isTrackSupported(i)) out += Triple(g to i, g.getTrackFormat(i), g.isTrackSelected(i))
        }
        return out
    }

    /** Nom de la langue en français (« fr » → « Français »), null si le flux ne l'indique pas. */
    private fun langName(code: String?): String? {
        if (code.isNullOrBlank() || code in setOf("und", "mul", "zxx", "mis", "qaa")) return null
        val n = java.util.Locale.forLanguageTag(code).getDisplayLanguage(java.util.Locale.FRENCH)
        return if (n.isBlank() || n.equals(code, true)) code.uppercase() else n.replaceFirstChar { it.uppercase() }
    }

    private fun audioLabel(f: Format): String {
        val lang = langName(f.language)
        val name = f.label?.takeIf { it.isNotBlank() && !it.equals(lang, true) }
        val ch = when (f.channelCount) { 1 -> "mono"; 2 -> "stéréo"; 6 -> "5.1"; 8 -> "7.1"; else -> if (f.channelCount > 0) "${f.channelCount} canaux" else null }
        val codecName = when (codec(f)) { "AC3" -> "Dolby Digital"; "E-AC3" -> "Dolby Digital+"; else -> codec(f) }
        return listOfNotNull(lang ?: name ?: "Langue non indiquée", if (lang != null) name else null, codecName, ch).joinToString(" · ")
    }

    /** Choix de la langue audio : retenue pour la chaîne ; langue préférée réglable pour toutes. */
    private fun audioMenu() {
        val ch = list.getOrNull(index) ?: return
        val tracks = audioTracks()
        if (tracks.isEmpty()) { Ui.toast(this, "Aucune piste audio pour le moment (attendez le début de la lecture)."); return }
        val labels = tracks.mapIndexed { n, t -> (if (t.third) "● " else "    ") + "${n + 1}. " + audioLabel(t.second) }
        AlertDialog.Builder(this)
            .setTitle(if (tracks.size > 1) "Langue audio — ${tracks.size} pistes" else "Langue audio (une seule piste)")
            .setItems(labels.toTypedArray()) { _, w ->
                val (gi, f, _) = tracks[w]
                player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false)
                    .setOverrideForType(TrackSelectionOverride(gi.first.mediaTrackGroup, gi.second))
                    .build()
                // Retenue pour cette chaîne (si le flux indique la langue).
                f.language?.takeIf { langName(it) != null }?.let { Prefs.setChannelAudio(ch.key, it) }
                Ui.toast(this, "🔊 ${audioLabel(f)}" + if (langName(f.language) != null) " — retenue pour « ${ch.name} »" else "")
            }
            .setNeutralButton("Langue préférée…") { _, _ -> preferredLanguageMenu() }
            .setNegativeButton("Fermer", null).show()
    }

    private fun preferredLanguageMenu() {
        val codes = listOf("", "fr", "ar", "en", "es", "de", "it", "tr", "pt", "nl")
        val names = codes.map { if (it.isEmpty()) "Langue du téléviseur" else langName(it) ?: it }
        AlertDialog.Builder(this).setTitle("Langue audio préférée (toutes les chaînes)")
            .setSingleChoiceItems(names.toTypedArray(), codes.indexOf(Prefs.audioLang).coerceAtLeast(0)) { d, w ->
                d.dismiss()
                Prefs.audioLang = codes[w]
                player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                    .clearOverridesOfType(C.TRACK_TYPE_AUDIO)
                    .setPreferredAudioLanguage(codes[w].ifBlank { java.util.Locale.getDefault().language })
                    .build()
                Ui.toast(this, "Langue préférée : ${names[w]} (utilisée quand le flux la propose)")
            }.show()
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

    private fun sleepMenu() {
        val choices = listOf(0, 15, 30, 45, 60, 90, 120)
        AlertDialog.Builder(this).setTitle("Arrêter la lecture dans…")
            .setItems(choices.map { if (it == 0) "Désactiver" else "$it minutes" }.toTypedArray()) { _, w ->
                handler.removeCallbacks(sleepRunnable)
                val m = choices[w]
                if (m == 0) { sleepAt = 0; Ui.toast(this, "Minuterie désactivée") }
                else {
                    sleepAt = System.currentTimeMillis() + m * 60_000L
                    handler.postDelayed(sleepRunnable, m * 60_000L)
                    Ui.toast(this, "Arrêt automatique dans $m minutes")
                }
            }.show()
    }

    private fun enterPip() {
        try {
            enterPictureInPictureMode(PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9)).build())
        } catch (e: Exception) {
            Ui.toast(this, "Image dans l'image non disponible sur cet appareil.")
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        // Sur téléphone/tablette : passage automatique en image dans l'image en quittant pendant la lecture.
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK) &&
            packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE) && player.isPlaying
        ) enterPip()
    }

    // ---------------- Écrans tactiles ----------------
    private lateinit var audio: AudioManager
    private var gestureValue = -1f

    private var swipeDone = false

    /** Zapping par glissement : affiche le nom de la chaîne atteinte. */
    private fun swipeZap(delta: Int) {
        val ch = list.getOrNull(index) ?: return
        if (ch.isVod) {
            // Film : glisser = avancer / reculer de 30 s
            player.seekTo((player.currentPosition + if (delta > 0) 30_000 else -30_000).coerceAtLeast(0))
            showGestureText(if (delta > 0) "⏩ +30 s" else "⏪ −30 s")
            return
        }
        if (list.size < 2) return
        zap(delta)
        list.getOrNull(index)?.let { showGestureText((if (delta > 0) "▶  " else "◀  ") + "${index + 1}. ${it.name}") }
    }

    /**
     * Détecteur de geste : glisser à gauche = chaîne suivante, à droite = précédente
     * (déclenché dès que le doigt a parcouru assez de distance, même lentement).
     */
    private fun gestureDetector(withVerticalControls: Boolean): GestureDetector {
        val swipeDist = Ui.dp(this, 70)
        return GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean { gestureValue = -1f; swipeDone = false; return true }

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                val ch = list.getOrNull(index) ?: return false
                if (ch.isVod) return false
                if (portraitPhone()) { showInfo(ch, null); return true }
                if (info.visibility == View.VISIBLE) info.visibility = View.GONE else showInfo(ch, null)
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (portraitPhone() && withVerticalControls) toggleFullscreen() else options()
                return true
            }

            override fun onLongPress(e: MotionEvent) { if (!portraitPhone()) openSide() else options() }

            override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
                if (swipeDone) return true
                val start = e1 ?: return false
                val dx = e2.x - start.x
                val dy = e2.y - start.y
                if (Math.abs(dx) > Math.abs(dy) * 1.3f && Math.abs(dx) > swipeDist / 2) {
                    swipeDone = true
                    swipeZap(if (dx < 0) 1 else -1)
                    return true
                }
                return false
            }

            override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
                val start = e1 ?: return false
                val dx = e2.x - start.x
                val dy = e2.y - start.y
                if (Math.abs(dx) > Math.abs(dy) * 1.3f) {
                    // Glissement horizontal : zapping dès que la distance est suffisante
                    if (!swipeDone && Math.abs(dx) > swipeDist) {
                        swipeDone = true
                        swipeZap(if (dx < 0) 1 else -1)
                    }
                    return true
                }
                if (!withVerticalControls || swipeDone) return false
                val h = playerView.height.coerceAtLeast(1).toFloat()
                val delta = distanceY / h * 1.5f
                if (start.x < playerView.width / 2f) {
                    // Moitié gauche : luminosité
                    val lp = window.attributes
                    if (gestureValue < 0) gestureValue = if (lp.screenBrightness < 0) 0.5f else lp.screenBrightness
                    gestureValue = (gestureValue + delta).coerceIn(0.02f, 1f)
                    lp.screenBrightness = gestureValue
                    window.attributes = lp
                    showGestureText("☀ ${(gestureValue * 100).toInt()} %")
                } else {
                    // Moitié droite : volume
                    val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).toFloat()
                    if (gestureValue < 0) gestureValue = audio.getStreamVolume(AudioManager.STREAM_MUSIC) / max
                    gestureValue = (gestureValue + delta).coerceIn(0f, 1f)
                    audio.setStreamVolume(AudioManager.STREAM_MUSIC, (gestureValue * max).toInt(), 0)
                    showGestureText("🔊 ${(gestureValue * 100).toInt()} %")
                }
                return true
            }
        })
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupGestures() {
        audio = getSystemService(AUDIO_SERVICE) as AudioManager
        val detector = gestureDetector(true)
        playerView.setOnTouchListener { v, e ->
            val handled = detector.onTouchEvent(e)
            val vod = list.getOrNull(index)?.isVod == true
            if (vod && !handled) v.onTouchEvent(e)
            true
        }
        // Bandeau d'informations sous la vidéo (téléphone en portrait) : on peut aussi y glisser pour zapper.
        val panelDetector = gestureDetector(false)
        findViewById<View>(R.id.mp_header).setOnTouchListener { _, e -> panelDetector.onTouchEvent(e); true }
    }

    // ---------------- Téléphone : portrait / plein écran ----------------
    private val phoneUi by lazy { AppActions.usePhoneUi(this) }

    private fun portraitPhone() = phoneUi &&
        resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT &&
        !isInPictureInPictureMode

    private fun applyLayout() {
        val panel = findViewById<View>(R.id.mobile_panel)
        val lp = playerView.layoutParams as android.widget.FrameLayout.LayoutParams
        val insets = androidx.core.view.WindowCompat.getInsetsController(window, window.decorView)
        if (portraitPhone()) {
            val h = resources.displayMetrics.widthPixels * 9 / 16
            lp.height = h
            lp.gravity = android.view.Gravity.TOP
            playerView.layoutParams = lp
            (panel.layoutParams as android.widget.FrameLayout.LayoutParams).topMargin = h
            panel.requestLayout()
            panel.visibility = View.VISIBLE
            info.visibility = View.GONE
            side.visibility = View.GONE
            insets.show(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            mpList.setSelection(index)
            mpList.setItemChecked(index, true)
        } else {
            lp.height = android.widget.FrameLayout.LayoutParams.MATCH_PARENT
            lp.gravity = android.view.Gravity.NO_GRAVITY
            playerView.layoutParams = lp
            panel.visibility = View.GONE
            if (phoneUi) {
                insets.systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                insets.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            }
        }
    }

    private fun toggleFullscreen() {
        requestedOrientation = if (resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT)
            android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        else android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        applyLayout()
        list.getOrNull(index)?.let { updatePanel(it) }
    }

    private lateinit var mpList: ListView

    private fun setupPanel() {
        mpList = findViewById(R.id.mp_list)
        mpList.adapter = rowAdapter
        mpList.setOnItemClickListener { _, _, pos, _ -> goTo(pos) }
        findViewById<View>(R.id.mp_fav).setOnClickListener { toggleFav() }
        findViewById<View>(R.id.mp_quality).setOnClickListener { trackMenu(C.TRACK_TYPE_VIDEO) }
        findViewById<View>(R.id.mp_options).setOnClickListener { options() }
        findViewById<View>(R.id.mp_full).setOnClickListener { toggleFullscreen() }
        findViewById<View>(R.id.mp_pip).setOnClickListener { enterPip() }
        findViewById<View>(R.id.mp_prev).setOnClickListener { swipeZap(-1) }
        findViewById<View>(R.id.mp_next).setOnClickListener { swipeZap(1) }
    }

    private fun updatePanel(ch: Channel) {
        if (!::mpList.isInitialized) return
        findViewById<TextView>(R.id.mp_title).text = "${index + 1}. ${ch.name}"
        val (now, next) = if (ch.isVod) null to null else Repo.nowNext(ch)
        findViewById<TextView>(R.id.mp_now).text = when {
            ch.isVod -> Library.progress(ch.key)?.let { "Déjà vu : $it %" } ?: ch.group
            now != null -> "${Ui.hm(now.start)}–${Ui.hm(now.end)}  ${now.title}"
            else -> ch.group
        }
        findViewById<TextView>(R.id.mp_next_prog).text = next?.let { "Ensuite ${Ui.hm(it.start)}  ${it.title}" } ?: ""
        val bar = findViewById<ProgressBar>(R.id.mp_progress)
        if (now != null) {
            val t = System.currentTimeMillis()
            bar.progress = (((t - now.start) * 100) / (now.end - now.start).coerceAtLeast(1)).toInt()
            bar.visibility = View.VISIBLE
        } else bar.visibility = View.GONE
        findViewById<TextView>(R.id.mp_fav).text = if (Repo.isFavorite(ch.key)) "★ Favori" else "☆ Favori"
        findViewById<TextView>(R.id.mp_tech).text = tTech.text
        val logoView = findViewById<ImageView>(R.id.mp_logo)
        if (ch.logo.isNotBlank()) Glide.with(this).load(ch.logo).error(R.drawable.ic_tv).into(logoView) else logoView.setImageResource(R.drawable.ic_tv)
        mpList.setItemChecked(index, true)
        if (index < mpList.firstVisiblePosition || index > mpList.lastVisiblePosition) mpList.setSelection(index)
    }

    private val hideGesture = Runnable { numberView.visibility = View.GONE }

    private fun showGestureText(t: String) {
        numberView.text = t
        numberView.visibility = View.VISIBLE
        handler.removeCallbacks(hideGesture)
        handler.postDelayed(hideGesture, 1200)
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
                Prefs.aspect = w
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

    // ---------- Liste latérale : chaînes, puis catégories (touche gauche) ----------
    private var sideGroups = false
    private var sideItems: List<Channel> = emptyList()
    private var sideAll: List<Channel> = emptyList()
    private var sideGroupNames: List<String> = emptyList()
    private var sideSource: String? = null

    private fun showSideGroups() {
        val ch = list.getOrNull(index) ?: return
        sideTitle.text = "Catégories • chargement…"
        lifecycleScope.launch {
            val all = if (sideSource == ch.sourceId && sideAll.isNotEmpty()) sideAll else withContext(Dispatchers.Default) {
                try {
                    com.elarbi.omniiptv.data.Edits.view(ch.sourceId)
                        .filter { it.isVod == ch.isVod && !it.isSeries && !it.isEpisode == !ch.isEpisode }
                } catch (e: Throwable) { com.elarbi.omniiptv.data.CrashLog.record("Catégories du lecteur", e); emptyList() }
            }
            if (side.visibility != View.VISIBLE) return@launch
            sideAll = all
            sideSource = ch.sourceId
            val counts = LinkedHashMap<String, Int>()
            for (c in all) counts[c.group] = (counts[c.group] ?: 0) + 1
            if (counts.isEmpty()) counts[ch.group] = list.size
            sideGroupNames = counts.keys.toList()
            sideGroups = true
            sideTitle.text = "Catégories (${counts.size}) • OK : ouvrir • ◀ : fermer"
            sideList.adapter = android.widget.ArrayAdapter(this@PlayerActivity, R.layout.item_text,
                counts.map { (g, n) -> "${g.removePrefix("Films · ").removePrefix("Séries · ")}  ($n)" })
            val cur = sideGroupNames.indexOf(sideTitleGroup ?: ch.group).coerceAtLeast(0)
            sideList.setSelection(cur)
            sideList.requestFocus()
        }
    }

    private var sideTitleGroup: String? = null

    private fun pickSideGroup(pos: Int) {
        val g = sideGroupNames.getOrNull(pos) ?: return
        sideItems = sideAll.filter { it.group == g }.ifEmpty { list }
        sideGroups = false
        sideTitleGroup = g
        sideList.adapter = if (sideItems === list) rowAdapter else ChannelRowAdapter(this, sideItems)
        sideTitle.text = "$g • ◀ : catégories"
        val cur = sideItems.indexOfFirst { it.key == list.getOrNull(index)?.key }.coerceAtLeast(0)
        sideList.setSelection(cur)
        sideList.requestFocus()
    }

    /** Chaîne choisie dans une autre catégorie : elle devient la liste de zapping. */
    private fun playFromOtherCategory(pos: Int) {
        if (pos !in sideItems.indices) return
        saveProgress(true)
        previousIndex = -1
        list = sideItems
        Repo.playContext = list
        rowAdapter.items = list
        rowAdapter.notifyDataSetChanged()
        index = pos
        side.visibility = View.GONE
        playCurrent()
    }

    private fun openSide() {
        sideGroups = false
        sideItems = list
        sideTitleGroup = list.getOrNull(index)?.group
        sideList.adapter = rowAdapter
        rowAdapter.items = list
        sideTitle.text = (list.getOrNull(index)?.group ?: "") + " • ◀ : catégories"
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
            KeyEvent.KEYCODE_MEDIA_AUDIO_TRACK, KeyEvent.KEYCODE_PROG_YELLOW -> { audioMenu(); return true }
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
        if (phoneUi && !portraitPhone() && !isInPictureInPictureMode && side.visibility != View.VISIBLE) {
            requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            return
        }
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

    /** Réseau coupé puis rétabli : relance immédiate de la chaîne. */
    private var netLost = false
    private val netCallback = object : android.net.ConnectivityManager.NetworkCallback() {
        override fun onLost(network: android.net.Network) { netLost = true }
        override fun onAvailable(network: android.net.Network) {
            if (!netLost) return
            netLost = false
            runOnUiThread {
                val ch = list.getOrNull(index) ?: return@runOnUiThread
                if (player.playerError != null || player.playbackState != Player.STATE_READY) {
                    handler.removeCallbacks(stallRun)
                    pendingReconnect = false
                    liveRetries = 0
                    reconnect(ch, "réseau rétabli")
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        try { getSystemService(android.net.ConnectivityManager::class.java)?.registerDefaultNetworkCallback(netCallback) } catch (_: Throwable) {}
    }

    override fun onStop() {
        handler.removeCallbacks(stallRun)
        try { getSystemService(android.net.ConnectivityManager::class.java)?.unregisterNetworkCallback(netCallback) } catch (_: Throwable) {}
        saveProgress(true)
        super.onStop()
        player.pause()
        if (tvView.visibility == View.VISIBLE) tvView.reset()
        if (isInPictureInPictureMode) finish()
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

/** Méthode de lien (portail MAC) qui a fonctionné, par source : réutilisée au démarrage et à chaque relance. */
private val goodAttempt = java.util.concurrent.ConcurrentHashMap<String, Int>()
