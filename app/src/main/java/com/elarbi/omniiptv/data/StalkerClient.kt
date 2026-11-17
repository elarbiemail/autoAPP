package com.elarbi.omniiptv.data

import com.elarbi.omniiptv.model.Channel
import com.elarbi.omniiptv.model.Programme
import com.elarbi.omniiptv.model.Source
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.security.MessageDigest

/** Client des portails Stalker / Ministra (boîtiers MAG, authentification par adresse MAC). */
class StalkerClient(private val s: Source) {
    companion object {
        const val MAG_UA = "Mozilla/5.0 (QtEmbedded; U; Linux; C) AppleWebKit/533.3 (KHTML, like Gecko) MAG200 stbapp ver: 2 rev: 250 Safari/533.3"
    }

    private val root: String
    private var token = ""
    private var api = ""

    init {
        var u = s.url.trim().trimEnd('/')
        if (u.endsWith("/c")) u = u.removeSuffix("/c")
        root = u
    }

    private val mac get() = s.mac.uppercase()

    private fun headers(): Map<String, String> {
        val h = mutableMapOf(
            "User-Agent" to MAG_UA,
            "X-User-Agent" to "Model: MAG250; Link: WiFi",
            "Cookie" to "mac=$mac; stb_lang=fr; timezone=Africa/Casablanca",
            "Referer" to "$root/c/",
            "Accept" to "*/*"
        )
        if (token.isNotEmpty()) h["Authorization"] = "Bearer $token"
        return h
    }

    private fun call(endpoint: String, query: String): Any? {
        val txt = Http.get("$endpoint?$query&JsHttpRequest=1-xml", headers())
        val start = txt.indexOf('{')
        if (start < 0) throw IOException("Réponse inattendue du portail")
        return JSONObject(txt.substring(start)).opt("js")
    }

    private fun sn(): String {
        val d = MessageDigest.getInstance("MD5").digest(mac.toByteArray())
        return d.joinToString("") { "%02X".format(it) }.take(13)
    }

    @Synchronized
    fun login() {
        if (token.isNotEmpty() && api.isNotEmpty()) return
        val candidates = if (root.endsWith(".php")) listOf(root) else listOf(
            "$root/portal.php", "$root/server/load.php", "$root/stalker_portal/server/load.php"
        )
        var last: Exception? = null
        for (c in candidates) {
            try {
                token = ""
                val js = call(c, "type=stb&action=handshake&token=&prehash=0") as? JSONObject ?: continue
                val t = js.str("token")
                if (t.isBlank()) continue
                token = t
                api = c
                try {
                    call(
                        c, "type=stb&action=get_profile&hd=1&num_banks=2&sn=${sn()}&stb_type=MAG250" +
                            "&image_version=218&video_out=hdmi&device_id=&device_id2=&signature=" +
                            "&auth_second_step=1&hw_version=1.7-BD-00&not_valid_token=0"
                    )
                } catch (_: Exception) {
                }
                return
            } catch (e: Exception) {
                last = e
            }
        }
        throw IOException("Portail injoignable ou adresse MAC non autorisée" + (last?.message?.let { " ($it)" } ?: ""))
    }

    fun channels(): List<Channel> {
        login()
        val genres = HashMap<String, String>()
        (call(api, "type=itv&action=get_genres") as? JSONArray)?.let { a ->
            for (i in 0 until a.length()) {
                val g = a.getJSONObject(i)
                genres[g.str("id")] = g.str("title")
            }
        }
        val arr = when (val js = call(api, "type=itv&action=get_all_channels")) {
            is JSONObject -> js.optJSONArray("data")
            is JSONArray -> js
            else -> null
        } ?: throw IOException("Aucune chaîne renvoyée par le portail")
        val out = ArrayList<Channel>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val id = o.str("id")
            val logo = o.str("logo").let { if (it.startsWith("http")) it else "" }
            out.add(
                Channel(
                    key = "${s.id}|stk$id", sourceId = s.id, name = o.str("name"),
                    url = "stalker:" + o.str("cmd"), logo = logo,
                    group = genres[o.str("tv_genre_id")]?.ifBlank { null } ?: "Chaînes",
                    epgId = "stk:$id", userAgent = MAG_UA
                )
            )
        }
        return out
    }

    /** Demande au portail un lien de lecture temporaire. */
    fun createLink(cmd: String): String {
        login()
        val direct = cmd.substringAfter(' ', cmd).trim()
        return try {
            val js = call(
                api, "type=itv&action=create_link&cmd=${URLEncoder.encode(cmd, "UTF-8")}" +
                    "&series=&forced_storage=undefined&disable_ad=0&download=0"
            ) as? JSONObject
            val c = js?.str("cmd").orEmpty()
            if (c.isBlank()) direct else c.substringAfter(' ', c).trim()
        } catch (e: Exception) {
            token = ""
            direct
        }
    }

    fun epg(): Map<String, List<Programme>> {
        login()
        val js = call(api, "type=itv&action=get_epg_info&period=24") as? JSONObject ?: return emptyMap()
        val data = js.optJSONObject("data") ?: return emptyMap()
        val out = HashMap<String, List<Programme>>()
        val keys = data.keys()
        while (keys.hasNext()) {
            val id = keys.next()
            val a = data.optJSONArray(id) ?: continue
            val list = ArrayList<Programme>()
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                list.add(
                    Programme(
                        o.optLong("start_timestamp") * 1000, o.optLong("stop_timestamp") * 1000,
                        o.str("name"), o.str("descr")
                    )
                )
            }
            out["stk:$id"] = list.sortedBy { it.start }
        }
        return out
    }
}
