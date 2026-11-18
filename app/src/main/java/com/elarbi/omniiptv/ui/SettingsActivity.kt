package com.elarbi.omniiptv.ui

import android.app.AlertDialog
import android.os.Bundle
import android.text.InputType
import android.graphics.Color
import android.graphics.Typeface
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.ListView
import androidx.core.content.ContextCompat
import com.elarbi.omniiptv.player.PlayerFactory
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import com.elarbi.omniiptv.data.Backup
import com.elarbi.omniiptv.data.Edits
import com.elarbi.omniiptv.data.Prefs
import com.elarbi.omniiptv.data.Reminders
import com.elarbi.omniiptv.data.Repo
import com.elarbi.omniiptv.data.TunerSource
import com.elarbi.omniiptv.model.SourceType

/**
 * Réglages en deux volets, comme les réglages d'Android TV :
 * à gauche les rubriques, à droite les options de la rubrique (titre + valeur actuelle).
 */
class SettingsActivity : FragmentActivity() {
    private lateinit var box: LinearLayout
    private lateinit var cats: ListView
    private lateinit var paneTitle: TextView
    private lateinit var scroll: ScrollView
    private var cat = 0

    private val categories = listOf(
        "🔌  Sources", "🗂  Listes et guide TV", "▶  Lecture et décodage", "🖥  Affichage et démarrage",
        "🏠  Écran d'accueil du TV", "🎨  Thème", "🔒  Contrôle parental", "⏰  Rappels", "💾  Sauvegarde et aide"
    )

    private val startViews = listOf(
        "home" to "Accueil", "live" to "Direct", "movies" to "Films", "series" to "Séries",
        "fav" to "Mes listes", "guide" to "Guide TV", "multi" to "Multi-écran"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val d = { v: Int -> Ui.dp(this, v) }
        val root = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(d(28), d(22), d(28), d(16)) }
        val left = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        left.addView(TextView(this).apply { text = "⚙ Réglages"; textSize = 28f; setTextColor(Color.WHITE); setTypeface(typeface, Typeface.BOLD) })
        left.addView(TextView(this).apply {
            text = "Omni IPTV ${try { packageManager.getPackageInfo(packageName, 0).versionName } catch (e: Exception) { "" }}"
            setTextColor(HINT); setPadding(0, d(2), 0, d(14))
        })
        cats = ListView(this).apply {
            divider = null
            dividerHeight = d(3)
            selector = ContextCompat.getDrawable(this@SettingsActivity, com.elarbi.omniiptv.R.drawable.bg_item)
            choiceMode = ListView.CHOICE_MODE_SINGLE
            adapter = ArrayAdapter(this@SettingsActivity, com.elarbi.omniiptv.R.layout.item_text, categories)
        }
        left.addView(cats, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(left, LinearLayout.LayoutParams(d(310), ViewGroup.LayoutParams.MATCH_PARENT))

        val right = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(d(30), 0, 0, 0) }
        paneTitle = TextView(this).apply { textSize = 22f; setTextColor(ThemeManager.accent); setTypeface(typeface, Typeface.BOLD); setPadding(0, d(6), 0, d(10)) }
        right.addView(paneTitle)
        scroll = ScrollView(this)
        box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 0, d(8), d(20)) }
        scroll.addView(box)
        right.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(right, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        setContentView(root)

        cat = intent.getIntExtra("cat", 0).coerceIn(0, categories.size - 1)
        cats.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                if (pos != cat) { cat = pos; render() }
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        cats.setOnItemClickListener { _, _, pos, _ -> cat = pos; render(); box.getChildAt(0)?.let { if (!it.isFocusable) box.getChildAt(1) else it }?.requestFocus() }
        cats.setItemChecked(cat, true)
        cats.setSelection(cat)
        render()
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    // ---------------- Lignes d'options ----------------
    private fun row(title: String, value: String, action: () -> Unit) {
        val d = { v: Int -> Ui.dp(this, v) }
        val v = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            isFocusable = true
            isClickable = true
            background = ContextCompat.getDrawable(this@SettingsActivity, com.elarbi.omniiptv.R.drawable.bg_item)
            setPadding(d(16), d(11), d(16), d(11))
            addView(TextView(this@SettingsActivity).apply { text = title; textSize = 18f; setTextColor(Color.WHITE) })
            if (value.isNotBlank()) addView(TextView(this@SettingsActivity).apply { text = value; textSize = 14f; setTextColor(HINT) })
            setOnClickListener {
                val idx = box.indexOfChild(this)
                action()
                if (!isFinishing) { render(); box.getChildAt(idx)?.requestFocus() }
            }
        }
        box.addView(v, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = d(4) })
    }

    private fun toggle(title: String, value: Boolean, desc: String = "", set: (Boolean) -> Unit) =
        row(title, (if (value) "✅ Activé" else "⬜ Désactivé") + if (desc.isNotBlank()) "  —  $desc" else "") { set(!value) }

    private fun choice(title: String, options: List<Pair<String, String>>, current: String, set: (String) -> Unit) {
        val label = options.firstOrNull { it.first == current }?.second ?: options.first().second
        row(title, label) {
            val i = options.indexOfFirst { it.first == current }.coerceAtLeast(0)
            AlertDialog.Builder(this).setTitle(title)
                .setSingleChoiceItems(options.map { it.second }.toTypedArray(), i) { dlg, w -> set(options[w].first); dlg.dismiss(); render() }
                .setNegativeButton("Annuler", null).show()
        }
    }

    private fun link(title: String, desc: String = "", action: () -> Unit) = row("$title  ›", desc, action)

    private fun note(t: String) = box.addView(TextView(this).apply {
        text = t; textSize = 14f; setTextColor(HINT); setPadding(Ui.dp(this@SettingsActivity, 4), Ui.dp(this@SettingsActivity, 4), 0, Ui.dp(this@SettingsActivity, 8))
    })

    private fun go(cls: Class<*>) = startActivity(android.content.Intent(this, cls))

    private fun hub(section: String) = startActivity(
        android.content.Intent(this, TvHomeActivity::class.java).putExtra("section", section)
            .addFlags(android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP)
    )

    private fun add(t: SourceType) = startActivity(android.content.Intent(this, AddSourceActivity::class.java).putExtra("type", t.name))

    /** Action exécutée par l'accueil de l'application (actualisation, rangées, tuner…). */
    private fun main(id: Int) = AppActions.run(this, id) { tunerPermission.launch(TunerSource.permissions) }

    private val tunerPermission = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()
    ) { _ -> AppActions.loadTuner(this) }

    // ---------------- Rubriques ----------------
    private fun render() {
        box.removeAllViews()
        paneTitle.text = categories[cat].substringAfter("  ")
        when (cat) {
            0 -> sources()
            1 -> lists()
            2 -> playback()
            3 -> display()
            4 -> homeScreen()
            5 -> theme()
            6 -> parental()
            7 -> reminders()
            else -> backupHelp()
        }
    }

    private fun sources() {
        note("${Repo.sources.size} source(s) : ${Repo.sources.joinToString { it.name }.ifBlank { "aucune" }}")
        link("Gérer les sources", "Ajouter, modifier, supprimer, actualiser, inclure dans « Toutes les sources »") { hub("SOURCES") }
        link("＋ Portail MAC (Stalker / Ministra)") { add(SourceType.STALKER) }
        link("＋ Compte Xtream Codes") { add(SourceType.XTREAM) }
        link("＋ Liste M3U / M3U8") { add(SourceType.M3U) }
        link("✎ Modifier un serveur", "Adresse, identifiants, adresse MAC, modèle de boîtier") { SourcePicker.editOne(this) }
        link("Chaînes YouTube Live") { go(YoutubeActivity::class.java) }
        link("Chaînes du téléviseur", "TNT, satellite, câble enregistrées sur le téléviseur") { main(MainFragment.A_TUNER) }
        link("Entrées du téléviseur", "HDMI, antenne, satellite…") { tvInputs() }
        link("Dépôts", "Installer des listes en un clic") { go(ReposActivity::class.java) }
        link("Listes gratuites et légales") { go(FreeSearchActivity::class.java) }
    }

    private fun lists() {
        link("Gestion des listes", "Catégories et chaînes de chaque source : masquer, renommer, ordonner, verrouiller") { hub("LISTS") }
        link("Mes listes de favoris", "Plusieurs listes avec des noms personnalisés") { hub("FAV") }
        link("Outils avancés", "Logos et identifiants de guide TV chaîne par chaîne") { go(ChannelManagerActivity::class.java) }
        link("Guides TV (EPG)", "Guide automatique de chaque source et guides supplémentaires") { go(EpgActivity::class.java) }
        choice("Actualisation automatique des listes",
            listOf("0" to "Jamais (liste enregistrée conservée)", "1" to "Chaque jour", "3" to "Tous les 3 jours", "7" to "Chaque semaine"),
            Prefs.autoRefreshDays.toString()) { Prefs.autoRefreshDays = it.toInt() }
        toggle("Actualiser la source au démarrage", Prefs.refreshOnStart) { Prefs.refreshOnStart = it }
        link("Actualiser la source affichée", "Chaînes et guide TV") { main(MainFragment.A_REFRESH) }
        link("Réinitialiser les personnalisations de la source affichée") {
            Repo.activeSourceId?.let { Edits.reset(it) }
            Ui.toast(this, "Personnalisations réinitialisées")
        }
    }

    private fun playback() {
        note(PlayerFactory.describe(this))
        choice("Mémoire tampon", listOf(
            "auto" to "Automatique (selon la mémoire de l'appareil) — recommandé",
            "low" to "Zapping rapide : démarre après 0,5 s, tampon 3–15 s",
            "normal" to "Équilibré : démarre après 1 s, tampon 10–40 s",
            "high" to "Connexion instable : démarre après 2 s, tampon 25–90 s",
            "max" to "Connexion très instable : démarre après 3 s, tampon 50–180 s"
        ), Prefs.buffer) { Prefs.buffer = it }
        choice("Décodage", listOf(
            "auto" to "Automatique : matériel, avec secours logiciel (FFmpeg) si le téléviseur ne sait pas lire le son",
            "hw" to "Matériel seulement (le plus économe)",
            "sw" to "Son décodé par FFmpeg en priorité (AC3, E-AC3, DTS, MP2 : à essayer si pas de son)"
        ), Prefs.decoder) { Prefs.decoder = it }
        choice("Résolution maximale", listOf(
            "0" to "Sans limite", "2160" to "4K (2160p)", "1080" to "Full HD (1080p)", "720" to "HD (720p) — appareils modestes",
            "576" to "SD (576p) — connexion faible"
        ), Prefs.maxHeight.toString()) { Prefs.maxHeight = it.toInt() }
        choice("Langue audio préférée", listOf(
            "" to "Langue du téléviseur", "fr" to "Français", "ar" to "Arabe", "en" to "Anglais", "es" to "Espagnol",
            "de" to "Allemand", "it" to "Italien", "tr" to "Turc", "pt" to "Portugais"
        ), Prefs.audioLang) { Prefs.audioLang = it }
        toggle("Mode tunnel", Prefs.tunneling, "image confiée au téléviseur, plus fluide sur certains modèles") { Prefs.tunneling = it }
        toggle("Reconnexion automatique en cas de coupure", Prefs.autoReconnect) { Prefs.autoReconnect = it }
        choice("Format d'image par défaut", listOf("0" to "Adapter", "1" to "Zoom (remplir l'écran)", "2" to "Étirer"),
            Prefs.aspect.toString()) { Prefs.aspect = it.toInt() }
        toggle("Aperçu vidéo dans Direct", Prefs.livePreview, "la chaîne sélectionnée joue dans le panneau de droite") { Prefs.livePreview = it }
        toggle("Heure dans le bandeau du lecteur", Prefs.showClock) { Prefs.showClock = it }
        note("Les changements s'appliquent à la prochaine chaîne ouverte.")
    }

    private fun display() {
        val uiLabels = listOf("auto" to "Automatique", "tv" to "Téléviseur", "phone" to "Téléphone / tablette")
        choice("Interface", uiLabels, Prefs.uiMode) {
            Prefs.uiMode = it
            startActivity(android.content.Intent(this, MainActivity::class.java)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK))
        }
        val sv = when (Prefs.startView) { "rows" -> "home"; "list", "grid" -> "live"; else -> Prefs.startView }
        choice("À l'ouverture de l'application", startViews, sv) { Prefs.startView = it }
        toggle("Lancer la dernière chaîne regardée", Prefs.autoPlayLast) { Prefs.autoPlayLast = it }
        link("Guide TV en grille") { go(GuideActivity::class.java) }
        link("Multi-écran (4 chaînes)") { go(MultiViewActivity::class.java) }
    }

    private fun homeScreen() {
        note("Rangées d'Omni IPTV sur l'accueil Google TV : nom, contenu, nombre d'éléments et présentation au choix. " +
            "L'ordre des rangées sur l'accueil se règle aussi dans le téléviseur (Accueil > Personnaliser les chaînes).")
        for (r in com.elarbi.omniiptv.home.HomeRows.rows(this)) {
            row((if (r.enabled) "✅  " else "⬜  ") + r.name,
                "${com.elarbi.omniiptv.home.HomeRows.kindLabel(r)}  •  ${r.max} éléments  •  " +
                    if (r.style == "channel") "nom et logo de la chaîne" else "programme en cours") { homeRowMenu(r) }
        }
        link("＋ Ajouter une rangée sur l'accueil") {
            chooseHomeContent { kind, arg, name ->
                val r = com.elarbi.omniiptv.home.HomeRows.add(this, kind, arg, name)
                syncHome(showRow = r)
            }
        }
        link("Mettre à jour l'accueil maintenant") { syncHome() }
        link("Publier les chaînes dans l'onglet Direct", "Chaînes et programmes dans l'onglet Direct de Google TV") { main(MainFragment.A_LIVE_TAB) }
    }

    /** Met à jour l'accueil ; si demandé, propose d'y afficher la rangée (le téléviseur demande confirmation). */
    private fun syncHome(showRow: com.elarbi.omniiptv.home.HomeRows.Row? = null) {
        Ui.toast(this, "Mise à jour de l'accueil du téléviseur…")
        lifecycleScope.launch {
            val id = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                try { com.elarbi.omniiptv.home.HomeScreen.sync(this@SettingsActivity) } catch (_: Throwable) {}
                showRow?.let { r -> com.elarbi.omniiptv.home.HomeScreen.ensureChannel(this@SettingsActivity, r.id, r.name, false) }
            }
            if (showRow != null) {
                if (id == null) Ui.toast(this@SettingsActivity, "Cet appareil ne gère pas les rangées d'accueil.")
                else try {
                    startActivity(android.content.Intent(androidx.tvprovider.media.tv.TvContractCompat.ACTION_REQUEST_CHANNEL_BROWSABLE)
                        .putExtra(androidx.tvprovider.media.tv.TvContractCompat.EXTRA_CHANNEL_ID, id))
                } catch (e: Exception) {
                    Ui.toast(this@SettingsActivity, "Ajoutez la rangée depuis l'accueil : Personnaliser les chaînes > Omni IPTV.")
                }
            }
            render()
        }
    }

    private fun homeRowMenu(r: com.elarbi.omniiptv.home.HomeRows.Row) {
        val H = com.elarbi.omniiptv.home.HomeRows
        val opts = ArrayList<Pair<String, () -> Unit>>()
        opts += "✎ Renommer" to {
            val e = EditText(this).apply { setText(r.name); setTextColor(Color.WHITE) }
            AlertDialog.Builder(this).setTitle("Nom de la rangée").setView(e)
                .setPositiveButton("OK") { _, _ -> e.text.toString().trim().takeIf { it.isNotEmpty() }?.let { H.update(this, r.copy(name = it.take(40))); syncHome() } }
                .setNegativeButton("Annuler", null).show()
        }
        opts += "Contenu : ${H.kindLabel(r)}" to { chooseHomeContent { k, a, _ -> H.update(this, r.copy(kind = k, arg = a)); syncHome() } }
        opts += "Nombre d'éléments : ${r.max}" to {
            val n = listOf(10, 20, 30, 40, 60)
            AlertDialog.Builder(this).setTitle("Nombre d'éléments")
                .setSingleChoiceItems(n.map { "$it" }.toTypedArray(), n.indexOf(r.max)) { d, w -> d.dismiss(); H.update(this, r.copy(max = n[w])); syncHome() }
                .show()
        }
        opts += "Présentation : " + (if (r.style == "channel") "nom et logo de la chaîne" else "programme en cours (vignette composée)") to {
            H.update(this, r.copy(style = if (r.style == "channel") "program" else "channel")); syncHome()
        }
        opts += (if (r.enabled) "⬜ Retirer de l'accueil" else "✅ Afficher sur l'accueil") to {
            val n = r.copy(enabled = !r.enabled); H.update(this, n); syncHome(showRow = if (n.enabled) n else null)
        }
        if (r.enabled) opts += "Demander l'affichage sur l'accueil" to { syncHome(showRow = r) }
        opts += "↑ Monter" to { H.move(this, r.id, -1); render() }
        opts += "↓ Descendre" to { H.move(this, r.id, 1); render() }
        opts += "🗑 Supprimer la rangée" to {
            lifecycleScope.launch {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { H.remove(this@SettingsActivity, r.id) }
                render()
            }
        }
        AlertDialog.Builder(this).setTitle(r.name)
            .setItems(opts.map { it.first }.toTypedArray()) { _, w -> opts[w].second() }
            .setNegativeButton("Fermer", null).show()
    }

    /** Choix du contenu d'une rangée : listes de favoris, reprise, récentes, en ce moment, catégorie d'une source. */
    private fun chooseHomeContent(done: (String, String, String) -> Unit) {
        val choices = ArrayList<Triple<String, String, String>>()   // kind, arg, nom proposé
        choices += Triple("main", "", "Omni IPTV")
        com.elarbi.omniiptv.data.FavLists.lists().forEach { choices += Triple("fav", it.id, "Omni · ${it.name}") }
        choices += Triple("resume", "", "Omni · Continuer à regarder")
        choices += Triple("watch", "", "Omni · À regarder")
        choices += Triple("recent", "", "Omni · Récentes")
        choices += Triple("now", "", "Omni · En ce moment")
        val labels = listOf("Mélange : reprise, favoris, à regarder") +
            com.elarbi.omniiptv.data.FavLists.lists().map { "★ Liste « ${it.name} »" } +
            listOf("▶ Continuer à regarder", "À regarder", "🕘 Regardées récemment", "📺 En ce moment (programmes en cours)", "🗂 Une catégorie d'une source…")
        AlertDialog.Builder(this).setTitle("Contenu de la rangée")
            .setItems(labels.toTypedArray()) { _, w ->
                if (w < choices.size) { val c = choices[w]; done(c.first, c.second, c.third); return@setItems }
                val srcs = Repo.sources.filter { it.type != SourceType.YOUTUBE }
                if (srcs.isEmpty()) return@setItems
                AlertDialog.Builder(this).setTitle("Source")
                    .setItems(srcs.map { it.name }.toTypedArray()) { _, i ->
                        val src = srcs[i]
                        Ui.toast(this, "Lecture des catégories…")
                        lifecycleScope.launch {
                            val groups = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                                try { Edits.groups(src.id) } catch (e: Throwable) { emptyList() }
                            }
                            if (groups.isEmpty()) { Ui.toast(this@SettingsActivity, "Aucune catégorie : actualisez d'abord la source."); return@launch }
                            AlertDialog.Builder(this@SettingsActivity).setTitle("Catégorie de ${src.name}")
                                .setItems(groups.toTypedArray()) { _, g ->
                                    done("group", src.id + "\u0001" + groups[g], "Omni · " + groups[g].removePrefix("Films · ").removePrefix("Séries · "))
                                }.show()
                        }
                    }.show()
            }.show()
    }

    private fun theme() {
        val preset = ThemeManager.presets.firstOrNull { it.second == Prefs.themeBg && it.third == Prefs.themeAccent }?.first ?: "personnalisé"
        row("Thème prêt à l'emploi", preset) { themePresets() }
        row("Couleur de fond", ThemeManager.bg().label) { themePick(true) }
        row("Couleur d'accent", ThemeManager.accentDef().label) { themePick(false) }
    }

    private fun parental() {
        row(if (Prefs.pin.isBlank()) "Définir un code parental" else "Changer ou supprimer le code parental",
            if (Prefs.pin.isBlank()) "Aucun code" else "Code défini") { pinDialog() }
        toggle("Masquer les catégories pour adultes", Prefs.hideAdult) { Prefs.hideAdult = it; Repo.bump() }
        row("Groupes verrouillés", "${Prefs.lockedGroups.size} groupe(s) — les groupes adultes le sont d'office avec un code") { lockGroups() }
    }

    private fun reminders() {
        val rem = Reminders.list()
        if (rem.isEmpty()) note("Aucun rappel. Programmez-en depuis le Guide TV (OK sur un programme à venir).")
        rem.sortedBy { it.start }.forEach { r ->
            row("⏰ ${Ui.hm(r.start)} — ${r.title}", "${r.channelName} • OK pour annuler") { Reminders.toggle(this, r) }
        }
    }

    private fun backupHelp() {
        link("Exporter la configuration", "Fichier dans Téléchargements (contient vos identifiants)") {
            try {
                val where = Backup.export(this)
                AlertDialog.Builder(this).setTitle("Sauvegarde enregistrée")
                    .setMessage("Fichier : $where\n\nPour restaurer : Sources > Dépôts, puis saisir le chemin complet du fichier " +
                        "(ex. /storage/emulated/0/Download/omni-iptv-sauvegarde.json) ou son adresse en ligne.\n" +
                        "Le fichier contient vos identifiants : ne le publiez pas.")
                    .setPositiveButton("OK", null).show()
            } catch (e: Exception) {
                Ui.toast(this, "Échec : ${e.message}")
            }
        }
        link("Raccourcis de la télécommande et mode d'emploi") { main(MainFragment.A_ABOUT) }
        val journal = com.elarbi.omniiptv.data.CrashLog.journal()
        link("Journal des erreurs", if (journal.isBlank()) "Aucune erreur enregistrée" else "${journal.lines().count { it.startsWith("===") }} erreur(s) enregistrée(s) — à photographier pour la correction") {
            val tv = TextView(this).apply {
                text = journal.ifBlank { "Aucune erreur enregistrée." }.takeLast(12_000)
                textSize = 11f; setTextColor(Color.WHITE); setPadding(Ui.dp(this@SettingsActivity, 14), 0, Ui.dp(this@SettingsActivity, 14), 0)
            }
            AlertDialog.Builder(this).setTitle("Journal des erreurs")
                .setView(ScrollView(this).apply { addView(tv) })
                .setPositiveButton("Fermer", null)
                .setNeutralButton("Effacer") { _, _ -> com.elarbi.omniiptv.data.CrashLog.clear(); render() }
                .show()
        }
        note(com.elarbi.omniiptv.data.CrashLog.device())
    }

    /** Liste des entrées du téléviseur : HDMI affiché dans l'application, tuner via l'application TV. */
    private fun tvInputs() {
        val inputs = TunerSource.inputs(this)
        if (inputs.isEmpty()) { Ui.toast(this, "Aucune entrée TV détectée sur cet appareil."); return }
        val labels = inputs.map { "${it.label}  ·  ${it.kind}" } + "Ouvrir l'application TV du téléviseur"
        AlertDialog.Builder(this).setTitle("Entrées du téléviseur")
            .setItems(labels.toTypedArray()) { _, w ->
                if (w == inputs.size) { if (!TunerSource.openTvApp(this)) Ui.toast(this, "Application TV introuvable."); return@setItems }
                val i = inputs[w]
                if (i.passthrough) {
                    val ch = TunerSource.inputChannel(i)
                    com.elarbi.omniiptv.data.Repo.playContext = listOf(ch)
                    startActivity(android.content.Intent(this, PlayerActivity::class.java).putExtra("key", ch.key))
                } else {
                    AlertDialog.Builder(this).setTitle(i.label)
                        .setMessage(
                            "Android réserve l'image du tuner intégré (TNT, satellite, câble) à l'application TV du téléviseur : " +
                                "une application tierce ne peut pas l'afficher elle-même.\n\n" +
                                "Omni IPTV peut lister ces chaînes avec leurs programmes (« Chaînes du téléviseur ») et basculer " +
                                "sur la chaîne choisie dans l'application TV."
                        )
                        .setPositiveButton("Ouvrir l'application TV") { _, _ -> TunerSource.openTvApp(this) }
                        .setNegativeButton("Fermer", null).show()
                }
            }.show()
    }

    private fun restartForTheme() {
        Ui.toast(this, "Thème appliqué")
        startActivity(android.content.Intent(this, MainActivity::class.java)
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK))
        startActivity(android.content.Intent(this, SettingsActivity::class.java))
        com.elarbi.omniiptv.home.HomeSyncWorker.runNow(this)
    }

    private fun themePresets() {
        val p = ThemeManager.presets
        val labels = p.map { (name, bg, ac) ->
            "$name — ${ThemeManager.backgrounds.first { it.id == bg }.label} / ${ThemeManager.accents.first { it.id == ac }.label}"
        }
        AlertDialog.Builder(this).setTitle("Thèmes")
            .setItems(labels.toTypedArray()) { _, w ->
                Prefs.themeBg = p[w].second
                Prefs.themeAccent = p[w].third
                restartForTheme()
            }.show()
    }

    private fun themePick(background: Boolean) {
        val labels = if (background) ThemeManager.backgrounds.map { it.label } else ThemeManager.accents.map { it.label }
        val adapter = object : android.widget.ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, labels) {
            override fun getView(position: Int, convertView: android.view.View?, parent: android.view.ViewGroup): android.view.View {
                val v = super.getView(position, convertView, parent) as TextView
                val color = if (background) ThemeManager.backgrounds[position].brand else ThemeManager.accents[position].color
                val dot = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.OVAL
                    setColor(color)
                    setStroke(2, 0x66FFFFFF.toInt())
                    setSize(Ui.dp(this@SettingsActivity, 22), Ui.dp(this@SettingsActivity, 22))
                }
                v.setCompoundDrawablesWithIntrinsicBounds(dot, null, null, null)
                v.compoundDrawablePadding = Ui.dp(this@SettingsActivity, 14)
                return v
            }
        }
        AlertDialog.Builder(this).setTitle(if (background) "Couleur de fond" else "Couleur d'accent")
            .setAdapter(adapter) { _, w ->
                if (background) Prefs.themeBg = ThemeManager.backgrounds[w].id else Prefs.themeAccent = ThemeManager.accents[w].id
                restartForTheme()
            }.show()
    }

    private fun pinDialog() {
        val e = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            hint = "Nouveau code (vide = supprimer)"
            setTextColor(0xFFFFFFFF.toInt())
        }
        val doApply = {
            AlertDialog.Builder(this).setTitle("Code parental").setView(e)
                .setPositiveButton("Enregistrer") { _, _ ->
                    Prefs.pin = e.text.toString().trim()
                    Prefs.unlocked = false
                    Ui.toast(this, if (Prefs.pin.isBlank()) "Code supprimé" else "Code enregistré")
                    render()
                }
                .setNegativeButton("Annuler", null).show()
            Unit
        }
        if (Prefs.pin.isBlank()) doApply() else Ui.askPin(this) { doApply() }
    }

    private fun lockGroups() {
        val src = Repo.activeSource() ?: run { Ui.toast(this, "Aucune source"); return }
        val groups = Edits.groups(src.id)
        val locked = Prefs.lockedGroups.toMutableSet()
        val checked = BooleanArray(groups.size) { groups[it] in locked }
        val go = {
            AlertDialog.Builder(this).setTitle("Groupes à verrouiller")
                .setMultiChoiceItems(groups.toTypedArray(), checked) { _, w, on -> if (on) locked.add(groups[w]) else locked.remove(groups[w]) }
                .setPositiveButton("Enregistrer") { _, _ ->
                    Prefs.lockedGroups = locked
                    if (Prefs.pin.isBlank()) Ui.toast(this, "Pensez à définir un code parental")
                    render()
                }
                .setNegativeButton("Annuler", null).show()
            Unit
        }
        if (Prefs.pin.isBlank()) go() else Ui.askPin(this) { go() }
    }

    companion object {
        private const val HINT = 0x99FFFFFF.toInt()
    }
}
