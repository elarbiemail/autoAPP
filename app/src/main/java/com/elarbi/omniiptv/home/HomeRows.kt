package com.elarbi.omniiptv.home

import android.content.Context
import com.elarbi.omniiptv.data.Edits
import com.elarbi.omniiptv.data.FavLists
import com.elarbi.omniiptv.data.Library
import com.elarbi.omniiptv.data.Prefs
import com.elarbi.omniiptv.data.Repo
import com.elarbi.omniiptv.model.Channel
import org.json.JSONArray
import org.json.JSONObject

/**
 * Rangées d'Omni IPTV sur l'écran d'accueil Google TV, personnalisables :
 * nom, contenu (mélange, une liste de favoris, reprise, « À regarder », récentes, programmes en cours,
 * une catégorie d'une source), nombre d'éléments et présentation des vignettes.
 */
object HomeRows {
    /**
     * kind : main | fav (arg = id de liste) | resume | watch | recent | now | group (arg = source \u0001 catégorie)
     * style : program (programme en cours, vignette composée) | channel (nom et logo de la chaîne)
     */
    data class Row(
        val id: String, val kind: String, val arg: String, val name: String,
        val max: Int = 30, val style: String = "program", val enabled: Boolean = true
    )

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("home", Context.MODE_PRIVATE)

    fun kindLabel(r: Row): String = when (r.kind) {
        "main" -> "Mélange : reprise, favoris, à regarder"
        "fav" -> "Liste « ${FavLists.name(r.arg)} »"
        "resume" -> "Continuer à regarder"
        "watch" -> "À regarder"
        "recent" -> "Regardées récemment"
        "now" -> "En ce moment (programmes en cours de vos chaînes)"
        "group" -> "Catégorie « ${r.arg.substringAfter('\u0001')} » de ${Repo.sources.firstOrNull { it.id == r.arg.substringBefore('\u0001') }?.name ?: "?"}"
        else -> r.kind
    }

    @Synchronized
    fun rows(ctx: Context): List<Row> {
        val p = prefs(ctx)
        val raw = p.getString("rows", null)
        val list = if (raw == null) {
            // Première fois : la rangée historique, et « En ce moment » si elle a déjà été ajoutée.
            val l = mutableListOf(Row(HomeScreen.SLOT_FAV, "main", "", HomeScreen.NAME_FAV, 40))
            if (p.getLong(HomeScreen.SLOT_LIVE, -1L) > 0) l += Row(HomeScreen.SLOT_LIVE, "now", "", HomeScreen.NAME_LIVE, 40)
            l
        } else try {
            val a = JSONArray(raw)
            (0 until a.length()).map { i ->
                a.getJSONObject(i).let { o ->
                    Row(o.getString("id"), o.getString("kind"), o.optString("arg"), o.getString("name"),
                        o.optInt("max", 30), o.optString("style", "program"), o.optBoolean("on", true))
                }
            }
        } catch (e: Exception) { listOf(Row(HomeScreen.SLOT_FAV, "main", "", HomeScreen.NAME_FAV, 40)) }
        // « En ce moment » ajoutée depuis l'ancien menu : on la reprend.
        if (list.none { it.id == HomeScreen.SLOT_LIVE } && p.getLong(HomeScreen.SLOT_LIVE, -1L) > 0 && raw != null && !p.getBoolean("liveRemoved", false))
            return list + Row(HomeScreen.SLOT_LIVE, "now", "", HomeScreen.NAME_LIVE, 40)
        return list
    }

    @Synchronized
    fun save(ctx: Context, l: List<Row>) {
        val a = JSONArray()
        l.forEach { r ->
            a.put(JSONObject().put("id", r.id).put("kind", r.kind).put("arg", r.arg).put("name", r.name)
                .put("max", r.max).put("style", r.style).put("on", r.enabled))
        }
        prefs(ctx).edit().putString("rows", a.toString()).apply()
    }

    fun add(ctx: Context, kind: String, arg: String, name: String): Row {
        val r = Row("row" + System.currentTimeMillis().toString(36), kind, arg, name.take(40))
        save(ctx, rows(ctx) + r)
        return r
    }

    fun update(ctx: Context, r: Row) = save(ctx, rows(ctx).map { if (it.id == r.id) r else it })

    fun move(ctx: Context, id: String, delta: Int) {
        val l = rows(ctx).toMutableList()
        val i = l.indexOfFirst { it.id == id }
        val j = (i + delta).coerceIn(0, l.size - 1)
        if (i < 0 || i == j) return
        l.add(j, l.removeAt(i))
        save(ctx, l)
    }

    /** Supprime la rangée et sa chaîne sur l'écran d'accueil. */
    fun remove(ctx: Context, id: String) {
        save(ctx, rows(ctx).filter { it.id != id })
        if (id == HomeScreen.SLOT_LIVE) prefs(ctx).edit().putBoolean("liveRemoved", true).apply()
        HomeScreen.deleteChannel(ctx, id)
    }

    /** Éléments d'une rangée (jamais toute une grosse liste : seulement ce qui est déjà en mémoire ou petit). */
    fun items(r: Row): List<Channel> {
        val list: List<Channel> = when (r.kind) {
            "fav" -> FavLists.items(r.arg)
            "resume" -> Library.resume.values.toList()
            "watch" -> Library.watchlist.values.toList()
            "recent" -> Repo.recents.toList()
            "now" -> (FavLists.lists().flatMap { FavLists.items(it.id) } + Repo.recents)
                .filter { !it.isVod }.distinctBy { it.key }.filter { Repo.nowNext(it).first != null }
            "group" -> {
                val sid = r.arg.substringBefore('\u0001')
                val g = r.arg.substringAfter('\u0001')
                try { Edits.view(sid).asSequence().filter { it.group == g }.take(r.max).toList() } catch (e: Throwable) { emptyList() }
            }
            else -> {
                val resume = Library.resume.values.toList()
                val fav = Repo.favorites.values.map { Edits.apply(it) }
                val watch = Library.watchlist.values.toList()
                var all = (resume.take(10) + fav + watch).distinctBy { it.key }
                if (all.size < 6) all = (all + Repo.recents).distinctBy { it.key }
                all
            }
        }
        return list.filter { !Prefs.isLocked(it) && !Prefs.isHiddenAdult(it) }.take(r.max)
    }
}
