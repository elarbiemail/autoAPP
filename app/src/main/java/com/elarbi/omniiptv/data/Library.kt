package com.elarbi.omniiptv.data

import com.elarbi.omniiptv.model.Channel
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Bibliothèque personnelle : liste « À regarder » et reprise de lecture des films, séries et vidéos. */
object Library {
    val watchlist = LinkedHashMap<String, Channel>()
    val resume = LinkedHashMap<String, Channel>()
    private val positions = HashMap<String, LongArray>()

    private fun file(n: String) = File(Repo.app.filesDir, n)

    @Synchronized
    fun init() {
        read("watchlist.json")?.let { a -> for (i in 0 until a.length()) Channel.fromJson(a.getJSONObject(i)).let { watchlist[it.key] = it } }
        read("resume.json")?.let { a -> for (i in 0 until a.length()) Channel.fromJson(a.getJSONObject(i)).let { resume[it.key] = it } }
        try {
            val f = file("positions.json")
            if (f.exists()) {
                val o = JSONObject(f.readText())
                val keys = o.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    val a = o.getJSONArray(k)
                    positions[k] = longArrayOf(a.getLong(0), a.getLong(1))
                }
            }
        } catch (_: Exception) {
        }
    }

    private fun read(n: String): JSONArray? = file(n).takeIf { it.exists() }?.let {
        try { JSONArray(it.readText()) } catch (e: Exception) { null }
    }

    private fun write(n: String, list: Collection<Channel>) {
        val a = JSONArray()
        list.forEach { a.put(it.toJson()) }
        file(n).writeText(a.toString())
    }

    private fun writePositions() {
        val o = JSONObject()
        positions.forEach { (k, v) -> o.put(k, JSONArray().put(v[0]).put(v[1])) }
        file("positions.json").writeText(o.toString())
    }

    @Synchronized
    fun isWatch(key: String) = watchlist.containsKey(key)

    @Synchronized
    fun toggleWatch(ch: Channel): Boolean {
        val added = if (watchlist.remove(ch.key) != null) false else { watchlist[ch.key] = ch; true }
        write("watchlist.json", watchlist.values)
        Repo.bump()
        return added
    }

    @Synchronized
    fun position(key: String): Long? = positions[key]?.get(0)

    /** Pourcentage déjà vu, ou null. */
    @Synchronized
    fun progress(key: String): Int? = positions[key]?.let { if (it[1] > 0) (it[0] * 100 / it[1]).toInt() else null }

    @Synchronized
    fun savePosition(ch: Channel, pos: Long, dur: Long) {
        if (dur <= 0) return
        if (pos >= dur * 95 / 100) {
            finish(ch)
            return
        }
        if (pos < 30_000) return
        positions[ch.key] = longArrayOf(pos, dur)
        val wasFirst = resume.keys.firstOrNull() == ch.key
        if (!wasFirst) {
            val copy = LinkedHashMap(resume)
            copy.remove(ch.key)
            resume.clear()
            resume[ch.key] = ch
            resume.putAll(copy)
            while (resume.size > 40) resume.remove(resume.keys.last())
            write("resume.json", resume.values)
            Repo.bump()
        }
        writePositions()
    }

    @Synchronized
    fun finish(ch: Channel) {
        positions.remove(ch.key)
        if (resume.remove(ch.key) != null) write("resume.json", resume.values)
        writePositions()
        Repo.bump()
    }

    @Synchronized
    fun restart(key: String) {
        positions.remove(key)
        writePositions()
    }

    @Synchronized
    fun find(key: String): Channel? = watchlist[key] ?: resume[key]
}
