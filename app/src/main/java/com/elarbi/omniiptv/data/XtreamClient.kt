package com.elarbi.omniiptv.data

import com.elarbi.omniiptv.model.Channel
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

    fun channels(): List<Channel> {
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
        val liveCats = categories("get_live_categories")
        val live = JSONArray(Http.get(api("get_live_streams"), headers()))
        for (i in 0 until live.length()) {
            val o = live.getJSONObject(i)
            val id = o.str("stream_id")
            out.add(
                Channel(
                    key = "${s.id}|L$id", sourceId = s.id, name = o.str("name"),
                    url = "$base/live/${s.username}/${s.password}/$id.$ext",
                    logo = o.str("stream_icon"), group = liveCats[o.str("category_id")] ?: "Divers",
                    epgId = o.str("epg_channel_id"), userAgent = s.userAgent
                )
            )
        }
        try {
            val vodCats = categories("get_vod_categories")
            val vod = JSONArray(Http.get(api("get_vod_streams"), headers()))
            for (i in 0 until vod.length()) {
                val o = vod.getJSONObject(i)
                val id = o.str("stream_id")
                val e = o.str("container_extension").ifBlank { "mp4" }
                out.add(
                    Channel(
                        key = "${s.id}|V$id", sourceId = s.id, name = o.str("name"),
                        url = "$base/movie/${s.username}/${s.password}/$id.$e",
                        logo = o.str("stream_icon"), group = "Films · " + (vodCats[o.str("category_id")] ?: "Divers"),
                        userAgent = s.userAgent, isVod = true
                    )
                )
            }
        } catch (_: Exception) {
        }
        return out
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
