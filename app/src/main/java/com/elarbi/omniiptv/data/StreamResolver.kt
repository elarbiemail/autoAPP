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
        val x = try { Repo.stalker(s.id).xUserAgent } catch (e: Exception) { "Model: MAG250; Link: Ethernet" }
        return mapOf("Cookie" to "mac=$mac; stb_lang=fr; timezone=$tz", "X-User-Agent" to x)
    }

    /**
     * Le lien désigne-t-il un flux HLS ? Reconnaît « .m3u8 » mais aussi les formes des portails
     * Xtream / MAG : « extension=m3u8 », « output=m3u8 », « type=m3u8 », « /hls/ »…
     */
    fun looksHls(url: String): Boolean {
        val l = url.lowercase()
        return l.contains(".m3u8") || l.contains("=m3u8") || l.contains("=hls") || l.contains("/hls/") ||
            l.contains("m3u8?") || l.endsWith("/m3u8") || l.contains("format=m3u")
    }

    /** Le lien désigne-t-il explicitement un flux MPEG-TS ? */
    fun looksTs(url: String): Boolean {
        val l = url.lowercase()
        return l.contains(".ts?") || l.endsWith(".ts") || l.contains("extension=ts") || l.contains("output=ts") || l.contains("output=mpegts")
    }

    /** Les liens des portails MAC sont à usage unique : il faut en redemander un à chaque essai. */
    fun isStalker(ch: Channel) = ch.url.startsWith("stalker:") || ch.url.startsWith("stkvod:")

    fun userAgent(ch: Channel): String =
        if (isStalker(ch)) (try { Repo.stalker(ch.sourceId).userAgent } catch (e: Exception) { StalkerClient.MAG_UA }) else

        YouTube.userAgentFor(ch.key) ?: ch.userAgent.ifBlank { Repo.sources.firstOrNull { it.id == ch.sourceId }?.userAgent.orEmpty() }
            .ifBlank { Http.DEFAULT_UA }
}
