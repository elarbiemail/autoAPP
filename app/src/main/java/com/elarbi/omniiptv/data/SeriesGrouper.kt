package com.elarbi.omniiptv.data

import com.elarbi.omniiptv.model.Channel
import com.elarbi.omniiptv.model.isSeries

/**
 * Distingue films et séries quand la source les mélange (listes M3U, catégories « VOD » des portails) :
 * les épisodes reconnus à leur nom (« Nom S01 E02 », « Nom 1x02 », « Saison 1 Épisode 2 ») ou à leur
 * adresse (« /series/ ») sont regroupés en une fiche série par catégorie, avec saisons et épisodes triés.
 */
object SeriesGrouper {
    const val PREFIX = "grpseries:"
    const val EP_GROUP = "Épisodes · "

    data class Ep(val series: String, val season: Int, val episode: Int)

    private val pattern = Regex(
        "(?i)(?:^|[\\s._\\-\\[(|:])(?:S(\\d{1,2})\\s*[._ -]?\\s*E[Pp]?(\\d{1,4})" +
            "|(?:saison|season|temporada|staffel)\\s*(\\d{1,2})\\s*[,._ -]*\\s*(?:[éeÉE]pisode|episodio|folge|ep\\.?)\\s*(\\d{1,4})" +
            "|(\\d{1,2})x(\\d{2,3}))(?:\\b|$)"
    )

    fun parse(name: String): Ep? {
        val m = pattern.find(name) ?: return null
        val series = name.substring(0, m.range.first).trim().trimEnd('-', '|', ':', '.', '_', '[', '(', ' ').trim()
        if (series.length < 2) return null
        val g = m.groupValues
        val (sn, en) = when {
            g[1].isNotEmpty() -> g[1] to g[2]
            g[3].isNotEmpty() -> g[3] to g[4]
            else -> g[5] to g[6]
        }
        return Ep(series, sn.toIntOrNull() ?: 1, en.toIntOrNull() ?: 0)
    }

    /** Regroupe les épisodes rangés comme des films ; les fiches séries déjà présentes sont conservées. */
    fun apply(sourceId: String, list: List<Channel>): List<Channel> {
        val existing = list.filter { it.url.startsWith(PREFIX) }.map { it.key }.toHashSet()
        val containers = LinkedHashMap<String, Channel>()
        val out = ArrayList<Channel>(list.size + 64)
        var moved = 0
        for (c in list) {
            if (!c.isVod || c.isSeries || c.group.startsWith(EP_GROUP)) { out += c; continue }
            val e = parse(c.name)
            if (e == null) { out += c; continue }
            val baseGroup = c.group.removePrefix("Films · ")
            val norm = EpgNorm.norm(e.series)
            val key = "$sourceId|GS" + Integer.toHexString((baseGroup + "\u0001" + norm).hashCode()) + "_" + norm.length
            if (key !in existing && key !in containers) {
                containers[key] = Channel(
                    key = key, sourceId = sourceId, name = e.series,
                    url = PREFIX + baseGroup + "\u0001" + e.series, logo = c.logo,
                    group = "Séries · $baseGroup", userAgent = c.userAgent, referer = c.referer, isVod = true
                )
            }
            out += c.copy(group = EP_GROUP + baseGroup)
            moved++
        }
        if (moved == 0) return list
        return ArrayList<Channel>(out.size + containers.size).apply { addAll(containers.values); addAll(out) }
    }

    /** Épisodes d'une fiche série regroupée, triés par saison puis épisode. */
    fun episodes(container: Channel): List<Channel> {
        val spec = container.url.removePrefix(PREFIX)
        val group = EP_GROUP + spec.substringBefore('\u0001')
        val n = EpgNorm.norm(spec.substringAfter('\u0001'))
        return Repo.channels(container.sourceId)
            .mapNotNull { c -> if (c.group != group) null else parse(c.name)?.takeIf { EpgNorm.norm(it.series) == n }?.let { c to it } }
            .sortedWith(compareBy({ it.second.season }, { it.second.episode }))
            .map { it.first }
    }
}
