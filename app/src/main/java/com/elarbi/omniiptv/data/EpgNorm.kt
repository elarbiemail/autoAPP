package com.elarbi.omniiptv.data

import java.text.Normalizer

/** Normalisation des noms de chaînes pour rapprocher liste IPTV, guide TV et logos. */
object EpgNorm {
    private val accents = Regex("\\p{Mn}+")
    private val brackets = Regex("\\[.*?]|\\(.*?\\)")
    private val prefix = Regex("^[a-z]{2,3}\\s*[:|]\\s*")
    private val tags = Regex("\\b(hd|fhd|uhd|sd|4k|hevc|h265|720p|1080p|backup|live)\\b")
    private val nonAlnum = Regex("[^a-z0-9\\u0600-\\u06FF]+")

    fun norm(s: String): String {
        var t = Normalizer.normalize(s.lowercase().trim(), Normalizer.Form.NFD).replace(accents, "")
        t = t.replace(brackets, " ").replace(prefix, "").replace(tags, " ")
        return t.replace(nonAlnum, "")
    }
}
