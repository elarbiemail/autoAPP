package com.elarbi.omniiptv.player

import androidx.media3.common.C
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.source.MediaSource.MediaPeriodId
import androidx.media3.exoplayer.source.TrackGroupArray
import androidx.media3.exoplayer.trackselection.ExoTrackSelection
import androidx.media3.exoplayer.upstream.Allocator
import androidx.media3.exoplayer.upstream.DefaultAllocator
import kotlin.math.max
import kotlin.math.min

/**
 * Mémoire tampon adaptative :
 *  - taille calculée d'après le débit et la résolution du flux (SD ≈ 15 Mo, Full HD ≈ 50 Mo, 4K ≈ 150 Mo) ;
 *  - plafonnée selon la mémoire de l'appareil ;
 *  - réduite à chaud si la mémoire manque (un quart de la mémoire de l'application reste toujours libre),
 *    et la mémoire inutilisée est rendue aussitôt.
 * Les durées (démarrage, minimum, maximum) viennent du réglage choisi (Réglages > Lecture).
 */
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
class AdaptiveLoadControl(
    private val b: PlayerFactory.Buffer,
    private val lowRam: Boolean,
    private val preview: Boolean
) : LoadControl {
    private val allocator = DefaultAllocator(true, C.DEFAULT_BUFFER_SEGMENT_SIZE)
    private val rt = Runtime.getRuntime()
    private val minUs = b.min * 1000L
    private val maxUs = b.max * 1000L
    private val startUs = b.start * 1000L
    private val rebufferUs = b.rebuffer * 1000L
    private val backUs = min(b.back, 10_000) * 1000L

    /** Plafond absolu pour cet appareil. */
    val ceiling: Int = if (preview) 12 * MB else
        (rt.maxMemory() * (if (lowRam) 0.25 else 0.40)).toLong().coerceIn(16L * MB, (if (lowRam) 64L else 192L) * MB).toInt()

    @Volatile var target: Int = MIN_BYTES; private set
    @Volatile var effective: Int = MIN_BYTES; private set
    @Volatile var bitrate: Long = 0; private set
    @Volatile var height: Int = 0; private set
    private var isLoading = false

    init { if (!preview) current = this }

    private fun estimate(h: Int): Long = when {
        h >= 2000 -> 25_000_000L
        h >= 1400 -> 14_000_000L
        h >= 1000 -> 8_000_000L
        h >= 700 -> 5_000_000L
        h > 0 -> 2_500_000L
        else -> 6_000_000L
    }

    override fun onTracksSelected(
        timeline: Timeline, mediaPeriodId: MediaPeriodId, renderers: Array<out Renderer>,
        trackGroups: TrackGroupArray, trackSelections: Array<out ExoTrackSelection?>
    ) {
        var bps = 0L
        var h = 0
        for (i in renderers.indices) {
            val sel = trackSelections.getOrNull(i) ?: continue
            // Flux adaptatif (HLS) : on prévoit la qualité la plus haute que la sélection peut atteindre.
            var best = 0L
            var bestH = 0
            for (k in 0 until sel.length()) {
                val f = sel.getFormat(k)
                val br = max(max(f.bitrate, f.peakBitrate), f.averageBitrate).toLong()
                best = max(best, br)
                bestH = max(bestH, f.height)
            }
            when (renderers[i].trackType) {
                C.TRACK_TYPE_VIDEO -> { h = max(h, bestH); bps += if (best > 0) best else estimate(bestH) }
                C.TRACK_TYPE_AUDIO -> bps += if (best > 0) best else 256_000L
                C.TRACK_TYPE_TEXT -> bps += 50_000L
            }
        }
        if (bps <= 0) bps = 6_000_000L
        bitrate = bps
        height = h
        // Durée maximale du réglage × débit, + 25 % de marge pour les variations du débit.
        val wanted = (bps / 8.0 * (b.max / 1000.0) * 1.25).toLong()
        target = wanted.coerceIn(MIN_BYTES.toLong(), ceiling.toLong()).toInt()
        effective = target
        allocator.setTargetBufferSize(target)
    }

    /** Cible réduite si la mémoire manque : un quart de la mémoire de l'application reste libre. */
    private fun effectiveTarget(): Int {
        val allocated = allocator.totalBytesAllocated.toLong()
        val free = rt.maxMemory() - (rt.totalMemory() - rt.freeMemory())
        val headroom = free - rt.maxMemory() / 4
        val e = min(target.toLong(), allocated + max(0L, headroom)).coerceAtLeast(MIN_FLOOR.toLong()).toInt()
        if (e + 4 * MB < effective) allocator.setTargetBufferSize(e)   // rend la mémoire inutilisée
        effective = e
        return e
    }

    override fun shouldContinueLoading(playbackPositionUs: Long, bufferedDurationUs: Long, playbackSpeed: Float): Boolean {
        val reached = allocator.totalBytesAllocated >= effectiveTarget()
        var minBuffer = minUs
        if (playbackSpeed > 1) minBuffer = min(Util.getMediaDurationForPlayoutDuration(minBuffer, playbackSpeed), maxUs)
        minBuffer = max(minBuffer, 500_000)
        if (bufferedDurationUs < minBuffer) {
            // Sous le minimum : on continue tant que la mémoire le permet (jamais bloqué sous 0,5 s).
            isLoading = !reached || bufferedDurationUs < 500_000
        } else if (bufferedDurationUs >= maxUs || reached) {
            isLoading = false
        }
        return isLoading
    }

    override fun shouldStartPlayback(
        timeline: Timeline, mediaPeriodId: MediaPeriodId, bufferedDurationUs: Long, playbackSpeed: Float,
        rebuffering: Boolean, targetLiveOffsetUs: Long
    ): Boolean {
        val buffered = Util.getPlayoutDurationForMediaDuration(bufferedDurationUs, playbackSpeed)
        var minBuffer = if (rebuffering) rebufferUs else startUs
        if (targetLiveOffsetUs != C.TIME_UNSET) minBuffer = min(targetLiveOffsetUs / 2, minBuffer)
        return minBuffer <= 0 || buffered >= minBuffer || allocator.totalBytesAllocated >= effective
    }

    override fun onPrepared() = reset(false)
    override fun onStopped() = reset(true)
    override fun onReleased() {
        reset(true)
        if (current === this) current = null
    }

    private fun reset(resetAllocator: Boolean) {
        target = MIN_BYTES
        effective = MIN_BYTES
        isLoading = false
        if (resetAllocator) allocator.reset()
    }

    override fun getAllocator(): Allocator = allocator
    override fun getBackBufferDurationUs(): Long = backUs
    override fun retainBackBufferFromKeyframe(): Boolean = true

    /** État lisible pour les statistiques du lecteur. */
    fun describe(): String {
        val q = when {
            height >= 2000 -> "4K"; height >= 1400 -> "1440p"; height >= 1000 -> "1080p"; height >= 700 -> "720p"
            height > 0 -> "${height}p"; else -> "?"
        }
        return "${allocator.totalBytesAllocated / MB} Mo utilisés / cible ${effective / MB} Mo " +
            "(flux $q ${"%.1f".format(bitrate / 1_000_000.0)} Mb/s, plafond ${ceiling / MB} Mo)"
    }

    companion object {
        private const val MB = 1024 * 1024
        private const val MIN_BYTES = 16 * MB
        private const val MIN_FLOOR = 8 * MB
        /** Lecteur plein écran en cours (pour l'affichage des statistiques). */
        @Volatile var current: AdaptiveLoadControl? = null
    }
}
