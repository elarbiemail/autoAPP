package com.elarbi.omniiptv.ui

import android.app.Activity
import android.graphics.Color
import com.elarbi.omniiptv.R
import com.elarbi.omniiptv.data.Prefs

/** Gestionnaire de thèmes : couleur de fond et couleur d'accent, appliquées à tous les écrans. */
object ThemeManager {
    data class Bg(val id: String, val label: String, val style: Int, val bg: Int, val brand: Int)
    data class Accent(val id: String, val label: String, val style: Int, val color: Int)

    val backgrounds = listOf(
        Bg("night", "Nuit bleue", R.style.Bg_Night, Color.parseColor("#0B1320"), Color.parseColor("#123A5A")),
        Bg("black", "Noir profond (OLED)", R.style.Bg_Black, Color.parseColor("#000000"), Color.parseColor("#161616")),
        Bg("slate", "Ardoise", R.style.Bg_Slate, Color.parseColor("#1C1F24"), Color.parseColor("#2E3440")),
        Bg("ocean", "Océan", R.style.Bg_Ocean, Color.parseColor("#04232B"), Color.parseColor("#0B4F5C")),
        Bg("forest", "Forêt", R.style.Bg_Forest, Color.parseColor("#0E1D14"), Color.parseColor("#1E4D2B")),
        Bg("plum", "Prune", R.style.Bg_Plum, Color.parseColor("#1A0F24"), Color.parseColor("#3D1F5C")),
        Bg("desert", "Désert", R.style.Bg_Desert, Color.parseColor("#1F1710"), Color.parseColor("#5A3D1E"))
    )

    val accents = listOf(
        Accent("orange", "Orange", R.style.Accent_Orange, Color.parseColor("#FF9F1C")),
        Accent("sky", "Bleu ciel", R.style.Accent_Sky, Color.parseColor("#4FC3F7")),
        Accent("green", "Vert", R.style.Accent_Green, Color.parseColor("#66BB6A")),
        Accent("pink", "Rose", R.style.Accent_Pink, Color.parseColor("#F06292")),
        Accent("yellow", "Jaune", R.style.Accent_Yellow, Color.parseColor("#FFD54F")),
        Accent("red", "Rouge", R.style.Accent_Red, Color.parseColor("#EF5350")),
        Accent("violet", "Violet", R.style.Accent_Violet, Color.parseColor("#B388FF")),
        Accent("cyan", "Cyan", R.style.Accent_Cyan, Color.parseColor("#26C6DA"))
    )

    /** Thèmes prêts à l'emploi (fond + accent). */
    val presets = listOf(
        Triple("Classique", "night", "orange"),
        Triple("Cinéma", "black", "red"),
        Triple("Lagon", "ocean", "cyan"),
        Triple("Nature", "forest", "green"),
        Triple("Néon", "plum", "pink"),
        Triple("Sobre", "slate", "sky"),
        Triple("Sahara", "desert", "yellow")
    )

    fun bg(): Bg = backgrounds.firstOrNull { it.id == Prefs.themeBg } ?: backgrounds[0]
    fun accentDef(): Accent = accents.firstOrNull { it.id == Prefs.themeAccent } ?: accents[0]

    val accent: Int get() = accentDef().color
    val brand: Int get() = bg().brand
    val background: Int get() = bg().bg

    /** À appeler avant l'affichage de chaque écran (fait automatiquement par l'application). */
    fun apply(a: Activity) {
        a.theme.applyStyle(bg().style, true)
        a.theme.applyStyle(accentDef().style, true)
        a.window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(background))
    }
}
