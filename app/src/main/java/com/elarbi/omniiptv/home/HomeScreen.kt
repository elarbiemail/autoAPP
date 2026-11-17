package com.elarbi.omniiptv.home

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.BaseColumns
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import androidx.tvprovider.media.tv.ChannelLogoUtils
import androidx.tvprovider.media.tv.PreviewProgram
import androidx.tvprovider.media.tv.TvContractCompat
import androidx.tvprovider.media.tv.WatchNextProgram
import com.elarbi.omniiptv.R
import com.elarbi.omniiptv.data.Edits
import com.elarbi.omniiptv.data.Library
import com.elarbi.omniiptv.data.Prefs
import com.elarbi.omniiptv.data.Repo
import com.elarbi.omniiptv.model.Channel
import com.elarbi.omniiptv.ui.Ui
import androidx.tvprovider.media.tv.Channel as TvChannel

/**
 * Rangées sur l'écran d'accueil Android TV / Google TV :
 *  - « Mes chaînes » : favoris (ou récents) avec le programme en cours ;
 *  - « En ce moment » : programmes en cours sur les chaînes suivies ;
 *  - « Continuer à regarder » (Watch Next) : dernière chaîne regardée.
 */
object HomeScreen {
    const val SLOT_FAV = "fav"
    const val SLOT_LIVE = "live"
    const val NAME_FAV = "Omni IPTV"
    const val NAME_LIVE = "Omni IPTV · En ce moment"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("home", Context.MODE_PRIVATE)

    private fun deepLink(ch: Channel): Uri = Uri.parse("omniiptv://play?key=" + Uri.encode(ch.key))

    private fun poster(ctx: Context, ch: Channel): Uri =
        if (ch.logo.startsWith("http")) Uri.parse(ch.logo)
        else Uri.parse("android.resource://${ctx.packageName}/${R.drawable.poster}")

    private fun channelExists(ctx: Context, id: Long): Boolean = try {
        ctx.contentResolver.query(TvContractCompat.buildChannelUri(id), arrayOf(BaseColumns._ID), null, null, null)
            ?.use { it.count > 0 } ?: false
    } catch (e: Exception) {
        false
    }

    /** Crée (si besoin) une rangée d'accueil et renvoie son identifiant. */
    fun ensureChannel(ctx: Context, slot: String, name: String, makeDefault: Boolean): Long? {
        return try {
            val pr = prefs(ctx)
            val saved = pr.getLong(slot, -1L)
            if (saved > 0 && channelExists(ctx, saved)) return saved
            val ch = TvChannel.Builder()
                .setType(TvContractCompat.Channels.TYPE_PREVIEW)
                .setDisplayName(name)
                .setAppLinkIntentUri(Uri.parse("omniiptv://home"))
                .setInternalProviderId(slot)
                .build()
            val uri = ctx.contentResolver.insert(TvContractCompat.Channels.CONTENT_URI, ch.toContentValues()) ?: return null
            val id = ContentUris.parseId(uri)
            try {
                val logo = ContextCompat.getDrawable(ctx, R.mipmap.ic_launcher)
                if (logo != null) ChannelLogoUtils.storeChannelLogo(ctx, id, logo.toBitmap(192, 192))
            } catch (_: Exception) {
            }
            if (makeDefault) try { TvContractCompat.requestChannelBrowsable(ctx, id) } catch (_: Exception) {}
            pr.edit().putLong(slot, id).apply()
            id
        } catch (e: Exception) {
            null
        }
    }

    /** Contenu de la rangée principale : reprise, favoris, « À regarder », sinon chaînes de la source. */
    private fun mainItems(): List<Channel> {
        val resume = Library.resume.values.toList()
        val fav = Repo.favorites.values.map { Edits.apply(it) }
        val watch = Library.watchlist.values.toList()
        var all = (resume.take(10) + fav + watch).distinctBy { it.key }
        if (all.size < 6) {
            val more = Repo.recents.map { Edits.apply(it) } +
                com.elarbi.omniiptv.data.Shown.channels().filter { c -> !c.isVod }.take(30)
            all = (all + more).distinctBy { it.key }
        }
        return all.filter { !Prefs.isLocked(it) && !Prefs.isHiddenAdult(it) }.take(40)
    }

    private fun program(ctx: Context, channelId: Long, ch: Channel, weight: Int): PreviewProgram {
        val b = PreviewProgram.Builder()
            .setChannelId(channelId)
            .setIntentUri(deepLink(ch))
            .setInternalProviderId(ch.key)
            .setWeight(weight)
        if (ch.isVod) {
            val p = Library.progress(ch.key)
            b.setType(if (ch.url.startsWith("xtseries:") || ch.key.contains("|E")) TvContractCompat.PreviewPrograms.TYPE_TV_EPISODE else TvContractCompat.PreviewPrograms.TYPE_MOVIE)
                .setTitle(ch.name)
                .setDescription((if (p != null) "Reprendre à $p % · " else "") + ch.group)
                .setPosterArtUri(poster(ctx, ch))
                .setPosterArtAspectRatio(TvContractCompat.PreviewPrograms.ASPECT_RATIO_2_3)
        } else {
            val (now, next) = Repo.nowNext(ch)
            val art = try { Posters.make(ctx, ch, now, next) } catch (e: Exception) { poster(ctx, ch) }
            b.setType(TvContractCompat.PreviewPrograms.TYPE_CHANNEL)
                .setTitle(now?.title?.ifBlank { null } ?: ch.name)
                .setDescription(listOfNotNull(ch.name, now?.let { "${Ui.hm(it.start)}–${Ui.hm(it.end)}" }, next?.let { "puis ${it.title}" }).joinToString(" · "))
                .setPosterArtUri(art)
                .setPosterArtAspectRatio(TvContractCompat.PreviewPrograms.ASPECT_RATIO_16_9)
                .setLive(true)
        }
        return b.build()
    }

    fun sync(ctx: Context) {
        val cr = ctx.contentResolver
        val favId = ensureChannel(ctx, SLOT_FAV, NAME_FAV, true) ?: return
        try {
            Posters.cleanup(ctx)
            cr.delete(TvContractCompat.buildPreviewProgramsUriForChannel(favId), null, null)
            val items = mainItems()
            items.forEachIndexed { i, ch ->
                try { cr.insert(TvContractCompat.PreviewPrograms.CONTENT_URI, program(ctx, favId, ch, 1000 - i).toContentValues()) } catch (_: Exception) {}
            }
        } catch (_: Exception) {
        }

        // Rangée « En ce moment » : seulement si elle existe déjà (l'utilisateur l'a ajoutée).
        val liveId = prefs(ctx).getLong(SLOT_LIVE, -1L)
        if (liveId <= 0 || !channelExists(ctx, liveId)) return
        try {
            cr.delete(TvContractCompat.buildPreviewProgramsUriForChannel(liveId), null, null)
            val pool = (Repo.favorites.values.map { Edits.apply(it) } + Repo.recents.map { Edits.apply(it) } +
                com.elarbi.omniiptv.data.Shown.channels().take(400))
                .filter { !it.isVod && !Prefs.isLocked(it) }.distinctBy { it.key }
            pool.filter { Repo.nowNext(it).first != null }.take(40).forEachIndexed { i, ch ->
                try { cr.insert(TvContractCompat.PreviewPrograms.CONTENT_URI, program(ctx, liveId, ch, 1000 - i).toContentValues()) } catch (_: Exception) {}
            }
        } catch (_: Exception) {
        }
    }

    /** Ajoute la chaîne (ou le film / l'épisode avec sa progression) à « Continuer à regarder ». */
    fun addWatchNext(ctx: Context, ch: Channel, pos: Long = 0L, dur: Long = 0L) {
        if (ch.isVod && dur <= 0L) return
        try {
            removeWatchNext(ctx, ch.key)
            val b = WatchNextProgram.Builder()
                .setWatchNextType(TvContractCompat.WatchNextPrograms.WATCH_NEXT_TYPE_CONTINUE)
                .setLastEngagementTimeUtcMillis(System.currentTimeMillis())
                .setTitle(ch.name)
                .setPosterArtUri(poster(ctx, ch))
                .setPosterArtAspectRatio(TvContractCompat.WatchNextPrograms.ASPECT_RATIO_16_9)
                .setIntentUri(deepLink(ch))
                .setInternalProviderId(ch.key)
            if (ch.isVod) {
                val episode = ch.key.contains("|E")
                b.setType(if (episode) TvContractCompat.WatchNextPrograms.TYPE_TV_EPISODE else TvContractCompat.WatchNextPrograms.TYPE_MOVIE)
                    .setTitle(if (episode) "${ch.group} — ${ch.name}" else ch.name)
                    .setPosterArtAspectRatio(TvContractCompat.WatchNextPrograms.ASPECT_RATIO_2_3)
                    .setDescription(if (episode) "Épisode en cours" else ch.group)
                    .setLastPlaybackPositionMillis(pos.toInt())
                    .setDurationMillis(dur.toInt())
            } else {
                b.setType(TvContractCompat.WatchNextPrograms.TYPE_CHANNEL)
                    .setDescription(Repo.nowNext(ch).first?.title ?: ch.group)
                    .setLive(true)
            }
            ctx.contentResolver.insert(TvContractCompat.WatchNextPrograms.CONTENT_URI, b.build().toContentValues())
        } catch (_: Exception) {
        }
    }

    fun removeWatchNext(ctx: Context, key: String) {
        try {
            val cr = ctx.contentResolver
            cr.query(
                TvContractCompat.WatchNextPrograms.CONTENT_URI,
                arrayOf(BaseColumns._ID, "internal_provider_id"), null, null, null
            )?.use { c ->
                while (c.moveToNext()) {
                    if (c.getString(1) == key) cr.delete(TvContractCompat.buildWatchNextProgramUri(c.getLong(0)), null, null)
                }
            }
        } catch (_: Exception) {
        }
    }
}
