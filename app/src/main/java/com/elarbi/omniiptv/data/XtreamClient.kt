package com.elarbi.omniiptv.data

import android.util.Base64
import com.elarbi.omniiptv.model.Channel
import com.elarbi.omniiptv.model.Programme
import com.elarbi.omniiptv.model.Source
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder

/** Client de l'API Xtream Codes (player_api.php). */
class XtreamClient(private val s: Source) {
    private val base = s.url.trim().trimEnd('/')
        .removeSuffix("/player_api.php").removeSuffix("/get.php").trimEnd('/')
    private val u = URLEncoder.encode(s.username, "UTF-8")
    private val p = URLEncoder.encode(s.password, "UTF-8")

    private fun api(action: String?) =
        "$base/player_api.php?username=$u&password=$p" + (action?.let { "&action=$it" } ?: "")

    private fun headers(): Map<String, String> =
        if (s.userAgent.isBlank()) emptyMap() else mapOf("User-Agent" to s.userAgent)

    fun epgUrl() = "$base/xmltv.php?username=$u&password=$p"

    /** Adresse de replay (catch-up) d'un programme passé. */
    fun timeshiftUrl(streamId: String, start: Long, end: Long): String {
        val f = java.text.SimpleDateFormat("yyyy-MM-dd:HH-mm", java.util.Locale.US)
        val minutes = ((end - start) / 60_000L).coerceAtLeast(1)
        return "$base/timeshift/${s.username}/${s.password}/$minutes/${f.format(java.util.Date(start))}/$streamId.ts"
    }

    fun channels(withVod: Boolean = true): List<Channel> {
        Loading.step("Connexion au serveur Xtream…")
        val auth = JSONObject(Http.get(api(null), headers()))
        val ui = auth.optJSONObject("user_info")
        if (ui == null || ui.str("auth") == "0") throw IOException("Identifiants Xtream refusés")
        if (ui.str("status").equals("Expired", true)) throw IOException("Abonnement expiré")
        var ext = "ts"
        ui.optJSONArray("allowed_output_formats")?.let { f ->
            val list = (0 until f.length()).map { f.optString(it) }
            if ("ts" !in list && "m3u8" in list) ext = "m3u8"
        }

        val out = ArrayList<Channel>()
        Loading.step("Chaînes en direct…", 1, 3)
        val liveCats = categories("get_live_categories")
        streamList(api("get_live_streams"), "Chaînes en direct (1/3)",
            setOf("stream_id", "name", "stream_icon", "category_id", "epg_channel_id", "tv_archive", "tv_archive_duration")) { o ->
            val id = o["stream_id"].orEmpty()
            out.add(
                Channel(
                    key = "${s.id}|L$id", sourceId = s.id, name = o["name"].orEmpty(),
                    url = "$base/live/${s.username}/${s.password}/$id.$ext",
                    logo = o["stream_icon"].orEmpty(), group = liveCats[o["category_id"].orEmpty()] ?: "Divers",
                    epgId = o["epg_channel_id"].orEmpty(), userAgent = s.userAgent,
                    archive = if (o["tv_archive"] == "1") (o["tv_archive_duration"]?.toIntOrNull() ?: 1) else 0
                )
            )
            if (out.size % 500 == 0) Loading.items(out.size)
        }
        Loading.items(out.size)
        if (!withVod) return out
        try {
            Loading.step("Films…", 2, 3)
            val vodCats = categories("get_vod_categories")
            streamList(api("get_vod_streams"), "Films (2/3)", setOf("stream_id", "name", "stream_icon", "category_id", "container_extension")) { o ->
                val id = o["stream_id"].orEmpty()
                val e = o["container_extension"].orEmpty().ifBlank { "mp4" }
                out.add(
                    Channel(
                        key = "${s.id}|V$id", sourceId = s.id, name = o["name"].orEmpty(),
                        url = "$base/movie/${s.username}/${s.password}/$id.$e",
                        logo = o["stream_icon"].orEmpty(), group = "Films · " + (vodCats[o["category_id"].orEmpty()] ?: "Divers"),
                        userAgent = s.userAgent, isVod = true
                    )
                )
                if (out.size % 500 == 0) Loading.items(out.size)
            }
        } catch (_: Exception) {
        }
        Loading.step("Séries…", 3, 3)
        try { out.addAll(series()) } catch (_: Exception) {}
        Loading.items(out.size)
        return out
    }

    /** Séries : une entrée par série (url « xtseries:id »), les épisodes sont chargés à l'ouverture. */
    fun series(): List<Channel> {
        val cats = categories("get_series_categories")
        val out = ArrayList<Channel>()
        streamList(api("get_series"), "Séries (3/3)", setOf("series_id", "name", "cover", "category_id")) { o ->
            val id = o["series_id"].orEmpty()
            out.add(
                Channel(
                    key = "${s.id}|S$id", sourceId = s.id, name = o["name"].orEmpty(), url = "xtseries:$id",
                    logo = o["cover"].orEmpty(), group = "Séries · " + (cats[o["category_id"].orEmpty()] ?: "Divers"),
                    userAgent = s.userAgent, isVod = true
                )
            )
        }
        return out
    }

    /** Lit une grande liste JSON en flux (sans la charger entièrement en mémoire). */
    private fun streamList(url: String, label: String, keep: Set<String>, f: (Map<String, String>) -> Unit) {
        Http.openTracked(url, label, headers()).use { input ->
            val r = JsonStream.reader(input)
            JsonStream.forEachObject(r) { f(JsonStream.flat(it, keep)) }
        }
    }

    fun episodes(seriesId: String, seriesName: String): List<Channel> {
        val o = JSONObject(Http.get(api("get_series_info") + "&series_id=$seriesId", headers()))
        val eps = o.optJSONObject("episodes") ?: return emptyList()
        val cover = o.optJSONObject("info")?.str("cover").orEmpty()
        val out = ArrayList<Channel>()
        val seasons = ArrayList<String>()
        val keys = eps.keys()
        while (keys.hasNext()) seasons.add(keys.next())
        seasons.sortBy { it.toIntOrNull() ?: 0 }
        for (season in seasons) {
            val arr = eps.optJSONArray(season) ?: continue
            for (i in 0 until arr.length()) {
                val e = arr.getJSONObject(i)
                val id = e.str("id")
                val ext = e.str("container_extension").ifBlank { "mp4" }
                val img = e.optJSONObject("info")?.str("movie_image").orEmpty().ifBlank { cover }
                val num = e.str("episode_num")
                val title = e.str("title")
                out.add(
                    Channel(
                        key = "${s.id}|E$id", sourceId = s.id,
                        name = "S$season E$num · $title",
                        url = "$base/series/${s.username}/${s.password}/$id.$ext",
                        logo = img, group = seriesName, userAgent = s.userAgent, isVod = true
                    )
                )
            }
        }
        return out
    }

    /** Guide court fourni par le serveur Xtream (titres encodés en Base64). */
    fun shortEpg(streamId: String): List<Programme> {
        val o = JSONObject(Http.get(api("get_short_epg") + "&stream_id=$streamId&limit=8", headers()))
        val a = o.optJSONArray("epg_listings") ?: return emptyList()
        fun dec(v: String) = try { String(Base64.decode(v, Base64.DEFAULT), Charsets.UTF_8) } catch (e: Exception) { v }
        return (0 until a.length()).map { i ->
            val e = a.getJSONObject(i)
            Programme(
                e.str("start_timestamp").toLongOrNull()?.times(1000) ?: 0L,
                e.str("stop_timestamp").toLongOrNull()?.times(1000) ?: 0L,
                dec(e.str("title")), dec(e.str("description"))
            )
        }.filter { it.end > 0 }.sortedBy { it.start }
    }

    private fun categories(action: String): Map<String, String> = try {
        val a = JSONArray(Http.get(api(action), headers()))
        (0 until a.length()).associate {
            val o = a.getJSONObject(it)
            o.str("category_id") to o.str("category_name")
        }
    } catch (e: Exception) {
        emptyMap()
    }
}
