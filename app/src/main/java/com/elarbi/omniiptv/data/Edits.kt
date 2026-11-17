package com.elarbi.omniiptv.data

import com.elarbi.omniiptv.model.Channel
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Personnalisations d'une chaîne : nom, groupe, masquage, position, identifiant de guide, logo. */
data class ChannelEdit(
    var name: String? = null,
    var group: String? = null,
    var hidden: Boolean = false,
    var pos: Double? = null,
    var epgId: String? = null,
    var logo: String? = null
)

class SourceEdits {
    val channels = HashMap<String, ChannelEdit>()
    val hiddenGroups = HashSet<String>()
    val groupOrder = ArrayList<String>()
}

/** Gestionnaire de chaînes : applique les personnalisations sur les listes des sources. */
object Edits {
    private val cache = HashMap<String, SourceEdits>()
    private val viewCache = HashMap<String, Pair<Int, List<Channel>>>()

    private fun file(sid: String) = File(Repo.app.filesDir, "edits_$sid.json")

    @Synchronized
    fun of(sid: String): SourceEdits = cache.getOrPut(sid) {
        val se = SourceEdits()
        try {
            val f = file(sid)
            if (f.exists()) {
                val o = JSONObject(f.readText())
                o.optJSONObject("channels")?.let { c ->
                    val keys = c.keys()
                    while (keys.hasNext()) {
                        val k = keys.next()
                        val e = c.getJSONObject(k)
                        se.channels[k] = ChannelEdit(
                            e.optString("name").ifBlank { null }, e.optString("group").ifBlank { null },
                            e.optBoolean("hidden"), if (e.has("pos")) e.getDouble("pos") else null,
                            e.optString("epgId").ifBlank { null }, e.optString("logo").ifBlank { null }
                        )
                    }
                }
                o.optJSONArray("hiddenGroups")?.let { a -> for (i in 0 until a.length()) se.hiddenGroups.add(a.getString(i)) }
                o.optJSONArray("groupOrder")?.let { a -> for (i in 0 until a.length()) se.groupOrder.add(a.getString(i)) }
            }
        } catch (_: Exception) {
        }
        se
    }

    @Synchronized
    fun save(sid: String) {
        val se = of(sid)
        val c = JSONObject()
        se.channels.forEach { (k, e) ->
            val o = JSONObject()
            e.name?.let { o.put("name", it) }
            e.group?.let { o.put("group", it) }
            if (e.hidden) o.put("hidden", true)
            e.pos?.let { o.put("pos", it) }
            e.epgId?.let { o.put("epgId", it) }
            e.logo?.let { o.put("logo", it) }
            if (o.length() > 0) c.put(k, o)
        }
        val root = JSONObject().put("channels", c)
            .put("hiddenGroups", JSONArray(se.hiddenGroups.toList()))
            .put("groupOrder", JSONArray(se.groupOrder))
        file(sid).writeText(root.toString())
        viewCache.clear()
        Repo.bump()
    }

    @Synchronized
    fun reset(sid: String) {
        cache[sid] = SourceEdits()
        file(sid).delete()
        viewCache.clear()
        Repo.bump()
    }

    private fun edit(sid: String, key: String): ChannelEdit = of(sid).channels.getOrPut(key) { ChannelEdit() }

    /** Applique les personnalisations (et le logo du guide TV si aucun logo). */
    fun apply(ch: Channel): Channel {
        val e = synchronized(this) { of(ch.sourceId).channels[ch.key] }
        val logo = e?.logo ?: ch.logo.ifBlank { Repo.epgIcon(ch) }
        if (e == null && logo == ch.logo) return ch
        return ch.copy(
            name = e?.name ?: ch.name,
            group = e?.group ?: ch.group,
            logo = logo,
            epgId = e?.epgId ?: ch.epgId
        )
    }

    fun isHidden(ch: Channel): Boolean {
        val se = synchronized(this) { of(ch.sourceId) }
        return se.channels[ch.key]?.hidden == true || apply(ch).group in se.hiddenGroups
    }

    /** Liste ordonnée et filtrée des chaînes d'une source. */
    fun view(sid: String, includeHidden: Boolean = false): List<Channel> {
        val cacheKey = "$sid|$includeHidden"
        val v = Repo.version
        synchronized(this) { viewCache[cacheKey]?.let { if (it.first == v) return it.second } }
        val raw = Repo.channels(sid)
        val se = synchronized(this) { of(sid) }
        val rows = raw.mapIndexed { i, c -> Triple(apply(c), se.channels[c.key], i) }
        val firstSeen = HashMap<String, Int>()
        rows.forEach { (c, _, i) -> firstSeen.putIfAbsent(c.group, i) }
        val groupIdx = HashMap<String, Int>()
        se.groupOrder.forEachIndexed { i, g -> groupIdx[g] = i }
        val visible = if (includeHidden) rows else rows.filter { (c, e, _) -> e?.hidden != true && c.group !in se.hiddenGroups && !Prefs.isHiddenAdult(c) }
        val sorted = visible.sortedWith(
            compareBy<Triple<Channel, ChannelEdit?, Int>>(
                { groupIdx[it.first.group] ?: (1_000_000 + (firstSeen[it.first.group] ?: 0)) },
                { it.second?.pos ?: it.third.toDouble() }
            )
        ).map { it.first }
        synchronized(this) { viewCache[cacheKey] = v to sorted }
        return sorted
    }

    fun groups(sid: String): List<String> = view(sid, true).map { it.group }.distinct()

    fun isGroupHidden(sid: String, g: String) = synchronized(this) { g in of(sid).hiddenGroups }

    @Synchronized
    fun toggleGroupHidden(sid: String, g: String) {
        val se = of(sid)
        if (!se.hiddenGroups.remove(g)) se.hiddenGroups.add(g)
        save(sid)
    }

    fun moveGroup(sid: String, g: String, delta: Int) {
        val order = groups(sid).toMutableList()
        val i = order.indexOf(g)
        val j = i + delta
        if (i < 0 || j < 0 || j >= order.size) return
        order[i] = order[j].also { order[j] = order[i] }
        synchronized(this) {
            val se = of(sid)
            se.groupOrder.clear()
            se.groupOrder.addAll(order)
        }
        save(sid)
    }

    fun renameGroup(sid: String, old: String, new: String) {
        val members = view(sid, true).filter { it.group == old }
        synchronized(this) {
            val se = of(sid)
            members.forEach { edit(sid, it.key).group = new }
            if (se.hiddenGroups.remove(old)) se.hiddenGroups.add(new)
            val i = se.groupOrder.indexOf(old)
            if (i >= 0) se.groupOrder[i] = new
        }
        save(sid)
    }

    fun moveChannel(sid: String, key: String, delta: Int) {
        val all = view(sid, true)
        val ch = all.firstOrNull { it.key == key } ?: return
        val inGroup = all.filter { it.group == ch.group }
        val i = inGroup.indexOfFirst { it.key == key }
        val j = i + delta
        if (j < 0 || j >= inGroup.size) return
        synchronized(this) {
            inGroup.forEachIndexed { n, c -> edit(sid, c.key).pos = n.toDouble() }
            edit(sid, inGroup[i].key).pos = j.toDouble()
            edit(sid, inGroup[j].key).pos = i.toDouble()
        }
        save(sid)
    }

    fun update(sid: String, key: String, block: (ChannelEdit) -> Unit) {
        synchronized(this) { block(edit(sid, key)) }
        save(sid)
    }

    @Synchronized
    fun clear(sid: String, key: String) {
        of(sid).channels.remove(key)
        save(sid)
    }

    @Synchronized
    fun setLogoQuiet(sid: String, key: String, url: String) {
        edit(sid, key).logo = url
    }
}
