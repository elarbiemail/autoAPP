package com.elarbi.omniiptv.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import java.io.IOException

/**
 * Chaînes YouTube Live : lecture directe du flux HLS du direct dans le lecteur,
 * avec repli sur l'application YouTube officielle si le flux n'est pas disponible.
 * URL acceptées : youtube.com/watch?v=…, youtu.be/…, youtube.com/@pseudo/live,
 * youtube.com/channel/UC…/live, youtube.com/live/…
 */
object YouTube {
    class NotLiveException(msg: String) : IOException(msg)

    private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36"
    private val hlsRe = Regex("\"hlsManifestUrl\"\\s*:\\s*\"(https:[^\"]+)\"")
    private val idRe = Regex("(?:v=|youtu\\.be/|/live/|/embed/|/shorts/)([A-Za-z0-9_-]{11})")

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

    /** Renvoie l'URL HLS du direct, ou lève NotLiveException. */
    fun resolveHls(raw: String): String {
        val url = normalize(raw)
        val page = Http.get(
            url,
            mapOf(
                "User-Agent" to UA,
                "Accept-Language" to "fr-FR,fr;q=0.9",
                "Cookie" to "CONSENT=YES+cb; SOCS=CAI"
            )
        )
        val m = hlsRe.find(page) ?: throw NotLiveException("Aucun direct en cours sur cette chaîne YouTube")
        return m.groupValues[1].replace("\\u0026", "&").replace("\\/", "/")
    }

    /** Ouvre la vidéo ou la chaîne dans l'application YouTube (TV de préférence). */
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
