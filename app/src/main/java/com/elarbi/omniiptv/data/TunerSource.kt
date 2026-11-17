package com.elarbi.omniiptv.data

import android.content.Context
import android.media.tv.TvContract
import android.media.tv.TvInputManager
import android.provider.BaseColumns
import com.elarbi.omniiptv.model.Channel
import com.elarbi.omniiptv.model.Programme
import com.elarbi.omniiptv.model.Source
import com.elarbi.omniiptv.model.SourceType
import com.elarbi.omniiptv.tif.TifSync
import java.io.IOException

/**
 * Chaînes enregistrées sur le téléviseur (tuner TNT, satellite, câble, autres sources TV)
 * lues depuis la base TV d'Android et jouées dans l'application via TvView.
 */
object TunerSource {
    const val ID = "tuner"

    val permissions = arrayOf(
        "android.permission.READ_TV_LISTINGS",
        "com.android.providers.tv.permission.READ_EPG_DATA"
    )

    fun source() = Source(ID, SourceType.TUNER, "Chaînes du téléviseur", "tuner")

    fun ensureSource() {
        if (Repo.sources.none { it.id == ID }) Repo.upsertSource(source())
    }

    private fun groupFor(type: String?, inputLabel: String): String = when (type) {
        TvContract.Channels.TYPE_DVB_T, TvContract.Channels.TYPE_DVB_T2 -> "TNT"
        TvContract.Channels.TYPE_DVB_S, TvContract.Channels.TYPE_DVB_S2 -> "Satellite"
        TvContract.Channels.TYPE_DVB_C, TvContract.Channels.TYPE_DVB_C2 -> "Câble"
        TvContract.Channels.TYPE_ATSC_T, TvContract.Channels.TYPE_ISDB_T, TvContract.Channels.TYPE_DTMB -> "TNT"
        TvContract.Channels.TYPE_ATSC_C, TvContract.Channels.TYPE_ISDB_C -> "Câble"
        TvContract.Channels.TYPE_ISDB_S -> "Satellite"
        TvContract.Channels.TYPE_PAL, TvContract.Channels.TYPE_SECAM, TvContract.Channels.TYPE_NTSC -> "Analogique"
        else -> inputLabel.ifBlank { "Autres" }
    }

    fun channels(ctx: Context): List<Channel> {
        val own = TifSync.inputId(ctx)
        val tim = ctx.getSystemService(Context.TV_INPUT_SERVICE) as? TvInputManager
        val labels = HashMap<String, String>()
        try { tim?.tvInputList?.forEach { labels[it.id] = it.loadLabel(ctx).toString() } } catch (_: Exception) {}
        val out = ArrayList<Pair<Double, Channel>>()
        val cursor = try {
            ctx.contentResolver.query(
                TvContract.Channels.CONTENT_URI,
                arrayOf(
                    BaseColumns._ID, TvContract.Channels.COLUMN_INPUT_ID, TvContract.Channels.COLUMN_DISPLAY_NUMBER,
                    TvContract.Channels.COLUMN_DISPLAY_NAME, TvContract.Channels.COLUMN_TYPE
                ), null, null, null
            )
        } catch (e: SecurityException) {
            throw IOException("Le téléviseur refuse l'accès à sa liste de chaînes (autorisation « Chaînes et programmes TV » requise).")
        }
        cursor?.use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0)
                val input = c.getString(1) ?: continue
                if (input == own) continue
                val num = c.getString(2).orEmpty()
                val name = c.getString(3).orEmpty().ifBlank { "Chaîne $num" }
                val type = c.getString(4)
                val uri = TvContract.buildChannelUri(id).toString()
                val order = num.replace('-', '.').toDoubleOrNull() ?: (100000.0 + out.size)
                out.add(
                    order to Channel(
                        key = "$ID|$id", sourceId = ID,
                        name = if (num.isNotBlank()) "$num  $name" else name,
                        url = "tuner:$input|$uri",
                        logo = TvContract.buildChannelLogoUri(id).toString(),
                        group = groupFor(type, labels[input].orEmpty()),
                        epgId = "tuner:$id"
                    )
                )
            }
        }
        if (out.isEmpty()) throw IOException(
            "Aucune chaîne du téléviseur accessible. Lancez d'abord une recherche de chaînes dans l'application TV du téléviseur, " +
                "puis autorisez l'accès aux chaînes pour Omni IPTV."
        )
        return out.sortedBy { it.first }.map { it.second }
    }

    /** Programmes du guide intégré au téléviseur (EIT de la TNT / du satellite). */
    fun programmes(ctx: Context, chans: List<Channel>, from: Long, to: Long): Map<String, List<Programme>> {
        val res = HashMap<String, List<Programme>>()
        for (ch in chans) {
            val id = ch.key.substringAfter("$ID|").toLongOrNull() ?: continue
            try {
                ctx.contentResolver.query(
                    TvContract.buildProgramsUriForChannel(id, from, to),
                    arrayOf("title", "short_description", "start_time_utc_millis", "end_time_utc_millis"),
                    null, null, null
                )?.use { c ->
                    val l = ArrayList<Programme>()
                    while (c.moveToNext()) l.add(Programme(c.getLong(2), c.getLong(3), c.getString(0).orEmpty(), c.getString(1).orEmpty()))
                    if (l.isNotEmpty()) res[ch.epgId.lowercase()] = l.sortedBy { it.start }
                }
            } catch (_: Exception) {
            }
        }
        return res
    }

    /** Sépare « tuner:inputId|uriChaîne ». */
    fun parse(url: String): Pair<String, String>? {
        val v = url.removePrefix("tuner:")
        val i = v.indexOf('|')
        return if (i > 0) v.substring(0, i) to v.substring(i + 1) else null
    }
}
