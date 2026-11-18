package com.elarbi.omniiptv.data

import com.elarbi.omniiptv.model.Channel
import java.net.URLDecoder

object M3uParser {
    class Result(val channels: List<Channel>, val epgUrl: String)

    private val attrRe = Regex("([A-Za-z0-9_-]+)=\"([^\"]*)\"")

    private fun attrs(line: String): Map<String, String> =
        attrRe.findAll(line).associate { it.groupValues[1].lowercase() to it.groupValues[2] }

    private fun decode(v: String): String = try { URLDecoder.decode(v, "UTF-8") } catch (e: Exception) { v }

    fun parse(text: String, sourceId: String): Result = parseLines(text.lineSequence(), sourceId)

    /** Lecture ligne à ligne (listes très volumineuses sans tout charger en mémoire). */
    fun parseLines(lines: Sequence<String>, sourceId: String): Result {
        var epg = ""
        val out = ArrayList<Channel>()
        val seen = HashSet<String>()
        var a: Map<String, String> = emptyMap()
        var name = ""
        var ua = ""
        var ref = ""
        var grp = ""
        for (raw in lines) {
            val line = raw.trim().removePrefix("﻿")
            if (line.isEmpty()) continue
            if (line.startsWith("#EXTM3U")) {
                val h = attrs(line)
                epg = h["url-tvg"] ?: h["x-tvg-url"] ?: epg
                continue
            }
            if (line.startsWith("#EXTINF")) {
                a = attrs(line)
                val lastAttr = attrRe.findAll(line).lastOrNull()?.range?.last ?: line.indexOf(':')
                val c = line.indexOf(',', lastAttr + 1)
                name = if (c >= 0) line.substring(c + 1).trim() else ""
                ua = ""; ref = ""; grp = ""
                continue
            }
            if (line.startsWith("#EXTVLCOPT:")) {
                val v = line.substringAfter(':')
                if (v.startsWith("http-user-agent=")) ua = v.substringAfter('=')
                if (v.startsWith("http-referrer=") || v.startsWith("http-referer=")) ref = v.substringAfter('=')
                continue
            }
            if (line.startsWith("#EXTGRP:")) {
                grp = line.substringAfter(':').trim()
                continue
            }
            if (line.startsWith("#")) continue

            var url = line
            if (url.contains('|')) {
                val opts = url.substringAfter('|')
                url = url.substringBefore('|')
                opts.split('&').forEach { kv ->
                    val k = kv.substringBefore('=').trim()
                    val v = decode(kv.substringAfter('=', "").trim())
                    if (k.equals("User-Agent", true)) ua = v
                    if (k.equals("Referer", true) || k.equals("Referrer", true)) ref = v
                }
            }
            val nm = name.ifBlank { a["tvg-name"] ?: url.substringAfterLast('/') }
            var key = "$sourceId|${(url + nm).hashCode()}"
            if (!seen.add(key)) key += "_${out.size}"
            val group = (a["group-title"] ?: grp).substringBefore(';').ifBlank { "Sans catégorie" }
            out.add(
                Channel(
                    key = key, sourceId = sourceId, name = nm, url = url,
                    logo = a["tvg-logo"].orEmpty(), group = group, epgId = a["tvg-id"].orEmpty(),
                    userAgent = ua.ifBlank { a["user-agent"].orEmpty() },
                    referer = ref.ifBlank { a["referrer"].orEmpty() },
                    isVod = url.contains("/movie/") || url.contains("/series/")
                )
            )
            a = emptyMap(); name = ""; ua = ""; ref = ""; grp = ""
        }
        return Result(out, epg)
    }
}
