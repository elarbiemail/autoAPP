package com.elarbi.omniiptv.data

import android.util.JsonReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStreamReader

/** Complète les logos manquants à partir de la base publique iptv-org (lecture en flux). */
object LogoFinder {
    private const val API = "https://iptv-org.github.io/api"

    suspend fun fill(sid: String): Int = withContext(Dispatchers.IO) {
        val targets = Edits.view(sid, true).filter { it.logo.isBlank() && !it.isVod }
        if (targets.isEmpty()) return@withContext 0

        // 1) logos.json : identifiant de chaîne -> URL du logo
        val logoById = HashMap<String, String>()
        try {
            Http.open("$API/logos.json").use { input ->
                val r = JsonReader(InputStreamReader(input, Charsets.UTF_8))
                r.beginArray()
                while (r.hasNext()) {
                    var ch = ""
                    var url = ""
                    r.beginObject()
                    while (r.hasNext()) {
                        when (r.nextName()) {
                            "channel" -> ch = r.nextStringOrEmpty()
                            "url" -> url = r.nextStringOrEmpty()
                            else -> r.skipValue()
                        }
                    }
                    r.endObject()
                    if (ch.isNotBlank() && url.isNotBlank()) logoById.putIfAbsent(ch.lowercase(), url)
                }
                r.endArray()
            }
        } catch (_: Exception) {
        }

        // 2) channels.json : noms et noms alternatifs -> identifiant
        val idByName = HashMap<String, String>()
        Http.open("$API/channels.json").use { input ->
            val r = JsonReader(InputStreamReader(input, Charsets.UTF_8))
            r.beginArray()
            while (r.hasNext()) {
                var id = ""
                var logo = ""
                val names = ArrayList<String>()
                r.beginObject()
                while (r.hasNext()) {
                    when (r.nextName()) {
                        "id" -> id = r.nextStringOrEmpty()
                        "name" -> names.add(r.nextStringOrEmpty())
                        "logo" -> logo = r.nextStringOrEmpty()
                        "alt_names" -> {
                            if (r.peek() == android.util.JsonToken.BEGIN_ARRAY) {
                                r.beginArray()
                                while (r.hasNext()) names.add(r.nextStringOrEmpty())
                                r.endArray()
                            } else r.skipValue()
                        }
                        else -> r.skipValue()
                    }
                }
                r.endObject()
                if (id.isBlank()) continue
                val lid = id.lowercase()
                if (logo.isNotBlank()) logoById.putIfAbsent(lid, logo)
                names.forEach { n -> val k = EpgNorm.norm(n); if (k.isNotEmpty()) idByName.putIfAbsent(k, lid) }
            }
            r.endArray()
        }

        var n = 0
        targets.forEach { c ->
            val id = c.epgId.lowercase().takeIf { logoById.containsKey(it) } ?: idByName[EpgNorm.norm(c.name)]
            val url = id?.let { logoById[it] }
            if (url != null) {
                Edits.setLogoQuiet(sid, c.key, url)
                n++
            }
        }
        if (n > 0) Edits.save(sid)
        n
    }

    private fun JsonReader.nextStringOrEmpty(): String =
        if (peek() == android.util.JsonToken.NULL) { nextNull(); "" } else nextString()
}
