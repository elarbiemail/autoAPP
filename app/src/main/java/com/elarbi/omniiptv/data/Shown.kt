package com.elarbi.omniiptv.data

import com.elarbi.omniiptv.model.Channel
import com.elarbi.omniiptv.model.Source

/**
 * Sources affichées en même temps (une seule par défaut, ou plusieurs au choix).
 * Avec plusieurs sources, chaque groupe est préfixé par le nom de sa source.
 */
object Shown {
    private var cache: Pair<Int, List<Channel>>? = null

    fun sources(): List<Source> {
        val ids = Prefs.shownSources
        val l = Repo.sources.filter { it.id in ids }
        return if (l.size >= 2) l else listOfNotNull(Repo.activeSource())
    }

    fun multi() = sources().size > 1

    fun title(): String = sources().joinToString(" + ") { it.name }.ifBlank { "Omni IPTV" }

    /** Chaînes de toutes les sources affichées. */
    @Synchronized
    fun channels(): List<Channel> {
        val v = Repo.version
        cache?.let { if (it.first == v) return it.second }
        val src = sources()
        val l = if (src.size <= 1) src.firstOrNull()?.let { Edits.view(it.id) } ?: emptyList()
        else src.flatMap { s -> Edits.view(s.id).map { it.copy(group = "${s.name} · ${it.group}") } }
        cache = v to l
        return l
    }

    @Synchronized
    fun invalidate() { cache = null }

    /** Affiche une seule source. */
    fun showOnly(id: String) {
        Prefs.shownSources = emptySet()
        Repo.activeSourceId = id
    }

    /** Affiche plusieurs sources (la première devient la source active). */
    fun showSeveral(ids: List<String>) {
        if (ids.isEmpty()) return
        Prefs.shownSources = if (ids.size >= 2) ids.toSet() else emptySet()
        Repo.activeSourceId = ids.first()
    }
}
