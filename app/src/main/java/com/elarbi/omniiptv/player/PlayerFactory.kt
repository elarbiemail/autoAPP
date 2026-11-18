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
    fun create(ctx: Context): ExoPlayer {
        val renderers = DefaultRenderersFactory(ctx)
            .setEnableDecoderFallback(true)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
        val load = when (com.elarbi.omniiptv.data.Prefs.buffer) {
            "low" -> DefaultLoadControl.Builder().setBufferDurationsMs(4_000, 20_000, 700, 1_500).build()
            "high" -> DefaultLoadControl.Builder().setBufferDurationsMs(30_000, 120_000, 2_500, 6_000).build()
            else -> DefaultLoadControl.Builder().setBufferDurationsMs(12_000, 50_000, 1_200, 3_000).build()
        }
        val selector = androidx.media3.exoplayer.trackselection.DefaultTrackSelector(ctx)
        // Mode tunnel : décodage vidéo directement par le téléviseur (plus fluide sur certains modèles).
        selector.setParameters(selector.buildUponParameters().setTunnelingEnabled(com.elarbi.omniiptv.data.Prefs.tunneling))
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
            .setLiveConfiguration(MediaItem.LiveConfiguration.Builder().setTargetOffsetMs(8_000).build())
        if (mime != null) builder.setMimeType(mime)
        val source = DefaultMediaSourceFactory(dataSource, extractors).createMediaSource(builder.build())
        player.setMediaSource(source)
        player.prepare()
        player.playWhenReady = true
    }
}
