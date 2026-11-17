package com.elarbi.omniiptv.data

import com.elarbi.omniiptv.model.Channel
import com.elarbi.omniiptv.ui.Ui
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Catalogues publics de chaînes en libre accès (diffusées gratuitement par leurs éditeurs). */
object FreeCatalog {
    data class Preset(val label: String, val url: String)

    private const val ORG = "https://iptv-org.github.io/iptv"
    const val INDEX = "$ORG/index.m3u"

    val presets = listOf(
        Preset("Maroc", "$ORG/countries/ma.m3u"),
        Preset("France", "$ORG/countries/fr.m3u"),
        Preset("Algérie", "$ORG/countries/dz.m3u"),
        Preset("Tunisie", "$ORG/countries/tn.m3u"),
        Preset("Égypte", "$ORG/countries/eg.m3u"),
        Preset("Arabie saoudite", "$ORG/countries/sa.m3u"),
        Preset("Qatar", "$ORG/countries/qa.m3u"),
        Preset("Émirats", "$ORG/countries/ae.m3u"),
        Preset("Belgique", "$ORG/countries/be.m3u"),
        Preset("Canada", "$ORG/countries/ca.m3u"),
        Preset("Langue arabe", "$ORG/languages/ara.m3u"),
        Preset("Langue française", "$ORG/languages/fra.m3u"),
        Preset("Langue anglaise", "$ORG/languages/eng.m3u"),
        Preset("Information", "$ORG/categories/news.m3u"),
        Preset("Éducation", "$ORG/categories/education.m3u"),
        Preset("Documentaires", "$ORG/categories/documentary.m3u"),
        Preset("Enfants", "$ORG/categories/kids.m3u"),
        Preset("Culture", "$ORG/categories/culture.m3u"),
        Preset("Science", "$ORG/categories/science.m3u"),
        Preset("Musique", "$ORG/categories/music.m3u"),
        Preset("Religion", "$ORG/categories/religious.m3u"),
        Preset("Sport", "$ORG/categories/sports.m3u"),
        Preset("Films", "$ORG/categories/movies.m3u"),
        Preset("Free-TV (sélection mondiale)", "https://raw.githubusercontent.com/Free-TV/IPTV/master/playlist.m3u8"),
        Preset("Monde entier (iptv-org)", INDEX)
    )

    private var indexCache: List<Channel>? = null

    suspend fun load(url: String): List<Channel> = withContext(Dispatchers.IO) {
        M3uParser.parse(Http.get(url), "free").channels
    }

    /** Recherche par nom ou catégorie dans tout le catalogue mondial. */
    suspend fun search(keyword: String): List<Channel> = withContext(Dispatchers.IO) {
        val all = indexCache ?: load(INDEX).also { indexCache = it }
        val k = Ui.norm(keyword)
        all.filter { Ui.norm(it.name).contains(k) || Ui.norm(it.group).contains(k) }
    }
}
