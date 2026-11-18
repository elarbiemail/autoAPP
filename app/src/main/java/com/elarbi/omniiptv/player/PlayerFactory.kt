package com.elarbi.omniiptv.player

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory

/**
 * Lecteur commun à l'application et à l'onglet Direct.
 * Protocoles : HTTP(S) progressif et MPEG-TS, HLS (m3u8), DASH (mpd), Smooth Streaming,
 * RTSP, RTMP, UDP/RTP multicast.
 */
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
object PlayerFactory {
    /** Réglage du tampon : durées (ms) min, max, avant lecture, après coupure, retour arrière, retard sur le direct. */
    data class Buffer(val label: String, val min: Int, val max: Int, val start: Int, val rebuffer: Int, val back: Int, val liveOffset: Long)

    val presets = linkedMapOf(
        "low" to Buffer("Zapping rapide", 3_000, 15_000, 500, 1_500, 0, 4_000),
        "normal" to Buffer("Équilibré", 10_000, 40_000, 1_000, 2_500, 10_000, 8_000),
        "high" to Buffer("Connexion instable", 25_000, 90_000, 2_000, 5_000, 20_000, 12_000),
        "max" to Buffer("Connexion très instable", 50_000, 180_000, 3_000, 8_000, 30_000, 20_000)
    )

    /** Mémoire accordée à l'application (Mo) et appareil modeste ou non. */
    private fun memory(ctx: Context): Pair<Int, Boolean> {
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        return am.largeMemoryClass to (am.isLowRamDevice || am.memoryClass < 128)
    }

    /** Préréglage effectif (« auto » choisit selon l'appareil). */
    fun buffer(ctx: Context): Buffer {
        val key = com.elarbi.omniiptv.data.Prefs.buffer
        if (key != "auto") return presets[key] ?: presets.getValue("normal")
        return if (memory(ctx).second) presets.getValue("low").copy(label = "Auto (appareil modeste)", min = 6_000, max = 20_000)
        else presets.getValue("normal").copy(label = "Auto")
    }

    /**
     * Plafond du tampon en octets : le tampon est en mémoire vive, on n'en prend qu'une part raisonnable
     * pour éviter les « manques de mémoire » sur les boîtiers TV modestes (16 à 96 Mo).
     */
    fun bufferBytes(ctx: Context): Int {
        val (mb, low) = memory(ctx)
        // Le tampon partage la mémoire avec les listes de chaînes : une part modeste suffit (8 Mb/s × 40 s ≈ 40 Mo).
        val part = if (low) mb / 10 else mb / 6
        return (part.coerceIn(16, 64) * 1024 * 1024)
    }

    /** Résumé lisible du réglage (affiché dans Réglages > Lecture). */
    fun describe(ctx: Context): String {
        val b = buffer(ctx)
        val dec = when (com.elarbi.omniiptv.data.Prefs.decoder) { "hw" -> "matériel seul"; "sw" -> "logiciel FFmpeg prioritaire"; else -> "matériel + secours logiciel" }
        return "${b.label} : démarrage après ${b.start / 1000.0} s, tampon ${b.min / 1000}–${b.max / 1000} s, " +
            "plafond ${bufferBytes(ctx) / 1048576} Mo, ${b.liveOffset / 1000} s derrière le direct • décodage $dec"
    }

    /** [preview] : lecteur léger pour l'aperçu de Direct (petit tampon, démarrage immédiat). */
    fun create(ctx: Context, preview: Boolean = false): ExoPlayer {
        val prefs = com.elarbi.omniiptv.data.Prefs
        // Décodage : matériel d'abord, avec secours sur un autre décodeur ; FFmpeg pour les sons que le téléviseur
        // ne sait pas lire (AC3, E-AC3, DTS, MP2… fréquents en IPTV).
        val renderers = DefaultRenderersFactory(ctx)
            .setEnableDecoderFallback(prefs.decoder != "hw")
            .setExtensionRendererMode(
                when (prefs.decoder) {
                    "hw" -> DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF
                    "sw" -> DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER
                    else -> DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON
                }
            )
        val b = if (preview) Buffer("Aperçu", 2_000, 8_000, 500, 1_000, 0, 6_000) else buffer(ctx)
        val load = DefaultLoadControl.Builder()
            .setAllocator(androidx.media3.exoplayer.upstream.DefaultAllocator(true, androidx.media3.common.C.DEFAULT_BUFFER_SEGMENT_SIZE))
            .setBufferDurationsMs(b.min, b.max, b.start, b.rebuffer)
            .setTargetBufferBytes(if (preview) 12 * 1024 * 1024 else bufferBytes(ctx))
            .setPrioritizeTimeOverSizeThresholds(false)
            .setBackBuffer(b.back, true)
            .build()
        val selector = androidx.media3.exoplayer.trackselection.DefaultTrackSelector(ctx)
        val params = selector.buildUponParameters()
            // Mode tunnel : décodage vidéo directement par le téléviseur (plus fluide sur certains modèles).
            .setTunnelingEnabled(prefs.tunneling)
            .setPreferredAudioLanguage(prefs.audioLang.ifBlank { java.util.Locale.getDefault().language })
        if (prefs.maxHeight > 0) params.setMaxVideoSize(prefs.maxHeight * 16 / 9 + 16, prefs.maxHeight)
        selector.setParameters(params)
        return ExoPlayer.Builder(ctx, renderers).setLoadControl(load).setTrackSelector(selector).build()
    }

    fun play(
        ctx: Context, player: ExoPlayer, url: String, userAgent: String,
        referer: String = "", forceHls: Boolean = false, headers: Map<String, String> = emptyMap()
    ) {
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent(userAgent)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(30_000)
        val props = HashMap(headers)
        if (referer.isNotBlank()) props["Referer"] = referer
        if (props.isNotEmpty()) http.setDefaultRequestProperties(props)
        val extractors = DefaultExtractorsFactory().setTsExtractorFlags(
            DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES or DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS
        )
        val l = url.lowercase()
        val mime = when {
            forceHls || com.elarbi.omniiptv.data.StreamResolver.looksHls(url) -> MimeTypes.APPLICATION_M3U8
            l.contains(".mpd") -> MimeTypes.APPLICATION_MPD
            l.contains(".ism") -> MimeTypes.APPLICATION_SS
            else -> null
        }
        // Redirections mémorisées : seulement pour le HLS des portails MAC (rechargements de la playlist
        // derrière un lien à usage unique). Partout ailleurs, lecture directe sans intermédiaire.
        val portal = headers.containsKey("X-User-Agent")
        val base = DefaultDataSource.Factory(ctx, http)
        val dataSource: androidx.media3.datasource.DataSource.Factory =
            if (portal && mime == MimeTypes.APPLICATION_M3U8) StickyRedirectDataSource.Factory(base) else base
        val builder = MediaItem.Builder().setUri(url)
            .setLiveConfiguration(MediaItem.LiveConfiguration.Builder().setTargetOffsetMs(buffer(ctx).liveOffset).build())
        if (mime != null) builder.setMimeType(mime)
        // Nouvel essai automatique sur les erreurs passagères (segment manquant, serveur saturé).
        val source = DefaultMediaSourceFactory(dataSource, extractors)
            .setLoadErrorHandlingPolicy(androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy(6))
            .createMediaSource(builder.build())
        player.setMediaSource(source)
        player.prepare()
        player.playWhenReady = true
    }
}
