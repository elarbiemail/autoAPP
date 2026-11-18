package com.elarbi.omniiptv.data

import com.elarbi.omniiptv.model.Channel
import com.elarbi.omniiptv.model.Programme
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.google.gson.stream.JsonWriter
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.InputStream
import java.io.InputStreamReader

/**
 * Lecture / écriture JSON en flux : les très grosses réponses des portails (des dizaines de milliers
 * de chaînes) et les listes enregistrées ne sont jamais chargées entièrement en mémoire.
 */
object JsonStream {
    fun reader(input: InputStream) = JsonReader(BufferedReader(InputStreamReader(input, Charsets.UTF_8), 65536)).apply { isLenient = true }

    /** Valeur simple sous forme de texte (les objets et tableaux imbriqués sont ignorés). */
    fun value(r: JsonReader): String = when (r.peek()) {
        JsonToken.STRING, JsonToken.NUMBER -> r.nextString()
        JsonToken.BOOLEAN -> if (r.nextBoolean()) "1" else "0"
        JsonToken.NULL -> { r.nextNull(); "" }
        else -> { r.skipValue(); "" }
    }

    /** Lit un objet en ne gardant que les champs utiles (valeurs simples). */
    fun flat(r: JsonReader, keep: Set<String>): Map<String, String> {
        val m = HashMap<String, String>(keep.size * 2)
        r.beginObject()
        while (r.hasNext()) {
            val k = r.nextName()
            if (k in keep) m[k] = value(r) else r.skipValue()
        }
        r.endObject()
        return m
    }

    /** Parcourt un tableau d'objets, ou les valeurs d'un objet {clé: objet}. */
    fun forEachObject(r: JsonReader, f: (JsonReader) -> Unit) {
        when (r.peek()) {
            JsonToken.BEGIN_ARRAY -> {
                r.beginArray()
                while (r.hasNext()) { if (r.peek() == JsonToken.BEGIN_OBJECT) f(r) else r.skipValue() }
                r.endArray()
            }
            JsonToken.BEGIN_OBJECT -> {
                r.beginObject()
                while (r.hasNext()) { r.nextName(); if (r.peek() == JsonToken.BEGIN_OBJECT) f(r) else r.skipValue() }
                r.endObject()
            }
            else -> r.skipValue()
        }
    }

    // ---------------- Listes de chaînes enregistrées ----------------
    fun writeChannels(file: File, list: Collection<Channel>) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        JsonWriter(BufferedWriter(tmp.writer(Charsets.UTF_8), 65536)).use { w ->
            w.beginArray()
            for (c in list) {
                w.beginObject()
                w.name("k").value(c.key); w.name("s").value(c.sourceId); w.name("n").value(c.name); w.name("u").value(c.url)
                if (c.logo.isNotEmpty()) w.name("l").value(c.logo)
                if (c.group.isNotEmpty()) w.name("g").value(c.group)
                if (c.epgId.isNotEmpty()) w.name("e").value(c.epgId)
                if (c.userAgent.isNotEmpty()) w.name("a").value(c.userAgent)
                if (c.referer.isNotEmpty()) w.name("r").value(c.referer)
                if (c.isVod) w.name("v").value(true)
                if (c.archive > 0) w.name("c").value(c.archive.toLong())
                w.endObject()
            }
            w.endArray()
        }
        if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
    }

    fun readChannels(file: File): List<Channel> {
        if (!file.exists()) return emptyList()
        val out = ArrayList<Channel>()
        file.inputStream().use { input ->
            val r = reader(input)
            r.beginArray()
            while (r.hasNext()) {
                var k = ""; var s = ""; var n = ""; var u = ""; var l = ""; var g = ""; var e = ""; var a = ""; var rf = ""
                var v = false; var c = 0
                r.beginObject()
                while (r.hasNext()) {
                    when (r.nextName()) {
                        "k" -> k = value(r); "s" -> s = value(r); "n" -> n = value(r); "u" -> u = value(r)
                        "l" -> l = value(r); "g" -> g = value(r); "e" -> e = value(r); "a" -> a = value(r); "r" -> rf = value(r)
                        "v" -> v = value(r).let { it == "1" || it == "true" }
                        "c" -> c = value(r).toIntOrNull() ?: 0
                        else -> r.skipValue()
                    }
                }
                r.endObject()
                if (k.isNotEmpty()) out += Channel(k, s, n, u, l, g, e, a, rf, v, c)
            }
            r.endArray()
        }
        return out
    }

    // ---------------- Guide TV enregistré ----------------
    fun writeEpg(file: File, map: Map<String, List<Programme>>) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        JsonWriter(BufferedWriter(tmp.writer(Charsets.UTF_8), 65536)).use { w ->
            w.beginObject()
            for ((k, list) in map) {
                w.name(k).beginArray()
                for (p in list) {
                    w.beginArray().value(p.start).value(p.end).value(p.title).value(p.desc).endArray()
                }
                w.endArray()
            }
            w.endObject()
        }
        if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
    }

    fun readEpg(file: File): Map<String, List<Programme>> {
        if (!file.exists()) return emptyMap()
        val m = HashMap<String, List<Programme>>()
        file.inputStream().use { input ->
            val r = reader(input)
            r.beginObject()
            while (r.hasNext()) {
                val k = r.nextName()
                val l = ArrayList<Programme>()
                r.beginArray()
                while (r.hasNext()) {
                    r.beginArray()
                    val s = value(r).toLongOrNull() ?: 0L
                    val e = value(r).toLongOrNull() ?: 0L
                    val t = value(r)
                    val d = if (r.hasNext()) value(r) else ""
                    while (r.hasNext()) r.skipValue()
                    r.endArray()
                    l += Programme(s, e, t, d)
                }
                r.endArray()
                m[k] = l
            }
            r.endObject()
        }
        return m
    }
}
