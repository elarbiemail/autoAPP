package com.elarbi.omniiptv.player;

import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.Timeline;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.common.util.Util;
import androidx.media3.exoplayer.LoadControl;
import androidx.media3.exoplayer.Renderer;
import androidx.media3.exoplayer.source.MediaSource.MediaPeriodId;
import androidx.media3.exoplayer.source.TrackGroupArray;
import androidx.media3.exoplayer.trackselection.ExoTrackSelection;
import androidx.media3.exoplayer.upstream.Allocator;
import androidx.media3.exoplayer.upstream.DefaultAllocator;
import java.util.Locale;

/**
 * Mémoire tampon adaptative :
 *  - taille calculée d'après le débit et la résolution du flux (SD ≈ 15 Mo, Full HD ≈ 50 Mo, 4K ≈ 150 Mo) ;
 *  - plafonnée selon la mémoire de l'appareil ;
 *  - réduite à chaud si la mémoire manque (un quart de la mémoire de l'application reste toujours libre),
 *    et la mémoire inutilisée est rendue aussitôt.
 * Les durées (démarrage, minimum, maximum) viennent du réglage choisi (Réglages > Lecture).
 */
@UnstableApi
public final class AdaptiveLoadControl implements LoadControl {
    private static final int MB = 1024 * 1024;
    private static final int MIN_BYTES = 16 * MB;
    private static final int MIN_FLOOR = 8 * MB;

    /** Lecteur plein écran en cours (pour l'affichage des statistiques). */
    @Nullable public static volatile AdaptiveLoadControl current;

    private final DefaultAllocator allocator = new DefaultAllocator(true, C.DEFAULT_BUFFER_SEGMENT_SIZE);
    private final Runtime rt = Runtime.getRuntime();
    private final long minUs, maxUs, startUs, rebufferUs, backUs;
    private final int maxMs;
    public final int ceiling;
    private volatile int target = MIN_BYTES;
    private volatile int effective = MIN_BYTES;
    private volatile long bitrate;
    private volatile int height;
    private boolean isLoading;

    public AdaptiveLoadControl(PlayerFactory.Buffer b, boolean lowRam, boolean preview) {
        minUs = b.getMin() * 1000L;
        maxUs = b.getMax() * 1000L;
        maxMs = b.getMax();
        startUs = b.getStart() * 1000L;
        rebufferUs = b.getRebuffer() * 1000L;
        backUs = Math.min(b.getBack(), 10_000) * 1000L;
        if (preview) {
            ceiling = 12 * MB;
        } else {
            long c = (long) (rt.maxMemory() * (lowRam ? 0.25 : 0.40));
            long hi = (lowRam ? 64L : 192L) * MB;
            ceiling = (int) Math.max(16L * MB, Math.min(hi, c));
            current = this;
        }
    }

    private static long estimate(int h) {
        if (h >= 2000) return 25_000_000L;
        if (h >= 1400) return 14_000_000L;
        if (h >= 1000) return 8_000_000L;
        if (h >= 700) return 5_000_000L;
        if (h > 0) return 2_500_000L;
        return 6_000_000L;
    }

    @Override
    public void onTracksSelected(Timeline timeline, MediaPeriodId mediaPeriodId, Renderer[] renderers,
                                 TrackGroupArray trackGroups, ExoTrackSelection[] trackSelections) {
        long bps = 0;
        int h = 0;
        for (int i = 0; i < renderers.length && i < trackSelections.length; i++) {
            ExoTrackSelection sel = trackSelections[i];
            if (sel == null) continue;
            // Flux adaptatif (HLS) : on prévoit la qualité la plus haute que la sélection peut atteindre.
            long best = 0;
            int bestH = 0;
            for (int k = 0; k < sel.length(); k++) {
                Format f = sel.getFormat(k);
                best = Math.max(best, Math.max(Math.max(f.bitrate, f.peakBitrate), f.averageBitrate));
                bestH = Math.max(bestH, f.height);
            }
            int type = renderers[i].getTrackType();
            if (type == C.TRACK_TYPE_VIDEO) { h = Math.max(h, bestH); bps += best > 0 ? best : estimate(bestH); }
            else if (type == C.TRACK_TYPE_AUDIO) bps += best > 0 ? best : 256_000L;
            else if (type == C.TRACK_TYPE_TEXT) bps += 50_000L;
        }
        if (bps <= 0) bps = 6_000_000L;
        bitrate = bps;
        height = h;
        // Durée maximale du réglage × débit, + 25 % de marge pour les variations du débit.
        long wanted = (long) (bps / 8.0 * (maxMs / 1000.0) * 1.25);
        target = (int) Math.max(MIN_BYTES, Math.min(ceiling, wanted));
        effective = target;
        allocator.setTargetBufferSize(target);
    }

    /** Cible réduite si la mémoire manque : un quart de la mémoire de l'application reste libre. */
    private int effectiveTarget() {
        long allocated = allocator.getTotalBytesAllocated();
        long free = rt.maxMemory() - (rt.totalMemory() - rt.freeMemory());
        long headroom = free - rt.maxMemory() / 4;
        int e = (int) Math.max(MIN_FLOOR, Math.min(target, allocated + Math.max(0L, headroom)));
        if (e + 4 * MB < effective) allocator.setTargetBufferSize(e); // rend la mémoire inutilisée
        effective = e;
        return e;
    }

    @Override
    public boolean shouldContinueLoading(long playbackPositionUs, long bufferedDurationUs, float playbackSpeed) {
        boolean reached = allocator.getTotalBytesAllocated() >= effectiveTarget();
        long minBuffer = minUs;
        if (playbackSpeed > 1) minBuffer = Math.min(Util.getMediaDurationForPlayoutDuration(minBuffer, playbackSpeed), maxUs);
        minBuffer = Math.max(minBuffer, 500_000);
        if (bufferedDurationUs < minBuffer) {
            // Sous le minimum : on continue tant que la mémoire le permet (jamais bloqué sous 0,5 s).
            isLoading = !reached || bufferedDurationUs < 500_000;
        } else if (bufferedDurationUs >= maxUs || reached) {
            isLoading = false;
        }
        return isLoading;
    }

    @Override
    public boolean shouldStartPlayback(Timeline timeline, MediaPeriodId mediaPeriodId, long bufferedDurationUs,
                                       float playbackSpeed, boolean rebuffering, long targetLiveOffsetUs) {
        long buffered = Util.getPlayoutDurationForMediaDuration(bufferedDurationUs, playbackSpeed);
        long minBuffer = rebuffering ? rebufferUs : startUs;
        if (targetLiveOffsetUs != C.TIME_UNSET) minBuffer = Math.min(targetLiveOffsetUs / 2, minBuffer);
        return minBuffer <= 0 || buffered >= minBuffer || allocator.getTotalBytesAllocated() >= effective;
    }

    @Override public void onPrepared() { reset(false); }
    @Override public void onStopped() { reset(true); }
    @Override public void onReleased() { reset(true); if (current == this) current = null; }

    private void reset(boolean resetAllocator) {
        target = MIN_BYTES;
        effective = MIN_BYTES;
        isLoading = false;
        if (resetAllocator) allocator.reset();
    }

    @Override public Allocator getAllocator() { return allocator; }
    @Override public long getBackBufferDurationUs() { return backUs; }
    @Override public boolean retainBackBufferFromKeyframe() { return true; }

    /** État lisible pour les statistiques du lecteur. */
    public String describe() {
        String q = height >= 2000 ? "4K" : height >= 1400 ? "1440p" : height >= 1000 ? "1080p"
            : height >= 700 ? "720p" : height > 0 ? height + "p" : "?";
        return (allocator.getTotalBytesAllocated() / MB) + " Mo utilisés / cible " + (effective / MB) + " Mo (flux " + q + " "
            + String.format(Locale.FRANCE, "%.1f", bitrate / 1_000_000.0) + " Mb/s, plafond " + (ceiling / MB) + " Mo)";
    }
}
