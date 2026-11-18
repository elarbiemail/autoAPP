package com.elarbi.omniiptv.model

import org.json.JSONObject

enum class SourceType { M3U, XTREAM, STALKER, YOUTUBE, TUNER }

data class Source(
    val id: String,
    val type: SourceType,
    val name: String,
    val url: String,
    val username: String = "",
    val password: String = "",
    val mac: String = "",
    val epgUrl: String = "",
    val userAgent: String = ""
) {
    val typeLabel: String
        get() = when (type) {
            SourceType.M3U -> "Liste M3U"
            SourceType.XTREAM -> "Xtream Codes"
            SourceType.STALKER -> "Portail MAC (Stalker)"
            SourceType.YOUTUBE -> "YouTube Live"
            SourceType.TUNER -> "TNT / satellite / câble du téléviseur"
        }

    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("type", type.name).put("name", name).put("url", url)
        .put("username", username).put("password", password).put("mac", mac)
        .put("epgUrl", epgUrl).put("userAgent", userAgent)

    companion object {
        fun fromJson(o: JSONObject) = Source(
            o.getString("id"), SourceType.valueOf(o.getString("type")), o.optString("name"),
            o.optString("url"), o.optString("username"), o.optString("password"),
            o.optString("mac"), o.optString("epgUrl"), o.optString("userAgent")
        )
    }
}

data class Channel(
    val key: String,
    val sourceId: String,
    val name: String,
    val url: String,
    val logo: String = "",
    val group: String = "",
    val epgId: String = "",
    val userAgent: String = "",
    val referer: String = "",
    val isVod: Boolean = false,
    /** Jours de replay disponibles (Xtream tv_archive), 0 si aucun. */
    val archive: Int = 0
) {
    /** Clé utilisée pour retrouver les programmes dans le guide TV. */
    val epgKey: String
        get() = if (epgId.isNotBlank()) epgId.lowercase() else "name:" + name.lowercase().trim()

    fun toJson(): JSONObject = JSONObject()
        .put("k", key).put("s", sourceId).put("n", name).put("u", url).put("l", logo)
        .put("g", group).put("e", epgId).put("a", userAgent).put("r", referer).put("v", isVod)
        .apply { if (archive > 0) put("c", archive) }

    companion object {
        fun fromJson(o: JSONObject) = Channel(
            o.getString("k"), o.optString("s"), o.optString("n"), o.optString("u"), o.optString("l"),
            o.optString("g"), o.optString("e"), o.optString("a"), o.optString("r"), o.optBoolean("v"),
            o.optInt("c")
        )
    }
}

/** Entrée « série » (épisodes chargés à l'ouverture) : Xtream ou portail Stalker. */
val Channel.isSeries: Boolean
    get() = url.startsWith("xtseries:") || url.startsWith("stkseries:") || url.startsWith("grpseries:")

/** Épisode rangé dans la liste de la source (affiché dans sa fiche série, pas dans Films). */
val Channel.isEpisode: Boolean get() = key.contains("|E") || group.startsWith("Épisodes · ")

/** Film : contenu à la demande qui n'est ni une série ni un épisode. */
val Channel.isMovie: Boolean get() = isVod && !isSeries && !isEpisode

data class Programme(val start: Long, val end: Long, val title: String, val desc: String)

data class ActionItem(val id: Int, val title: String, val subtitle: String, val icon: Int)
