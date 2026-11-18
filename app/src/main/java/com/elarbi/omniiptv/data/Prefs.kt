package com.elarbi.omniiptv.data

import android.content.Context
import android.content.SharedPreferences
import com.elarbi.omniiptv.model.Channel

/** Réglages de l'application (démarrage, lecteur, contrôle parental, mises à jour). */
object Prefs {
    private val p: SharedPreferences get() = Repo.app.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val adultRe = Regex("(adult|adulte|xxx|\\+18|18\\+|porn|erotic|érotique)", RegexOption.IGNORE_CASE)

    /** Vue d'ouverture : rows, list, grid, guide, multi. */
    var startView: String
        get() = p.getString("startView", "rows") ?: "rows"
        set(v) = p.edit().putString("startView", v).apply()

    var autoPlayLast: Boolean
        get() = p.getBoolean("autoPlayLast", false)
        set(v) = p.edit().putBoolean("autoPlayLast", v).apply()

    var refreshOnStart: Boolean
        get() = p.getBoolean("refreshOnStart", false)
        set(v) = p.edit().putBoolean("refreshOnStart", v).apply()

    /** Tampon : low, normal, high. */
    var buffer: String
        get() = p.getString("buffer", "normal") ?: "normal"
        set(v) = p.edit().putString("buffer", v).apply()

    /** Mode tunnel (lecture vidéo confiée au téléviseur). */
    var tunneling: Boolean
        get() = p.getBoolean("tunneling", false)
        set(v) = p.edit().putBoolean("tunneling", v).apply()

    /** Actualisation automatique des listes : 0 = jamais (liste conservée), sinon tous les N jours. */
    var autoRefreshDays: Int
        get() = p.getInt("autoRefreshDays", 0)
        set(v) = p.edit().putInt("autoRefreshDays", v).apply()

    var autoReconnect: Boolean
        get() = p.getBoolean("autoReconnect", true)
        set(v) = p.edit().putBoolean("autoReconnect", v).apply()

    /** 0 adapter, 1 zoom, 2 étirer. */
    var aspect: Int
        get() = p.getInt("aspect", 0)
        set(v) = p.edit().putInt("aspect", v).apply()

    /** Interface : auto, tv, phone. */
    var uiMode: String
        get() = p.getString("uiMode", "auto") ?: "auto"
        set(v) = p.edit().putString("uiMode", v).apply()

    /** Sources affichées ensemble (vide = la source active seule). */
    var shownSources: Set<String>
        get() = p.getStringSet("shownSources", emptySet())?.toSet() ?: emptySet()
        set(v) { p.edit().putStringSet("shownSources", v).apply(); Repo.bump() }

    var themeBg: String
        get() = p.getString("themeBg", "night") ?: "night"
        set(v) = p.edit().putString("themeBg", v).apply()

    var themeAccent: String
        get() = p.getString("themeAccent", "orange") ?: "orange"
        set(v) = p.edit().putString("themeAccent", v).apply()

    /** Portail / source choisi dans la médiathèque (vide = sources affichées). */
    var browseSource: String
        get() = p.getString("browseSource", "") ?: ""
        set(v) = p.edit().putString("browseSource", v).apply()

    var showClock: Boolean
        get() = p.getBoolean("showClock", true)
        set(v) = p.edit().putBoolean("showClock", v).apply()

    // ---------- Contrôle parental ----------
    var pin: String
        get() = p.getString("pin", "") ?: ""
        set(v) = p.edit().putString("pin", v).apply()

    var hideAdult: Boolean
        get() = p.getBoolean("hideAdult", false)
        set(v) { p.edit().putBoolean("hideAdult", v).apply(); Repo.bump() }

    var lockedGroups: Set<String>
        get() = p.getStringSet("lockedGroups", emptySet())?.toSet() ?: emptySet()
        set(v) = p.edit().putStringSet("lockedGroups", v).apply()

    /** Déverrouillage valable jusqu'à la fermeture de l'application. */
    @Volatile
    var unlocked = false

    fun isAdultGroup(group: String) = adultRe.containsMatchIn(group)

    fun isLocked(ch: Channel): Boolean =
        pin.isNotBlank() && !unlocked && (ch.group in lockedGroups || isAdultGroup(ch.group))

    fun isHiddenAdult(ch: Channel): Boolean = hideAdult && isAdultGroup(ch.group)
}
