package com.elarbi.omniiptv.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import org.json.JSONObject
import java.io.IOException

/**
 * Chaînes et vidéos YouTube lues DANS l'application :
 *  1) recherche de l'identifiant de la vidéo (direct en cours d'une chaîne ou lien de vidéo) ;
 *  2) demande du flux à l'API « player » de YouTube en se présentant comme différents appareils
 *     (casque VR Android, iPhone, Android) → flux HLS lu par le lecteur natif ;
 *  3) à défaut, lecteur YouTube intégré (page web) dans l'application.
 */
object YouTube {
    class NotLiveException(msg: String) : IOException(msg)

    /** Aucun flux exploitable par le lecteur natif : utiliser le lecteur YouTube intégré. */
    class EmbedRequired(val videoId: String) : IOException("Lecteur YouTube intégré requis")

    private val userAgents = HashMap<String, String>()

    fun userAgentFor(key: String): String? = synchronized(userAgents) { userAgents[key] }

    /** URL lisible pour une entrée de la liste (mémorise le user-agent associé). */
    fun resolveForChannel(key: String, raw: String): String {
        val s = resolveStream(raw)
        synchronized(userAgents) { userAgents[key] = s.userAgent }
        return s.url ?: throw EmbedRequired(s.videoId)
    }

    /** Résultat : flux HLS lisible (url + user-agent) et/ou identifiant pour le lecteur intégré. */
    data class Stream(val url: String?, val userAgent: String, val videoId: String, val isLive: Boolean)

    private const val WEB_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Safari/537.36"
    private val hlsRe = Regex("\"hlsManifestUrl\"\\s*:\\s*\"(https:[^\"]+)\"")
    private val idRe = Regex("(?:v=|youtu\\.be/|/live/|/embed/|/shorts/)([A-Za-z0-9_-]{11})")
    private val canonicalRe = Regex("<link rel=\"canonical\" href=\"https://www\\.youtube\\.com/watch\\?v=([A-Za-z0-9_-]{11})\"")
    private val liveIdRe = Regex("\"videoId\":\"([A-Za-z0-9_-]{11})\"[^{}]{0,400}?\"isLive(?:Now|Content)?\":true")

    private data class Client(
        val name: String, val number: Int, val version: String, val ua: String, val extra: Map<String, Any>
    )

    private val clients = listOf(
        Client(
            "ANDROID_VR", 28, "1.62.27",
            "com.google.android.apps.youtube.vr.oculus/1.62.27 (Linux; U; Android 12L; eureka-user Build/SQ3A.220605.009.A1) gzip",
            mapOf("deviceMake" to "Oculus", "deviceModel" to "Quest 3", "osName" to "Android", "osVersion" to "12L", "androidSdkVersion" to 32)
        ),
        Client(
            "IOS", 5, "20.10.4",
            "com.google.ios.youtube/20.10.4 (iPhone16,2; U; CPU iOS 18_3_2 like Mac OS X;)",
            mapOf("deviceMake" to "Apple", "deviceModel" to "iPhone16,2", "osName" to "iPhone", "osVersion" to "18.3.2.22D82")
        ),
        Client(
            "ANDROID", 3, "20.10.38",
            "com.google.android.youtube/20.10.38 (Linux; U; Android 14) gzip",
            mapOf("osName" to "Android", "osVersion" to "14", "androidSdkVersion" to 34)
        )
    )

    fun isYouTube(url: String): Boolean {
        val u = url.removePrefix("yt:").lowercase()
        return u.contains("youtube.com/") || u.contains("youtu.be/")
    }

    fun videoId(url: String): String? = idRe.find(url)?.groupValues?.get(1)

    /** Normalise l'URL saisie : une chaîne (@pseudo ou /channel/UC…) pointe vers son direct. */
    fun normalize(raw: String): String {
        var u = raw.trim().removePrefix("yt:")
        if (u.startsWith("@")) u = "https://www.youtube.com/$u"
        if (!u.startsWith("http")) u = "https://$u"
        val isChannel = (u.contains("/@") || u.contains("/channel/") || u.contains("/c/") || u.contains("/user/")) &&
            videoId(u) == null
        if (isChannel) {
            u = u.substringBefore('?').trimEnd('/')
            listOf("/featured", "/videos", "/streams", "/live").forEach { u = u.removeSuffix(it) }
            u += "/live"
        }
        return u
    }

    fun thumbnail(url: String): String = videoId(url)?.let { "https://i.ytimg.com/vi/$it/hqdefault.jpg" } ?: ""

    private val webHeaders = mapOf(
        "User-Agent" to WEB_UA,
        "Accept-Language" to "fr-FR,fr;q=0.9",
        "Cookie" to "CONSENT=YES+cb; SOCS=CAI"
    )

    /** Trouve la vidéo à lire : identifiant du lien, ou direct en cours de la chaîne. */
    private fun findVideo(raw: String): Pair<String, String?> {
        val url = normalize(raw)
        videoId(url)?.let { return it to null }
        val page = Http.get(url, webHeaders)
        val id = canonicalRe.find(page)?.groupValues?.get(1)
            ?: liveIdRe.find(page)?.groupValues?.get(1)
            ?: throw NotLiveException("Aucun direct en cours sur cette chaîne YouTube")
        return id to page
    }

    /** Interroge l'API player de YouTube avec un client donné. */
    private fun player(id: String, c: Client): JSONObject? = try {
        val client = JSONObject().put("clientName", c.name).put("clientVersion", c.version)
            .put("hl", "fr").put("gl", "MA")
        c.extra.forEach { (k, v) -> client.put(k, v) }
        val body = JSONObject()
            .put("context", JSONObject().put("client", client))
            .put("videoId", id)
            .put("contentCheckOk", true)
            .put("racyCheckOk", true)
        val txt = Http.postJson(
            "https://www.youtube.com/youtubei/v1/player?prettyPrint=false", body.toString(),
            mapOf(
                "User-Agent" to c.ua,
                "X-YouTube-Client-Name" to c.number.toString(),
                "X-YouTube-Client-Version" to c.version,
                "Origin" to "https://www.youtube.com"
            )
        )
        JSONObject(txt)
    } catch (e: Exception) {
        null
    }

    /** Renvoie le flux à lire dans l'application. */
    fun resolveStream(raw: String): Stream {
        val (id, page) = findVideo(raw)
        var live = false
        for (c in clients) {
            val o = player(id, c) ?: continue
            if (o.optJSONObject("playabilityStatus")?.optString("status") != "OK") continue
            live = o.optJSONObject("videoDetails")?.optBoolean("isLive") == true || live
            val hls = o.optJSONObject("streamingData")?.optString("hlsManifestUrl").orEmpty()
            if (hls.startsWith("http")) return Stream(hls, c.ua, id, live)
        }
        // Dernier essai : le manifeste présent dans la page web.
        val html = page ?: try { Http.get("https://www.youtube.com/watch?v=$id", webHeaders) } catch (e: Exception) { "" }
        hlsRe.find(html)?.let {
            return Stream(it.groupValues[1].replace("\\u0026", "&").replace("\\/", "/"), WEB_UA, id, true)
        }
        // Pas de flux direct : le lecteur intégré prendra le relais.
        return Stream(null, WEB_UA, id, live)
    }

    /** Ouvre la vidéo ou la chaîne dans l'application YouTube (utilisé seulement sur demande). */
    fun openInApp(ctx: Context, raw: String): Boolean {
        val uri = Uri.parse(normalize(raw))
        for (pkg in listOf("com.google.android.youtube.tv", "com.google.android.youtube", null)) {
            try {
                val i = Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (pkg != null) i.setPackage(pkg)
                ctx.startActivity(i)
                return true
            } catch (_: Exception) {
            }
        }
        return false
    }

    data class Preset(val name: String, val url: String, val group: String)

    /** Suggestions de directs officiels (les pseudos peuvent changer : testez-les). */
    val presets = listOf(
        Preset("France 24 (français)", "https://www.youtube.com/@FRANCE24/live", "Info"),
        Preset("France 24 (arabe)", "https://www.youtube.com/@France24_ar/live", "Info"),
        Preset("Al Jazeera (arabe)", "https://www.youtube.com/@aljazeera/live", "Info"),
        Preset("Al Jazeera English", "https://www.youtube.com/@AlJazeeraEnglish/live", "Info"),
        Preset("Sky News Arabia", "https://www.youtube.com/@skynewsarabia/live", "Info"),
        Preset("Euronews (français)", "https://www.youtube.com/@euronewsfr/live", "Info"),
        Preset("NASA", "https://www.youtube.com/@NASA/live", "Science")
    )
}
