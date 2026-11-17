package com.elarbi.omniiptv.tif

import android.content.Context
import android.media.tv.TvContract
import android.media.tv.TvInputManager
import android.media.tv.TvInputService
import android.net.Uri
import android.view.Surface
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import com.elarbi.omniiptv.data.Repo
import com.elarbi.omniiptv.data.StreamResolver
import com.elarbi.omniiptv.data.YouTube
import com.elarbi.omniiptv.player.PlayerFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Source TV système : lit les chaînes Omni IPTV depuis l'onglet Direct / Chaînes en direct. */
class OmniTvInputService : TvInputService() {
    override fun onCreateSession(inputId: String): Session = OmniSession(this)

    @androidx.annotation.OptIn(markerClass = [UnstableApi::class])
    private class OmniSession(private val ctx: Context) : Session(ctx) {
        private val player = PlayerFactory.create(ctx)
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        private var job: Job? = null

        init {
            player.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(state: Int) {
                    when (state) {
                        Player.STATE_READY -> notifyVideoAvailable()
                        Player.STATE_BUFFERING -> notifyVideoUnavailable(TvInputManager.VIDEO_UNAVAILABLE_REASON_BUFFERING)
                        else -> {}
                    }
                }

                override fun onPlayerError(error: PlaybackException) {
                    notifyVideoUnavailable(TvInputManager.VIDEO_UNAVAILABLE_REASON_UNKNOWN)
                }
            })
        }

        override fun onRelease() {
            scope.cancel()
            player.release()
        }

        override fun onSetSurface(surface: Surface?): Boolean {
            player.setVideoSurface(surface)
            return true
        }

        override fun onSetStreamVolume(volume: Float) {
            player.volume = volume
        }

        override fun onSetCaptionEnabled(enabled: Boolean) {}

        override fun onTune(channelUri: Uri): Boolean {
            notifyVideoUnavailable(TvInputManager.VIDEO_UNAVAILABLE_REASON_TUNING)
            job?.cancel()
            job = scope.launch {
                try {
                    val key = withContext(Dispatchers.IO) {
                        ctx.contentResolver.query(
                            channelUri, arrayOf(TvContract.Channels.COLUMN_INTERNAL_PROVIDER_ID), null, null, null
                        )?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
                    } ?: return@launch
                    val ch = withContext(Dispatchers.IO) { Repo.findChannel(key) } ?: return@launch
                    val url = StreamResolver.resolve(ch)
                    PlayerFactory.play(ctx, player, url, StreamResolver.userAgent(ch), ch.referer, forceHls = YouTube.isYouTube(ch.url), headers = StreamResolver.headers(ch))
                } catch (e: Exception) {
                    notifyVideoUnavailable(TvInputManager.VIDEO_UNAVAILABLE_REASON_UNKNOWN)
                }
            }
            return true
        }
    }
}
