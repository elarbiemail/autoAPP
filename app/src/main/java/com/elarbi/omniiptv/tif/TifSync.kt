package com.elarbi.omniiptv.tif

import android.content.ComponentName
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.media.tv.TvContract
import android.provider.BaseColumns
import com.elarbi.omniiptv.data.Http
import com.elarbi.omniiptv.data.Repo
import com.elarbi.omniiptv.model.Channel
import com.elarbi.omniiptv.model.Programme

/** Publie chaînes et programmes dans le guide système (onglet Direct de Google TV / Chaînes en direct). */
object TifSync {
    private const val P_CHANNEL_ID = "channel_id"
    private const val P_TITLE = "title"
    private const val P_DESC = "short_description"
    private const val P_START = "start_time_utc_millis"
    private const val P_END = "end_time_utc_millis"
    private const val P_POSTER = "poster_art_uri"

    fun inputId(ctx: Context): String = TvContract.buildInputId(ComponentName(ctx, OmniTvInputService::class.java))

    fun selection(): List<Channel> {
        val fav = Repo.favorites.values.filter { !it.isVod }
        if (fav.isNotEmpty()) return fav
        val s = Repo.activeSource() ?: return emptyList()
        return Repo.channels(s.id).filter { !it.isVod }.take(800)
    }

    /** Renvoie le nombre de chaînes publiées. */
    fun sync(ctx: Context): Int {
        val cr = ctx.contentResolver
        val input = inputId(ctx)
        val existing = HashMap<String, Long>()
        cr.query(
            TvContract.buildChannelsUriForInput(input),
            arrayOf(BaseColumns._ID, TvContract.Channels.COLUMN_INTERNAL_PROVIDER_ID), null, null, null
        )?.use { c ->
            while (c.moveToNext()) c.getString(1)?.let { existing[it] = c.getLong(0) }
        }
        val wanted = selection()
        val keep = HashSet<Long>()
        var logos = 0
        wanted.forEachIndexed { i, ch ->
            val cv = ContentValues().apply {
                put(TvContract.Channels.COLUMN_INPUT_ID, input)
                put(TvContract.Channels.COLUMN_DISPLAY_NUMBER, (i + 1).toString())
                put(TvContract.Channels.COLUMN_DISPLAY_NAME, ch.name)
                put(TvContract.Channels.COLUMN_TYPE, TvContract.Channels.TYPE_OTHER)
                put(TvContract.Channels.COLUMN_SERVICE_TYPE, TvContract.Channels.SERVICE_TYPE_AUDIO_VIDEO)
                put(TvContract.Channels.COLUMN_INTERNAL_PROVIDER_ID, ch.key)
                put(TvContract.Channels.COLUMN_SEARCHABLE, 1)
            }
            var rowId = existing[ch.key]
            if (rowId != null) {
                cr.update(TvContract.buildChannelUri(rowId), cv, null, null)
            } else {
                rowId = cr.insert(TvContract.Channels.CONTENT_URI, cv)?.let { ContentUris.parseId(it) }
                if (rowId != null && ch.logo.startsWith("http") && logos < 150) {
                    logos++
                    try {
                        val bytes = Http.open(ch.logo).use { it.readBytes() }
                        cr.openOutputStream(TvContract.buildChannelLogoUri(rowId))?.use { it.write(bytes) }
                    } catch (_: Exception) {
                    }
                }
            }
            if (rowId != null) {
                keep.add(rowId)
                try { writePrograms(ctx, rowId, ch) } catch (_: Exception) {}
            }
        }
        existing.values.filter { it !in keep }.forEach {
            try { cr.delete(TvContract.buildChannelUri(it), null, null) } catch (_: Exception) {}
        }
        return keep.size
    }

    private fun writePrograms(ctx: Context, rowId: Long, ch: Channel) {
        val cr = ctx.contentResolver
        cr.delete(TvContract.buildProgramsUriForChannel(rowId), null, null)
        val now = System.currentTimeMillis()
        val progs = Repo.programmes(ch).take(60).ifEmpty {
            listOf(Programme(now - 3600_000L, now + 24 * 3600_000L, ch.name, "En direct · ${ch.group}"))
        }
        val values = progs.map { p ->
            ContentValues().apply {
                put(P_CHANNEL_ID, rowId)
                put(P_TITLE, p.title.ifBlank { ch.name })
                put(P_DESC, p.desc.take(250))
                put(P_START, p.start)
                put(P_END, p.end)
                if (ch.logo.startsWith("http")) put(P_POSTER, ch.logo)
            }
        }.toTypedArray()
        cr.bulkInsert(TvContract.Programs.CONTENT_URI, values)
    }
}
