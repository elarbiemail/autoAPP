package com.elarbi.omniiptv.data

import com.elarbi.omniiptv.model.Channel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object StreamResolver {
    /** Renvoie l'URL réellement lisible (les portails MAC donnent un lien temporaire). */
    suspend fun resolve(ch: Channel): String = withContext(Dispatchers.IO) {
        when {
            ch.url.startsWith("stalker:") -> Repo.stalker(ch.sourceId).createLink(ch.url.removePrefix("stalker:"))
            ch.url.startsWith("stkvod:") -> {
                val v = ch.url.removePrefix("stkvod:")
                Repo.stalker(ch.sourceId).createLink(v.substringAfter(':'), "vod", v.substringBefore(':'))
            }
            YouTube.isYouTube(ch.url) -> YouTube.resolveForChannel(ch.key, ch.url)
            else -> ch.url
        }
    }

    /** Portails MAC : en-têtes d'un vrai boîtier MAG aussi pendant la lecture. */
    fun headers(ch: Channel): Map<String, String> {
        if (!isStalker(ch)) return emptyMap()
        val s = Repo.sources.firstOrNull { it.id == ch.sourceId } ?: return emptyMap()
        val mac = java.net.URLEncoder.encode(s.mac.trim().uppercase(), "UTF-8")
        val tz = java.net.URLEncoder.encode(java.util.TimeZone.getDefault().id, "UTF-8")
        return mapOf("Cookie" to "mac=$mac; stb_lang=fr; timezone=$tz", "X-User-Agent" to "Model: MAG250; Link: WiFi")
    }

    /** Les liens des portails MAC sont à usage unique : il faut en redemander un à chaque essai. */
    fun isStalker(ch: Channel) = ch.url.startsWith("stalker:") || ch.url.startsWith("stkvod:")

    fun userAgent(ch: Channel): String =
        YouTube.userAgentFor(ch.key) ?: ch.userAgent.ifBlank { Repo.sources.firstOrNull { it.id == ch.sourceId }?.userAgent.orEmpty() }
            .ifBlank { Http.DEFAULT_UA }
}
