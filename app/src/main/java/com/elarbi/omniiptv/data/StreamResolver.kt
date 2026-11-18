package com.elarbi.omniiptv.data

import com.elarbi.omniiptv.model.Channel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object StreamResolver {
    /** Renvoie l'URL réellement lisible (les portails MAC donnent un lien temporaire). */
    /** Pour les chaînes des portails MAC : description du dernier lien choisi (affichée en cas d'échec). */
    @Volatile var lastStrategy = ""

    suspend fun resolve(ch: Channel, attempt: Int = 0): String = withContext(Dispatchers.IO) {
        when {
            ch.url.startsWith("stalker:") -> {
                val (u, how) = Repo.stalker(ch.sourceId).liveLink(ch.url.removePrefix("stalker:"), stalkerId(ch), attempt)
                lastStrategy = how
                u
            }
            ch.url.startsWith("stkvod:") -> {
                val v = ch.url.removePrefix("stkvod:")
                Repo.stalker(ch.sourceId).createLink(v.substringAfter(':'), "vod", v.substringBefore(':'))
            }
            YouTube.isYouTube(ch.url) -> YouTube.resolveForChannel(ch.key, ch.url)
            else -> current(ch)
        }
    }

    private val xtreamPath = Regex("^(.*?)/(live|movie|series|timeshift)/([^/]+)/([^/]+)/(.+)$")

    /**
     * Xtream : le lien est reconstruit avec le serveur et les identifiants actuels de la source,
     * pour que les chaînes, films et épisodes enregistrés marchent aussitôt après une modification du compte.
     */
    fun current(ch: Channel): String = xtreamUrl(ch.sourceId, ch.url)

    fun xtreamUrl(sourceId: String, url: String): String {
        val s = Repo.sources.firstOrNull { it.id == sourceId && it.type == com.elarbi.omniiptv.model.SourceType.XTREAM } ?: return url
        val m = xtreamPath.find(url) ?: return url
        val base = s.url.trim().trimEnd('/').removeSuffix("/player_api.php").removeSuffix("/get.php").trimEnd('/')
        if (base.isBlank() || s.username.isBlank()) return url
        val (_, kind, _, _, rest) = m.destructured
        return "$base/$kind/${s.username}/${s.password}/$rest"
    }

    /** Portails MAC : en-têtes d'un vrai boîtier MAG aussi pendant la lecture. */
    /** Identifiant de la chaîne dans le portail (clé « source|stk123 »). */
    fun stalkerId(ch: Channel): String = ch.key.substringAfterLast("|stk", "").takeIf { it.all(Char::isDigit) }.orEmpty()

    /**
     * En-têtes de lecture. Essais 0 à 2 : comme un boîtier MAG (User-Agent, X-User-Agent, cookie mac).
     * À partir de l'essai 3 : seulement le User-Agent du boîtier, comme QiTV ou VLC dans MacAttack.
     */
    fun headers(ch: Channel, attempt: Int = 0): Map<String, String> {
        if (!isStalker(ch)) return emptyMap()
        if (attempt >= 3) return emptyMap()
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
