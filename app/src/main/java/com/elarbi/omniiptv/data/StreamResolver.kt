package com.elarbi.omniiptv.data

import com.elarbi.omniiptv.model.Channel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object StreamResolver {
    /** Renvoie l'URL réellement lisible (les portails MAC donnent un lien temporaire). */
    suspend fun resolve(ch: Channel): String = withContext(Dispatchers.IO) {
        when {
            ch.url.startsWith("stalker:") -> Repo.stalker(ch.sourceId).createLink(ch.url.removePrefix("stalker:"))
            YouTube.isYouTube(ch.url) -> YouTube.resolveHls(ch.url)
            else -> ch.url
        }
    }

    fun userAgent(ch: Channel): String =
        ch.userAgent.ifBlank { Repo.sources.firstOrNull { it.id == ch.sourceId }?.userAgent.orEmpty() }
            .ifBlank { Http.DEFAULT_UA }
}
