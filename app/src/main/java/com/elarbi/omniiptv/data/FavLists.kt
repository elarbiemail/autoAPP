package com.elarbi.omniiptv.data

import android.content.Context
import com.elarbi.omniiptv.model.Channel
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Plusieurs listes de favoris avec des noms personnalisés.
 * La liste « Favoris » d'origine (celle de l'écran d'accueil du téléviseur) reste la première ;
 * les autres sont enregistrées chacune dans leur fichier, avec leur ordre.
 */
object FavLists {
    data class FavList(val id: String, val name: String)

    const val DEFAULT = "fav"
    private val prefs get() = Repo.app.getSharedPreferences("favlists", Context.MODE_PRIVATE)
    private val cache = HashMap<String, LinkedHashMap<String, Channel>>()

    @Synchronized
    fun lists(): List<FavList> = listOf(FavList(DEFAULT, "Favoris")) + custom()

    fun name(id: String): String = lists().firstOrNull { it.id == id }?.name ?: "Favoris"

    private fun custom(): List<FavList> = try {
        val a = JSONArray(prefs.getString("index", "[]"))
        (0 until a.length()).map { a.getJSONObject(it).let { o -> FavList(o.getString("id"), o.getString("name")) } }
    } catch (e: Exception) { emptyList() }

    private fun saveIndex(l: List<FavList>) {
        val a = JSONArray()
        l.forEach { a.put(JSONObject().put("id", it.id).put("name", it.name)) }
        prefs.edit().putString("index", a.toString()).apply()
    }

    private fun file(id: String) = File(Repo.app.filesDir, "favlist_$id.json")

    private fun map(id: String): LinkedHashMap<String, Channel> = cache.getOrPut(id) {
        val m = LinkedHashMap<String, Channel>()
        try { JsonStream.readChannels(file(id)).forEach { m[it.key] = it } } catch (_: Exception) {}
        m
    }

    private fun save(id: String) {
        if (id == DEFAULT) Repo.setFavorites(Repo.favorites.values.toList())
        else JsonStream.writeChannels(file(id), map(id).values)
        Repo.bump()
    }

    @Synchronized
    fun create(name: String): FavList {
        val l = FavList("l" + System.currentTimeMillis().toString(36), name.trim().ifBlank { "Ma liste" })
        saveIndex(custom() + l)
        return l
    }

    @Synchronized
    fun rename(id: String, name: String) {
        if (id == DEFAULT || name.isBlank()) return
        saveIndex(custom().map { if (it.id == id) it.copy(name = name.trim()) else it })
    }

    @Synchronized
    fun delete(id: String) {
        if (id == DEFAULT) return
        saveIndex(custom().filter { it.id != id })
        cache.remove(id)
        file(id).delete()
        Repo.bump()
    }

    /** Déplace une liste dans l'ordre des listes (la liste « Favoris » reste en tête). */
    @Synchronized
    fun moveList(id: String, delta: Int) {
        val l = custom().toMutableList()
        val i = l.indexOfFirst { it.id == id }
        val j = i + delta
        if (i < 0 || j !in l.indices) return
        l.add(j, l.removeAt(i))
        saveIndex(l)
    }

    @Synchronized
    fun items(id: String): List<Channel> =
        if (id == DEFAULT) Repo.favorites.values.toList() else map(id).values.toList()

    @Synchronized
    fun count(id: String): Int = if (id == DEFAULT) Repo.favorites.size else map(id).size

    @Synchronized
    fun contains(id: String, key: String): Boolean =
        if (id == DEFAULT) Repo.isFavorite(key) else map(id).containsKey(key)

    /** Listes contenant cet élément (pour l'étoile et le menu « Ajouter à une liste »). */
    @Synchronized
    fun containing(key: String): List<FavList> = lists().filter { contains(it.id, key) }

    @Synchronized
    fun add(id: String, chs: List<Channel>) {
        if (id == DEFAULT) { chs.forEach { Repo.favorites[it.key] = it } } else { val m = map(id); chs.forEach { m[it.key] = it } }
        save(id)
    }

    @Synchronized
    fun remove(id: String, key: String) {
        if (id == DEFAULT) Repo.favorites.remove(key) else map(id).remove(key)
        save(id)
    }

    @Synchronized
    fun toggle(id: String, ch: Channel): Boolean {
        val added = !contains(id, ch.key)
        if (added) add(id, listOf(ch)) else remove(id, ch.key)
        return added
    }

    /** Change la place d'un élément dans sa liste. */
    @Synchronized
    fun move(id: String, key: String, delta: Int) {
        val l = items(id).toMutableList()
        val i = l.indexOfFirst { it.key == key }
        val j = (i + delta).coerceIn(0, l.size - 1)
        if (i < 0 || i == j) return
        l.add(j, l.removeAt(i))
        if (id == DEFAULT) Repo.setFavorites(l)
        else { val m = map(id); m.clear(); l.forEach { m[it.key] = it }; JsonStream.writeChannels(file(id), m.values) }
        Repo.bump()
    }

    /** Après rechargement d'une source : les éléments des listes reprennent les liens à jour. */
    @Synchronized
    fun sync(sourceId: String, byKey: Map<String, Channel>) {
        for (l in custom()) {
            val m = map(l.id)
            var changed = false
            for ((k, c) in m.entries.toList()) if (c.sourceId == sourceId) byKey[k]?.let { if (it != c) { m[k] = it; changed = true } }
            if (changed) JsonStream.writeChannels(file(l.id), m.values)
        }
    }
}
