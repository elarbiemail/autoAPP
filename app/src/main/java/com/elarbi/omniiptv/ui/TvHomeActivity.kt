package com.elarbi.omniiptv.ui

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridView
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.elarbi.omniiptv.R
import com.elarbi.omniiptv.data.Edits
import com.elarbi.omniiptv.data.EpgNorm
import com.elarbi.omniiptv.data.FavLists
import com.elarbi.omniiptv.data.Library
import com.elarbi.omniiptv.data.Prefs
import com.elarbi.omniiptv.data.Repo
import com.elarbi.omniiptv.data.StreamResolver
import com.elarbi.omniiptv.data.TunerSource
import com.elarbi.omniiptv.home.HomeSyncWorker
import com.elarbi.omniiptv.model.Channel
import com.elarbi.omniiptv.model.Source
import com.elarbi.omniiptv.model.SourceType
import com.elarbi.omniiptv.model.isSeries
import com.elarbi.omniiptv.model.isMovie
import com.elarbi.omniiptv.player.PlayerFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Interface principale pour téléviseur, sur le modèle des applications IPTV professionnelles :
 * barre de navigation latérale qui se déploie au focus, onglets de sources en haut,
 * Direct en trois colonnes (catégories, chaînes, aperçu + guide), Films et Séries en affiches,
 * listes de favoris multiples, gestionnaire de listes et gestion des sources.
 */
class TvHomeActivity : FragmentActivity() {

    private enum class Sec(val icon: String, val label: String, val opens: Boolean = false) {
        SEARCH("🔍", "Rechercher", true),
        HOME("🏠", "Accueil"),
        LIVE("📺", "Direct"),
        GUIDE("📅", "Guide TV", true),
        MOVIES("🎬", "Films"),
        SERIES("🎞", "Séries"),
        FAV("★", "Mes listes"),
        LISTS("🗂", "Gestion des listes"),
        SOURCES("🔌", "Sources"),
        SETTINGS("⚙", "Réglages", true)
    }

    private data class Cat(val label: String, val sid: String? = null, val group: String? = null, val special: String? = null)

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var rail: LinearLayout
    private val railItems = LinkedHashMap<Sec, TextView>()
    private lateinit var titleView: TextView
    private lateinit var chipsBox: LinearLayout
    private lateinit var clock: TextView
    private lateinit var status: TextView
    private lateinit var content: FrameLayout
    private var sec = Sec.HOME
    private var railExpanded = false
    private var builtVersion = -1
    private var lastBack = 0L
    /** Listes préparées par source (calculées en arrière-plan, jamais sur le fil de l'écran). */
    private val viewCache = java.util.concurrent.ConcurrentHashMap<String, List<Channel>>()
    private val fullCache = java.util.concurrent.ConcurrentHashMap<String, List<Channel>>()
    /** Liste de Repo d'où vient chaque entrée de viewCache (pour savoir si une actualisation l'a remplacée). */
    private val viewOrigin = java.util.concurrent.ConcurrentHashMap<String, Any>()
    private var loadingSec: Sec? = null
    private var safeMode = false

    // Direct
    private var liveShown: List<Channel> = emptyList()
    private var preview: ExoPlayer? = null
    private var previewView: PlayerView? = null
    private var previewKey = ""
    private var pendingPreview: Channel? = null
    private val previewRun = Runnable { startPreview() }
    private var digits = ""
    private val zapRun = Runnable { zapToNumber() }
    private var liveList: ListView? = null

    // Gestion des listes : sélection mémorisée
    private var mgrSource: String? = null
    private var mgrGroup: String? = null
    /** Catégorie en cours de déplacement aux flèches : (section, nom). */
    private var movingGroup: Pair<String, String>? = null
    /** Après « Placer en tête / en fin » : le curseur reste à cette place. */
    private var keepGroupIndex: Int? = null
    private var mgrVisible: List<Pair<String, String>> = emptyList()
    private var mgrGrpList: ListView? = null
    private var swallowOkUp = false

    private val tunerPermission = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()
    ) { _ -> AppActions.loadTuner(this) }

    // ======================================================================
    // Cadre : barre latérale, barre du haut, contenu
    // ======================================================================
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(ThemeManager.background)
        }
        rail = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(darker(ThemeManager.brand))
            setPadding(dp(10), dp(14), dp(10), dp(10))
        }
        rail.addView(TextView(this).apply {
            text = "Omni"
            textSize = 22f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(ThemeManager.accent)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(14))
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        for (s in Sec.values()) {
            val item = TextView(this).apply {
                textSize = 17f
                setTextColor(Color.WHITE)
                isSingleLine = true
                isFocusable = true
                isFocusableInTouchMode = false
                background = ContextCompat.getDrawable(this@TvHomeActivity, R.drawable.bg_rail)
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(14), dp(9), dp(14), dp(9))
                setOnClickListener { select(s, true) }
                setOnFocusChangeListener { _, f ->
                    if (f && !s.opens && s != sec) {
                        handler.removeCallbacks(railSwitch)
                        railTarget = s
                        handler.postDelayed(railSwitch, 450)
                    }
                }
            }
            railItems[s] = item
            rail.addView(item, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(3)
            })
        }
        root.addView(rail, LinearLayout.LayoutParams(dp(76), ViewGroup.LayoutParams.MATCH_PARENT))

        val main = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(14), dp(22), dp(10))
        }
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        titleView = TextView(this).apply {
            textSize = 24f; setTextColor(Color.WHITE); setTypeface(typeface, Typeface.BOLD); setPadding(0, 0, dp(18), 0)
        }
        top.addView(titleView)
        chipsBox = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        top.addView(HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(chipsBox)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        clock = TextView(this).apply { textSize = 20f; setTextColor(Color.WHITE); setPadding(dp(14), 0, 0, 0) }
        top.addView(clock)
        main.addView(top)
        status = TextView(this).apply { textSize = 13f; setTextColor(HINT); setPadding(0, dp(4), 0, dp(8)) }
        main.addView(status)
        content = FrameLayout(this)
        main.addView(content, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(main, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        setContentView(root)

        root.viewTreeObserver.addOnGlobalFocusChangeListener { _, now -> setRailExpanded(now != null && now.parent === rail) }

        // Démarrage précédent interrompu (liste trop grosse, mémoire ou stockage plein) : mode sans échec.
        safeMode = savedInstanceState == null && Prefs.launching
        Prefs.launching = true
        sec = if (safeMode) Sec.SOURCES else initialSection()
        builtVersion = Repo.version
        show(sec)
        handler.post(tick)
        handler.postDelayed({ Prefs.launching = false }, 8000)
        val crash = if (savedInstanceState == null) com.elarbi.omniiptv.data.CrashLog.takePending() else null
        when {
            crash != null -> handler.post { crashDialog(crash) }
            safeMode -> handler.post { safeModeDialog() }
        }
        if (!safeMode && crash == null && savedInstanceState == null) startup()
    }

    /** Cause du dernier plantage : à photographier pour la correction. */
    private fun crashDialog(text: String) {
        val tv = TextView(this).apply {
            this.text = text.lines().take(18).joinToString("\n")
            textSize = 12f; setTextColor(Color.WHITE); setPadding(dp(16), dp(8), dp(16), dp(8)); setTextIsSelectable(true)
        }
        AlertDialog.Builder(this).setTitle("L'application s'est arrêtée la dernière fois")
            .setView(ScrollView(this).apply { addView(tv) })
            .setMessage("Prenez cette fenêtre en photo pour la correction. Le journal complet est dans Réglages > Sauvegarde et aide.")
            .setPositiveButton("Fermer", null)
            .setNeutralButton("Libérer de l'espace") { _, _ -> freeSpace() }
            .show()
    }

    override fun onStop() {
        super.onStop()
        Prefs.launching = false
    }

    private fun safeModeDialog() {
        val st = Repo.storage()
        AlertDialog.Builder(this).setTitle("Démarrage sans échec")
            .setMessage(
                "Omni IPTV s'est fermée lors du dernier démarrage. C'est en général une liste trop volumineuse pour la mémoire " +
                    "du téléviseur, ou un stockage plein.\n\n" +
                    "Espace libre : ${st.free / 1048576} Mo • listes : ${st.lists / 1048576} Mo • guides TV : ${st.guides / 1048576} Mo • " +
                    "images : ${st.images / 1048576} Mo\n\n" +
                    "Dans Sources, choisissez un portail puis « Chaînes seulement (sans films ni séries) » ou « Vider la liste enregistrée ». " +
                    "Vous pouvez aussi libérer de l'espace maintenant."
            )
            .setPositiveButton("Libérer de l'espace") { _, _ -> freeSpace() }
            .setNegativeButton("Fermer", null).show()
    }

    private fun freeSpace() {
        lifecycleScope.launch {
            val before = withContext(Dispatchers.IO) { Repo.storage().free }
            withContext(Dispatchers.IO) { try { Repo.freeSpace() } catch (_: Throwable) {} }
            val after = withContext(Dispatchers.IO) { Repo.storage().free }
            Ui.toast(this@TvHomeActivity, "Espace libéré : ${(after - before).coerceAtLeast(0) / 1048576} Mo. " +
                "Les guides TV seront retéléchargés à la prochaine actualisation.")
            if (sec == Sec.SOURCES) show(Sec.SOURCES)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.getStringExtra("section")?.let { n ->
            Sec.values().firstOrNull { it.name.equals(n, true) }?.let { s -> show(s); focusContent() }
        }
    }

    private fun initialSection(): Sec {
        intent?.getStringExtra("section")?.let { n -> Sec.values().firstOrNull { it.name.equals(n, true) }?.let { return it } }
        return when (Prefs.startView) {
            "list", "grid", "live" -> Sec.LIVE
            "movies" -> Sec.MOVIES
            "series" -> Sec.SERIES
            "fav" -> Sec.FAV
            else -> if (Repo.sources.isEmpty()) Sec.SOURCES else Sec.HOME
        }
    }

    /** Comportement au lancement : actualisation, guide / multi-écran, dernière chaîne. */
    private fun startup() {
        // Mise à jour des rangées de l'accueil du téléviseur : au plus toutes les 30 minutes au lancement.
        if (System.currentTimeMillis() - Prefs.lastHomeSync > 30 * 60_000L) HomeSyncWorker.runNow(this)
        if (intent?.data?.host == "home") return
        if (Prefs.refreshOnStart) Repo.activeSource()?.let { s ->
            Repo.scope.launch { try { Repo.refresh(s) } catch (_: Exception) {} }
        }
        when (Prefs.startView) {
            "guide" -> startActivity(Intent(this, GuideActivity::class.java))
            "multi" -> startActivity(Intent(this, MultiViewActivity::class.java))
        }
        if (Prefs.autoPlayLast) Repo.recents.firstOrNull()?.let { ch -> play(ch, listOf(ch)) }
    }

    private val tick = object : Runnable {
        override fun run() {
            clock.text = SimpleDateFormat("HH:mm", Locale.FRANCE).format(Date())
            handler.postDelayed(this, 20_000)
        }
    }

    private var railTarget: Sec? = null
    private val railSwitch = Runnable { railTarget?.let { if (it != sec) show(it) } }

    private fun setRailExpanded(e: Boolean) {
        if (e == railExpanded) return
        railExpanded = e
        rail.layoutParams = rail.layoutParams.apply { width = dp(if (e) 236 else 76) }
        for ((s, v) in railItems) v.text = if (e) "${s.icon}   ${s.label}" else s.icon
        (rail.getChildAt(0) as TextView).text = if (e) "Omni IPTV" else "Omni"
    }

    private fun select(s: Sec, click: Boolean) {
        handler.removeCallbacks(railSwitch)
        when (s) {
            Sec.SEARCH -> startActivity(Intent(this, SearchActivity::class.java))
            Sec.GUIDE -> startActivity(Intent(this, GuideActivity::class.java))
            Sec.SETTINGS -> startActivity(Intent(this, SettingsActivity::class.java))
            else -> {
                if (s != sec) show(s)
                if (click) focusContent()
            }
        }
    }

    private fun focusContent() {
        handler.post { content.getChildAt(0)?.let { v -> if (!v.requestFocus(View.FOCUS_RIGHT)) v.requestFocus() } }
    }

    private fun show(s: Sec) {
        if (sec == Sec.LIVE && s != Sec.LIVE) stopPreview()
        sec = s
        for ((k, v) in railItems) {
            v.isActivated = k == s
            v.text = if (railExpanded) "${k.icon}   ${k.label}" else k.icon
        }
        titleView.text = s.label
        chipsBox.removeAllViews()
        status.text = ""
        content.removeAllViews()
        liveList = null
        // Les listes nécessaires sont préparées en arrière-plan ; l'écran reste fluide pendant ce temps.
        val need = neededSources(s).filter { !viewCache.containsKey(it) }
        if (need.isNotEmpty()) { showLoading(s, need); return }
        val v = try {
            when (s) {
                Sec.HOME -> buildHome()
                Sec.LIVE -> buildLive()
                Sec.MOVIES -> buildVod(series = false)
                Sec.SERIES -> buildVod(series = true)
                Sec.FAV -> buildFav()
                Sec.LISTS -> buildManager()
                Sec.SOURCES -> buildSources()
                else -> View(this)
            }
        } catch (e: Throwable) {
            if (e is OutOfMemoryError) { viewCache.clear(); fullCache.clear(); Repo.trimMemory() }
            text(17f, Color.WHITE, false).apply {
                text = "Impossible d'afficher cette section : ${if (e is OutOfMemoryError) "mémoire du téléviseur insuffisante pour cette liste" else e.message}.\n\n" +
                    "Dans Sources, choisissez « Chaînes seulement (sans films ni séries) » pour le portail concerné, ou videz sa liste."
                setPadding(dp(8), dp(24), dp(8), 0)
            }
        }
        content.addView(v, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    /** Sources dont la liste doit être prête pour afficher la section. */
    private fun neededSources(s: Sec): List<String> = when (s) {
        Sec.LIVE -> tabSourceIds("live")
        Sec.MOVIES -> tabSourceIds("movies")
        Sec.SERIES -> tabSourceIds("series")
        Sec.LISTS -> listOfNotNull(mgrSource?.takeIf { id -> Repo.sources.any { it.id == id } } ?: Repo.sources.firstOrNull()?.id)
        else -> emptyList()
    }

    private fun showLoading(s: Sec, need: List<String>) {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER }
        val names = need.joinToString { id -> "« ${srcName(id)} » (${Repo.listFileSize(id) / 1048576 + 1} Mo)" }
        box.addView(ProgressBar(this))
        box.addView(text(17f, Color.WHITE, false).apply {
            text = "Chargement de $names…"; gravity = Gravity.CENTER; setPadding(0, dp(12), 0, 0)
        })
        content.addView(box, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        if (loadingSec == s) return
        loadingSec = s
        lifecycleScope.launch {
            val failed = withContext(Dispatchers.Default) {
                val f = ArrayList<String>()
                for (id in need) {
                    try {
                        val origin = Repo.channels(id)
                        if (id in Repo.loadFailed) f += id
                        viewCache[id] = Edits.view(id).filter { !Prefs.isHiddenAdult(it) }
                        viewOrigin[id] = origin
                        if (s == Sec.LISTS) fullCache[id] = Edits.view(id, true)
                        if (s == Sec.LIVE) Repo.epg(id)
                    } catch (e: Throwable) {
                        f += id
                        viewCache[id] = emptyList()
                        if (e is OutOfMemoryError) Repo.trimMemory()
                    }
                }
                f
            }
            loadingSec = null
            if (isFinishing) return@launch
            if (sec == s) show(s)
            if (failed.isNotEmpty()) tooBig(failed)
        }
    }

    /** Liste trop volumineuse pour la mémoire de ce téléviseur : propose de garder seulement les chaînes. */
    private fun tooBig(ids: List<String>) {
        val src = Repo.sources.firstOrNull { it.id == ids.first() } ?: return
        AlertDialog.Builder(this).setTitle("Liste trop volumineuse")
            .setMessage("La liste de « ${src.name} » (${Repo.listFileSize(src.id) / 1048576} Mo) ne tient pas dans la mémoire de ce téléviseur.\n\n" +
                "Solution : la recharger avec les chaînes seulement (sans films ni séries), ou la vider.")
            .setPositiveButton("Chaînes seulement") { _, _ ->
                Prefs.liveOnlySources = Prefs.liveOnlySources + src.id
                Repo.clearList(src.id)
                refreshSources(listOf(src))
            }
            .setNeutralButton("Vider la liste") { _, _ -> Repo.clearList(src.id); dropCache(src.id); show(sec) }
            .setNegativeButton("Fermer", null).show()
    }

    private fun dropCache(id: String) { viewCache.remove(id); fullCache.remove(id); viewOrigin.remove(id) }

    override fun onResume() {
        super.onResume()
        if (builtVersion != Repo.version) {
            builtVersion = Repo.version
            // Seules les listes remplacées (actualisation, mémoire libérée) sont recalculées.
            for (id in viewCache.keys.toList()) {
                val cur = Repo.cachedChannels(id)
                if (cur == null || cur !== viewOrigin[id]) dropCache(id)
            }
            val f = currentFocus
            val inRail = f != null && f.parent === rail
            show(sec)
            if (inRail) railItems[sec]?.requestFocus()
        }
    }

    override fun onPause() {
        super.onPause()
        stopPreview()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        stopPreview()
        super.onDestroy()
    }

    @Deprecated("Navigation Retour des téléviseurs")
    override fun onBackPressed() {
        val f = currentFocus
        if (f != null && f.parent !== rail) { railItems[sec]?.requestFocus(); return }
        if (sec != Sec.HOME) { show(Sec.HOME); railItems[Sec.HOME]?.requestFocus(); return }
        val now = System.currentTimeMillis()
        if (now - lastBack < 2000) { @Suppress("DEPRECATION") super.onBackPressed(); return }
        lastBack = now
        android.widget.Toast.makeText(this, "Appuyez encore sur Retour pour quitter", android.widget.Toast.LENGTH_SHORT).show()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_MENU || keyCode == KeyEvent.KEYCODE_SETTINGS) {
            startActivity(Intent(this, SettingsActivity::class.java)); return true
        }
        if (keyCode == KeyEvent.KEYCODE_SEARCH) { startActivity(Intent(this, SearchActivity::class.java)); return true }
        if (keyCode == KeyEvent.KEYCODE_GUIDE) { startActivity(Intent(this, GuideActivity::class.java)); return true }
        // Saisie d'un numéro de chaîne dans Direct
        if (sec == Sec.LIVE && keyCode in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 && currentFocus !is EditText) {
            digits = (digits + (keyCode - KeyEvent.KEYCODE_0)).takeLast(5)
            status.text = "Chaîne n° $digits…"
            handler.removeCallbacks(zapRun)
            handler.postDelayed(zapRun, 1300)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun zapToNumber() {
        val n = digits.toIntOrNull() ?: return
        digits = ""
        val i = n - 1
        val ch = liveShown.getOrNull(i) ?: run { status.text = "Pas de chaîne n° $n dans cette catégorie"; return }
        liveList?.setSelection(i)
        play(ch, liveShown)
    }

    // ======================================================================
    // Données des onglets
    // ======================================================================
    /** Liste préparée d'une source (vide si elle n'est pas encore chargée : jamais de lecture sur le fil de l'écran). */
    private fun srcView(id: String): List<Channel> = viewCache[id] ?: emptyList()

    private fun inTab(tab: String, ch: Channel): Boolean = when (tab) {
        "live" -> !ch.isVod
        "movies" -> ch.isMovie
        "series" -> ch.isSeries
        else -> true
    }

    private fun selectedSource(tab: String): String? =
        Prefs.tabSource(tab).takeIf { id -> id.isNotEmpty() && Repo.sources.any { it.id == id } }

    /** Sources qui composent l'onglet (une seule si choisie, sinon toutes celles incluses). */
    private fun tabSourceIds(tab: String): List<String> =
        selectedSource(tab)?.let { listOf(it) } ?: Repo.sources.filter { it.id !in Prefs.hiddenSources }.map { it.id }

    private fun tabItems(tab: String): List<Channel> {
        val out = ArrayList<Channel>()
        for (id in tabSourceIds(tab)) srcView(id).filterTo(out) { inTab(tab, it) }
        return out
    }

    private fun srcName(id: String) = Repo.sources.firstOrNull { it.id == id }?.name ?: "?"

    private fun typeIcon(t: SourceType) = when (t) {
        SourceType.STALKER -> "📡"; SourceType.XTREAM -> "🔑"; SourceType.M3U -> "📄"
        SourceType.YOUTUBE -> "▶"; SourceType.TUNER -> "📺"
    }

    /** Onglets de sources en haut de Direct, Films et Séries. */
    private fun sourceChips(tab: String) {
        val sel = selectedSource(tab)
        // Nombre d'éléments seulement pour les listes déjà chargées (on ne charge rien pour les afficher).
        val counts = Repo.sources.associate { s -> s.id to (viewCache[s.id]?.count { inTab(tab, it) } ?: -1) }
        chip("Toutes les sources", sel == null) { Prefs.setTabSource(tab, ""); reshow() }
        for (s in Repo.sources) {
            val n = counts[s.id] ?: -1
            if (n == 0 && s.id != sel) continue
            chip("${typeIcon(s.type)} ${s.name}" + if (n > 0) "  ${fmt(n)}" else "", s.id == sel) { Prefs.setTabSource(tab, s.id); reshow() }
        }
        chip("＋ Source", false) { addSourceDialog() }
    }

    private fun reshow() { val f = currentFocus; show(sec); if (f != null && f.parent === chipsBox) chipsBox.getChildAt(0)?.requestFocus() }

    private fun chip(label: String, active: Boolean, onLong: (() -> Unit)? = null, onClick: () -> Unit): TextView {
        val v = TextView(this).apply {
            text = label
            textSize = 15f
            setTextColor(Color.WHITE)
            isSingleLine = true
            isFocusable = true
            isActivated = active
            background = ContextCompat.getDrawable(this@TvHomeActivity, R.drawable.bg_chip)
            setPadding(dp(16), dp(7), dp(16), dp(7))
            setOnClickListener { onClick() }
            if (onLong != null) setOnLongClickListener { onLong(); true }
        }
        chipsBox.addView(v, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            marginEnd = dp(8)
        })
        return v
    }

    /** Catégories d'une liste : groupes dans l'ordre, avec le nom de la source s'il y en a plusieurs. */
    private fun categories(items: List<Channel>, multi: Boolean): Pair<List<Cat>, Map<String, List<Channel>>> {
        val index = LinkedHashMap<String, MutableList<Channel>>()
        for (c in items) index.getOrPut(c.sourceId + "\u0000" + c.group) { ArrayList() }.add(c)
        val cats = index.map { (k, l) ->
            val sid = k.substringBefore('\u0000')
            val g = k.substringAfter('\u0000')
            val name = g.removePrefix("Films · ").removePrefix("Séries · ")
            val lock = if (Prefs.pin.isNotBlank() && (g in Prefs.lockedGroups || Prefs.isAdultGroup(g))) "🔒 " else ""
            Cat("$lock${if (multi) "${srcName(sid)} · " else ""}$name  (${fmt(l.size)})", sid, g)
        }
        return cats to index
    }

    private fun catItems(c: Cat, all: List<Channel>, index: Map<String, List<Channel>>, tab: String): List<Channel> = when {
        c.special == "fav" -> FavLists.items(FavLists.DEFAULT).filter { inTab(tab, it) }
        c.special == "recent" -> Repo.recents.filter { inTab(tab, it) }
        c.sid == null -> all
        else -> index[c.sid + "\u0000" + c.group] ?: emptyList()
    }

    // ======================================================================
    // Accueil : rangées
    // ======================================================================
    private fun buildHome(): View {
        chip("⟳ Actualiser", false) { refreshAll(onlyShown = true) }
        chip("▦ Multi-écran", false) { startActivity(Intent(this, MultiViewActivity::class.java)) }
        chip("＋ Ajouter une source", false) { addSourceDialog() }
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        if (Repo.sources.isEmpty()) {
            box.addView(welcome())
            return ScrollView(this).apply { addView(box) }
        }
        status.text = Repo.sources.joinToString("  •  ") { "${typeIcon(it.type)} ${it.name}" }
        fun row(title: String, items: List<Channel>, ctx: List<Channel> = items) {
            if (items.isEmpty()) return
            box.addView(TextView(this).apply {
                text = "$title  ·  ${items.size}"
                textSize = 18f; setTextColor(Color.WHITE); setTypeface(typeface, Typeface.BOLD)
                setPadding(dp(4), dp(10), 0, dp(6))
            })
            val rv = androidx.leanback.widget.HorizontalGridView(this)
            rv.adapter = RowAdapter(items.take(60), { play(it, ctx) }, { itemMenu(it, ctx, null) })
            rv.setItemSpacing(dp(12))
            rv.clipToPadding = false
            rv.setPadding(dp(4), dp(6), dp(4), dp(6))
            box.addView(rv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(196)))
        }
        row("▶ Continuer à regarder", Library.resume.values.toList())
        for (l in FavLists.lists()) row("★ ${l.name}", FavLists.items(l.id))
        row("🕘 Regardées récemment", Repo.recents.toList())

        if (box.childCount == 0) box.addView(TextView(this).apply {
            text = "Ouvrez Direct, Films ou Séries dans la barre de gauche. Vos listes de favoris, reprises et chaînes " +
                "récentes apparaîtront ici."
            textSize = 18f; setTextColor(HINT); setPadding(dp(4), dp(20), dp(4), 0)
        })
        return ScrollView(this).apply { addView(box); isFillViewport = true }
    }

    private fun welcome(): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(8), dp(24), dp(8), 0)
        addView(TextView(this@TvHomeActivity).apply {
            text = "Bienvenue dans Omni IPTV"
            textSize = 26f; setTextColor(Color.WHITE); setTypeface(typeface, Typeface.BOLD)
        })
        addView(TextView(this@TvHomeActivity).apply {
            text = "Ajoutez une première source pour remplir Direct, Films et Séries."
            textSize = 17f; setTextColor(HINT); setPadding(0, dp(6), 0, dp(16))
        })
        val r = LinearLayout(this@TvHomeActivity).apply { orientation = LinearLayout.HORIZONTAL }
        r.addView(Ui.button(this@TvHomeActivity, "📡 Portail MAC (Stalker)", false) { addSource(SourceType.STALKER) })
        r.addView(Ui.button(this@TvHomeActivity, "🔑 Compte Xtream", false) { addSource(SourceType.XTREAM) })
        r.addView(Ui.button(this@TvHomeActivity, "📄 Liste M3U", false) { addSource(SourceType.M3U) })
        r.addView(Ui.button(this@TvHomeActivity, "Plus…", false) { addSourceDialog() })
        addView(r)
    }

    /** Vignettes des rangées de l'accueil (agrandies au focus). */
    private inner class RowAdapter(
        val items: List<Channel>, val onClick: (Channel) -> Unit, val onLong: (Channel) -> Unit
    ) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            // Les paramètres de disposition viennent de la rangée elle-même (gonflage avec le parent) :
            // on ne change que la largeur, sans les remplacer (la rangée TV exige les siens).
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_tile, parent, false)
            v.layoutParams.width = dp(180)
            v.background = ContextCompat.getDrawable(this@TvHomeActivity, R.drawable.bg_card)
            v.isFocusable = true
            v.setOnFocusChangeListener { x, f -> x.animate().scaleX(if (f) 1.07f else 1f).scaleY(if (f) 1.07f else 1f).setDuration(120).start() }
            return object : RecyclerView.ViewHolder(v) {}
        }

        override fun getItemCount() = items.size

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val ch = items[position]
            TileAdapter.bind(this@TvHomeActivity, holder.itemView, ch, position)
            holder.itemView.setOnClickListener { onClick(ch) }
            holder.itemView.setOnLongClickListener { onLong(ch); true }
        }
    }

    // ======================================================================
    // Direct : catégories | chaînes | aperçu et guide
    // ======================================================================
    private fun buildLive(): View {
        sourceChips("live")
        val all = tabItems("live")
        val multi = tabSourceIds("live").size > 1
        val (groupCats, index) = categories(all, multi)
        val cats = listOf(
            Cat("Toutes les chaînes  (${fmt(all.size)})"),
            Cat("★ Favoris  (${FavLists.items(FavLists.DEFAULT).count { inTab("live", it) }})", special = "fav"),
            Cat("🕘 Récentes", special = "recent")
        ) + groupCats

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val catList = listView()
        catList.adapter = ArrayAdapter(this, R.layout.item_text, cats.map { it.label })
        row.addView(column("Catégories", catList), LinearLayout.LayoutParams(dp(215), ViewGroup.LayoutParams.MATCH_PARENT))

        val middle = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val search = searchField("Rechercher une chaîne…")
        middle.addView(search)
        val chList = listView()
        val adapter = ChannelRowAdapter(this, emptyList())
        chList.adapter = adapter
        middle.addView(chList, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        row.addView(middle, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply {
            marginStart = dp(12); marginEnd = dp(12)
        })
        val info = infoPanel()
        row.addView(info.root, LinearLayout.LayoutParams(dp(285), ViewGroup.LayoutParams.MATCH_PARENT))
        liveList = chList

        var cat = cats[0]
        fun refreshList() {
            val q = EpgNorm.norm(search.text.toString())
            var l = catItems(cat, all, index, "live")
            if (q.isNotEmpty()) l = (if (cat.sid == null && cat.special == null) all else l).filter { EpgNorm.norm(it.name).contains(q) }
            liveShown = l
            adapter.items = l
            adapter.notifyDataSetChanged()
            status.text = "${cat.label.substringBefore("  (")} • ${fmt(l.size)} chaîne(s)" +
                if (q.isNotEmpty()) " • recherche « ${search.text} »" else "  •  Chiffres : aller à un numéro • Appui long : options"
            if (l.isEmpty()) info.clear(if (all.isEmpty()) emptyText("live") else "Aucune chaîne.")
        }
        val catRun = Runnable { refreshList() }
        catList.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                val c = cats.getOrNull(pos) ?: return
                if (c == cat) return
                cat = c
                handler.removeCallbacks(catRun); handler.postDelayed(catRun, 220)
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        catList.setOnItemClickListener { _, _, pos, _ -> cat = cats[pos]; refreshList(); chList.requestFocus() }
        catList.setOnItemLongClickListener { _, _, pos, _ -> cats[pos].let { c -> if (c.sid != null) groupMenu(c.sid, c.group!!, "live") }; true }
        chList.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                liveShown.getOrNull(pos)?.let { info.show(it); schedulePreview(it) }
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        chList.setOnItemClickListener { _, _, pos, _ -> liveShown.getOrNull(pos)?.let { play(it, liveShown) } }
        chList.setOnItemLongClickListener { _, _, pos, _ -> liveShown.getOrNull(pos)?.let { itemMenu(it, liveShown, null) }; true }
        search.addTextChangedListener(watcher { handler.removeCallbacks(catRun); handler.postDelayed(catRun, 300) })
        leftTo(chList, { catList })
        leftTo(catList, { toRail() })
        refreshList()
        liveShown.firstOrNull()?.let { info.show(it) }
        if (all.isEmpty()) info.clear(emptyText("live"))
        return row
    }

    /** Panneau de droite : aperçu vidéo, programme en cours et suivant. */
    private inner class Info(val root: LinearLayout) {
        val frame = FrameLayout(this@TvHomeActivity).apply { setBackgroundColor(Color.BLACK) }
        val logo = ImageView(this@TvHomeActivity).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
        val name = text(19f, Color.WHITE, true)
        val now = text(16f, Color.WHITE, false)
        val bar = ProgressBar(this@TvHomeActivity, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100 }
        val desc = text(14f, HINT, false).apply { maxLines = 6 }
        val next = text(14f, HINT, false)

        fun show(ch: Channel) {
            name.text = ch.name
            val (n, nx) = Repo.nowNext(ch)
            if (n != null) {
                now.text = "${Ui.hm(n.start)} – ${Ui.hm(n.end)}  ${n.title}"
                val t = System.currentTimeMillis()
                bar.progress = (((t - n.start) * 100) / (n.end - n.start).coerceAtLeast(1)).toInt()
                bar.visibility = View.VISIBLE
                desc.text = n.desc
            } else {
                now.text = ch.group
                bar.visibility = View.INVISIBLE
                desc.text = "Pas de guide TV pour cette chaîne."
            }
            next.text = nx?.let { "Ensuite : ${Ui.hm(it.start)}  ${it.title}" } ?: ""
            if (preview == null || previewKey != ch.key) {
                previewView?.visibility = View.INVISIBLE
                logo.visibility = View.VISIBLE
                if (ch.logo.isNotBlank()) Glide.with(this@TvHomeActivity).load(ch.logo).error(R.drawable.ic_tv).into(logo)
                else logo.setImageResource(R.drawable.ic_tv)
            }
        }

        fun clear(msg: String) {
            name.text = ""; now.text = ""; next.text = ""; desc.text = msg; bar.visibility = View.INVISIBLE
            logo.setImageResource(R.drawable.ic_tv)
        }
    }

    private fun infoPanel(): Info {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = ContextCompat.getDrawable(this@TvHomeActivity, R.drawable.bg_card)
            setPadding(dp(10), dp(10), dp(10), dp(10))
        }
        val info = Info(root)
        info.frame.addView(info.logo, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT).apply {
            setMargins(dp(40), dp(30), dp(40), dp(30))
        })
        if (Prefs.livePreview) {
            previewView = PlayerView(this).apply {
                useController = false; setShutterBackgroundColor(Color.TRANSPARENT); visibility = View.INVISIBLE
            }
            info.frame.addView(previewView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        } else previewView = null
        root.addView(info.frame, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(149)))
        root.addView(info.name, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
        root.addView(info.now)
        root.addView(info.bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(6)).apply { topMargin = dp(4); bottomMargin = dp(6) })
        root.addView(info.desc, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(info.next)
        root.addView(text(12f, HINT, false).apply {
            text = "OK : plein écran   •   Appui long : options"; setPadding(0, dp(6), 0, 0)
        })
        return info
    }

    // ---------- Aperçu vidéo ----------
    private fun schedulePreview(ch: Channel) {
        if (!Prefs.livePreview || previewView == null) return
        pendingPreview = ch
        handler.removeCallbacks(previewRun)
        handler.postDelayed(previewRun, 900)
    }

    private fun startPreview() {
        val ch = pendingPreview ?: return
        if (ch.key == previewKey || sec != Sec.LIVE) return
        if (ch.isVod || ch.url.startsWith("tuner:") || Prefs.isLocked(ch)) { stopPlayerOnly(); return }
        previewKey = ch.key
        lifecycleScope.launch {
            try {
                val url = StreamResolver.resolve(ch)
                if (previewKey != ch.key || sec != Sec.LIVE || isFinishing) return@launch
                val p = preview ?: PlayerFactory.create(this@TvHomeActivity, preview = true).also { pl ->
                    preview = pl
                    pl.addListener(object : androidx.media3.common.Player.Listener {
                        override fun onRenderedFirstFrame() { previewView?.visibility = View.VISIBLE }
                        override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                            previewView?.visibility = View.INVISIBLE
                            status.text = "Aperçu indisponible (${error.errorCodeName}) — OK pour essayer en plein écran"
                        }
                    })
                }
                previewView?.player = p
                PlayerFactory.play(this@TvHomeActivity, p, url, StreamResolver.userAgent(ch), ch.referer,
                    forceHls = StreamResolver.looksHls(url), headers = StreamResolver.headers(ch))
            } catch (e: Throwable) {
                if (e is OutOfMemoryError) { stopPreview(); com.elarbi.omniiptv.data.CrashLog.record("Aperçu", e) }
                if (previewKey == ch.key) status.text = "Aperçu indisponible : ${e.message}"
            }
        }
    }

    private fun stopPlayerOnly() {
        previewKey = ""
        previewView?.visibility = View.INVISIBLE
        preview?.stop()
    }

    private fun stopPreview() {
        handler.removeCallbacks(previewRun)
        previewKey = ""
        pendingPreview = null
        previewView?.visibility = View.INVISIBLE
        previewView?.player = null
        preview?.release()
        preview = null
    }

    // ======================================================================
    // Films et Séries : catégories | affiches
    // ======================================================================
    private fun buildVod(series: Boolean): View {
        val tab = if (series) "series" else "movies"
        sourceChips(tab)
        val all = tabItems(tab)
        val multi = tabSourceIds(tab).size > 1
        val (groupCats, index) = categories(all, multi)
        val cats = listOf(Cat("Tout  (${fmt(all.size)})"), Cat("★ Favoris", special = "fav")) +
            (if (!series) listOf(Cat("▶ En cours", special = "resume")) else emptyList()) + groupCats

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val catList = listView()
        catList.adapter = ArrayAdapter(this, R.layout.item_text, cats.map { it.label })
        row.addView(column("Catégories", catList), LinearLayout.LayoutParams(dp(260), ViewGroup.LayoutParams.MATCH_PARENT))
        val right = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val search = searchField(if (series) "Rechercher une série…" else "Rechercher un film…")
        right.addView(search)
        val grid = GridView(this).apply {
            numColumns = GridView.AUTO_FIT
            columnWidth = dp(160)
            stretchMode = GridView.STRETCH_COLUMN_WIDTH
            horizontalSpacing = dp(10)
            verticalSpacing = dp(10)
            selector = ContextCompat.getDrawable(this@TvHomeActivity, R.drawable.bg_card)
            setDrawSelectorOnTop(true)
            clipToPadding = false
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        val adapter = TileAdapter(this, emptyList())
        grid.adapter = adapter
        right.addView(grid, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        val empty = text(18f, HINT, false).apply { setPadding(dp(8), dp(30), dp(8), 0) }
        right.addView(empty)
        row.addView(right, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply { marginStart = dp(14) })

        var cat = cats[0]
        var shown: List<Channel> = emptyList()
        fun refresh() {
            val q = EpgNorm.norm(search.text.toString())
            var l = if (cat.special == "resume") Library.resume.values.filter { inTab(tab, it) } else catItems(cat, all, index, tab)
            if (q.isNotEmpty()) l = (if (cat.sid == null && cat.special == null) all else l).filter { EpgNorm.norm(it.name).contains(q) }
            shown = l
            adapter.items = l
            adapter.notifyDataSetChanged()
            empty.visibility = if (l.isEmpty()) View.VISIBLE else View.GONE
            empty.text = if (all.isEmpty()) emptyText(tab) else "Aucun résultat."
            status.text = "${cat.label.substringBefore("  (")} • ${fmt(l.size)} ${if (series) "série(s)" else "film(s)"}" +
                if (q.isNotEmpty()) " • recherche « ${search.text} »" else "  •  Appui long : options"
        }
        val run = Runnable { refresh() }
        catList.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                val c = cats.getOrNull(pos) ?: return
                if (c == cat) return
                cat = c
                handler.removeCallbacks(run); handler.postDelayed(run, 220)
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        catList.setOnItemClickListener { _, _, pos, _ -> cat = cats[pos]; refresh(); grid.requestFocus() }
        catList.setOnItemLongClickListener { _, _, pos, _ -> cats[pos].let { c -> if (c.sid != null) groupMenu(c.sid, c.group!!, tab) }; true }
        grid.setOnItemClickListener { _, _, pos, _ -> shown.getOrNull(pos)?.let { play(it, shown) } }
        grid.setOnItemLongClickListener { _, _, pos, _ -> shown.getOrNull(pos)?.let { itemMenu(it, shown, null) }; true }
        search.addTextChangedListener(watcher { handler.removeCallbacks(run); handler.postDelayed(run, 300) })
        leftTo(grid, { catList }) { grid.selectedItemPosition <= 0 || grid.selectedItemPosition % grid.numColumns.coerceAtLeast(1) == 0 }
        leftTo(catList, { toRail() })
        refresh()
        return row
    }

    // ======================================================================
    // Mes listes : plusieurs listes de favoris
    // ======================================================================
    private fun buildFav(): View {
        val lists = FavLists.lists()
        val cur = lists.firstOrNull { it.id == Prefs.favList } ?: lists[0]
        for (l in lists) {
            chip("★ ${l.name}  ${FavLists.count(l.id)}", l.id == cur.id, onLong = { listMenu(l) }) {
                Prefs.favList = l.id; reshow()
            }
        }
        chip("＋ Nouvelle liste", false) {
            askText("Nouvelle liste de favoris", "") { n -> val l = FavLists.create(n); Prefs.favList = l.id; reshow() }
        }
        chip("✎ Gérer « ${cur.name} »", false) { listMenu(cur) }

        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val items = FavLists.items(cur.id)
        status.text = "« ${cur.name} » • ${items.size} élément(s)  •  Appui long sur un élément : déplacer, retirer, copier dans une autre liste"
        if (items.isEmpty()) {
            box.addView(text(18f, HINT, false).apply {
                text = "Cette liste est vide.\n\nDans Direct, Films ou Séries, faites un appui long sur une chaîne ou un film, " +
                    "puis « Ajouter à une liste de favoris ». Vous pouvez créer autant de listes que vous voulez " +
                    "(Sport, Enfants, Info, Cinéma…) avec « ＋ Nouvelle liste »."
                setPadding(dp(8), dp(24), dp(8), 0)
            })
            return box
        }
        val list = listView()
        val adapter = ChannelRowAdapter(this, items)
        list.adapter = adapter
        list.setOnItemClickListener { _, _, pos, _ -> items.getOrNull(pos)?.let { play(it, items) } }
        list.setOnItemLongClickListener { _, _, pos, _ -> items.getOrNull(pos)?.let { itemMenu(it, items, cur.id) }; true }
        leftTo(list, { toRail() })
        box.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        return box
    }

    private fun listMenu(l: FavLists.FavList) {
        val opts = ArrayList<Pair<String, () -> Unit>>()
        opts += "▶ Lire la liste" to { FavLists.items(l.id).firstOrNull()?.let { play(it, FavLists.items(l.id)) } }
        opts += "📺 Afficher sur l'accueil du téléviseur" to { homeRow("fav", l.id, "Omni · ${l.name}") }
        if (l.id != FavLists.DEFAULT) {
            opts += "✎ Renommer" to { askText("Renommer la liste", l.name) { FavLists.rename(l.id, it); reshow() } }
            opts += "↑ Placer plus haut" to { FavLists.moveList(l.id, -1); reshow() }
            opts += "↓ Placer plus bas" to { FavLists.moveList(l.id, 1); reshow() }
            opts += "🗑 Supprimer la liste" to {
                confirm("Supprimer la liste « ${l.name} » ?") { FavLists.delete(l.id); Prefs.favList = FavLists.DEFAULT; reshow() }
            }
        }
        opts += "Vider la liste" to {
            confirm("Retirer tous les éléments de « ${l.name} » ?") {
                FavLists.items(l.id).forEach { FavLists.remove(l.id, it.key) }; reshow()
            }
        }
        menu(l.name, opts)
    }

    // ======================================================================
    // Gestion des listes : sources | catégories | chaînes
    // ======================================================================
    private fun buildManager(): View {
        val sources = Repo.sources
        if (sources.isEmpty()) return welcome()
        if (mgrSource == null || sources.none { it.id == mgrSource }) mgrSource = sources[0].id
        val sid = mgrSource!!
        val src = sources.first { it.id == sid }
        chip("⟳ Actualiser", false) { refreshSources(listOf(src)) }
        if (src.type == SourceType.M3U || src.type == SourceType.XTREAM || src.type == SourceType.STALKER)
            chip("✎ Modifier le serveur", false) { SourcePicker.edit(this, sid) }
        chip("Outils avancés (logos, guide)", false) {
            startActivity(Intent(this, ChannelManagerActivity::class.java).putExtra("source", sid))
        }
        chip("Réinitialiser", false) {
            confirm("Annuler toutes les personnalisations de « ${src.name} » (noms, ordre, catégories masquées) ?") {
                Edits.reset(sid); invalidate(sid)
            }
        }

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        // 1. Sources
        val srcList = listView()
        srcList.adapter = ArrayAdapter(this, R.layout.item_text, sources.map { "${typeIcon(it.type)} ${it.name}" })
        row.addView(column("Sources", srcList), LinearLayout.LayoutParams(dp(220), ViewGroup.LayoutParams.MATCH_PARENT))

        // 2. Catégories (y compris masquées) groupées par section
        val full = fullCache[sid] ?: emptyList()
        val sections = listOf("live" to "📺", "movies" to "🎬", "series" to "🎞")
        data class G(val tab: String, val group: String, val count: Int)
        val groups = ArrayList<G>()
        for ((tab, _) in sections) {
            val idx = LinkedHashMap<String, Int>()
            for (c in full) if (inTab(tab, c)) idx[c.group] = (idx[c.group] ?: 0) + 1
            idx.forEach { (g, n) -> groups += G(tab, g, n) }
        }
        val groupLabels = groups.map { g ->
            val icon = sections.first { it.first == g.tab }.second
            val hidden = if (Edits.isGroupHidden(sid, g.group)) "🚫 " else ""
            val lock = if (g.group in Prefs.lockedGroups) "🔒 " else ""
            val moving = if (movingGroup == g.tab to g.group) "↕  " else ""
            "$moving$icon $hidden$lock${g.group.removePrefix("Films · ").removePrefix("Séries · ")}  (${fmt(g.count)})"
        }
        val grpFilter = searchField("Filtrer les catégories…")
        val grpList = listView()
        var visibleGroups: List<G> = groups
        fun fillGroups() {
            val q = EpgNorm.norm(grpFilter.text.toString())
            val pairs = groups.indices.filter { q.isEmpty() || EpgNorm.norm(groupLabels[it]).contains(q) }
            visibleGroups = pairs.map { groups[it] }
            mgrVisible = visibleGroups.map { it.tab to it.group }
            grpList.adapter = ArrayAdapter(this, R.layout.item_text, pairs.map { groupLabels[it] })
        }
        fillGroups()
        val mid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        mid.addView(header("Catégories • OK : options"))
        mid.addView(grpFilter)
        mid.addView(grpList, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        row.addView(mid, LinearLayout.LayoutParams(dp(330), ViewGroup.LayoutParams.MATCH_PARENT).apply { marginStart = dp(12) })

        // 3. Chaînes de la catégorie
        val chList = listView()
        var chans: List<Channel> = emptyList()
        fun fillChannels(g: G?) {
            chans = if (g == null) emptyList() else full.filter { it.group == g.group && inTab(g.tab, it) }
            val hiddenKeys = chans.filter { Edits.isHidden(it) }.map { it.key }.toHashSet()
            chList.adapter = ArrayAdapter(this, R.layout.item_text, chans.mapIndexed { i, c ->
                "${i + 1}. ${c.name}" + (if (c.key in hiddenKeys) "  🚫" else "") +
                    (if (FavLists.containing(c.key).isNotEmpty()) "  ★" else "")
            })
            status.text = "${src.name} • ${g?.group ?: "choisissez une catégorie"} • ${chans.size} élément(s)" +
                "  •  🚫 masqué  🔒 verrouillé  ★ dans une liste"
        }
        row.addView(column("Chaînes, films, séries • OK : options", chList),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply { marginStart = dp(12) })

        srcList.setSelection(sources.indexOfFirst { it.id == sid })
        srcList.setOnItemClickListener { _, _, pos, _ -> mgrSource = sources[pos].id; mgrGroup = null; show(Sec.LISTS); focusContent() }
        grpList.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                visibleGroups.getOrNull(pos)?.let { g -> mgrGroup = g.group; fillChannels(g) }
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        grpList.setOnItemClickListener { _, _, pos, _ ->
            if (movingGroup != null) return@setOnItemClickListener
            visibleGroups.getOrNull(pos)?.let { g -> mgrGroup = g.group; fillChannels(g); groupMenu(sid, g.group, g.tab) }
        }
        chList.setOnItemClickListener { _, _, pos, _ -> chans.getOrNull(pos)?.let { managerChannelMenu(it, chans) } }
        chList.setOnItemLongClickListener { _, _, pos, _ -> chans.getOrNull(pos)?.let { managerChannelMenu(it, chans) }; true }
        grpFilter.addTextChangedListener(watcher { fillGroups() })
        leftTo(chList, { grpList })
        leftTo(srcList, { toRail() })
        // Catégories : touche gauche → sources ; en mode déplacement, ↑/↓ déplacent la catégorie, OK termine.
        grpList.setOnKeyListener { _, code, ev ->
            val ok = code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER
            if (ev.action == KeyEvent.ACTION_UP) {
                if (ok && swallowOkUp) { swallowOkUp = false; return@setOnKeyListener true }
                return@setOnKeyListener movingGroup != null
            }
            val mg = movingGroup
            when {
                mg != null -> {
                    when (code) {
                        KeyEvent.KEYCODE_DPAD_UP -> moveGroupInTab(sid, mg.first, mg.second, -1)
                        KeyEvent.KEYCODE_DPAD_DOWN -> moveGroupInTab(sid, mg.first, mg.second, 1)
                        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_BACK,
                        KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                            if (ok) swallowOkUp = true
                            movingGroup = null
                            mgrGroup = mg.second
                            refreshManager()
                            Ui.toast(this, "Ordre des catégories enregistré")
                        }
                    }
                    true
                }
                code == KeyEvent.KEYCODE_DPAD_LEFT -> { srcList.requestFocus(); true }
                else -> false
            }
        }
        mgrGrpList = grpList
        val mg = movingGroup
        val start = keepGroupIndex?.coerceIn(0, (visibleGroups.size - 1).coerceAtLeast(0))
            ?: (if (mg != null) visibleGroups.indexOfFirst { it.tab == mg.first && it.group == mg.second }
                else visibleGroups.indexOfFirst { it.group == mgrGroup }).coerceAtLeast(0)
        keepGroupIndex = null
        visibleGroups.getOrNull(start)?.let { mgrGroup = it.group; fillChannels(it); grpList.setSelection(start) } ?: fillChannels(null)
        if (mg != null) status.text = "↕ Déplacement de « ${mg.second} » : ↑ / ↓ pour déplacer, OK pour terminer"
        return row
    }

    /** Ordre actuel des catégories d'une source (d'après la liste en mémoire, déjà triée). */
    private fun orderOf(sid: String): List<String> =
        (fullCache[sid] ?: viewCache[sid] ?: emptyList()).asSequence().map { it.group }.distinct().toList()

    /**
     * Déplace une catégorie parmi celles de sa section (Direct, Films ou Séries), sans recalculer toute la liste :
     * l'ordre est enregistré et les listes en mémoire sont simplement retriées.
     */
    private fun moveGroupInTab(sid: String, tab: String, g: String, delta: Int) {
        val inTabOrder = (fullCache[sid] ?: viewCache[sid] ?: emptyList()).asSequence()
            .filter { inTab(tab, it) }.map { it.group }.distinct().toList()
        val i = inTabOrder.indexOf(g)
        if (i < 0) return
        val j = (i + delta).coerceIn(0, inTabOrder.size - 1)
        if (i == j) return
        val global = orderOf(sid).toMutableList()
        val anchor = inTabOrder[j]
        global.remove(g)
        val ai = global.indexOf(anchor)
        if (ai < 0) return
        global.add(if (delta < 0) ai else ai + 1, g)
        Edits.setGroupOrder(sid, global)
        reorderCached(sid, global)
        refreshManager()
    }

    private fun reorderCached(sid: String, order: List<String>) {
        val idx = HashMap<String, Int>(order.size * 2)
        order.forEachIndexed { n, g -> idx[g] = n }
        fullCache[sid]?.let { l -> fullCache[sid] = l.sortedBy { idx[it.group] ?: Int.MAX_VALUE } }
        viewCache[sid]?.let { l -> viewCache[sid] = l.sortedBy { idx[it.group] ?: Int.MAX_VALUE } }
    }

    /** Réaffiche la section sans recharger les listes, en gardant le curseur sur les catégories. */
    private fun refreshManager() {
        builtVersion = Repo.version
        val inGroups = sec == Sec.LISTS
        show(sec)
        if (inGroups) handler.post { mgrGrpList?.requestFocus() } else focusContent()
    }

    /** Réaffiche après une modification ; [sid] : source dont la liste personnalisée a changé. */
    private fun invalidate(sid: String? = null) {
        Repo.bump()
        builtVersion = Repo.version
        if (sid != null) dropCache(sid)
        val f = currentFocus
        show(sec)
        if (f != null && f.parent === rail) railItems[sec]?.requestFocus() else focusContent()
    }

    /** Options d'une catégorie (masquer, renommer, ordre, verrou, favoris). */
    private fun groupMenu(sid: String, g: String, tab: String = "live") {
        val opts = ArrayList<Pair<String, () -> Unit>>()
        val hidden = Edits.isGroupHidden(sid, g)
        opts += (if (hidden) "👁 Afficher la catégorie" else "🚫 Masquer la catégorie") to { Edits.toggleGroupHidden(sid, g); invalidate(sid) }
        opts += "✎ Renommer la catégorie" to {
            askText("Renommer « $g »", g) { n -> Edits.renameGroup(sid, g, n); mgrGroup = n; invalidate(sid) }
        }
        opts += "↕ Déplacer avec les flèches (OK pour terminer)" to {
            movingGroup = tab to g
            mgrSource = sid
            mgrGroup = g
            if (sec != Sec.LISTS) show(Sec.LISTS)
            refreshManager()
        }
        opts += "↑ Monter d'un cran" to { mgrGroup = g; moveGroupInTab(sid, tab, g, -1) }
        opts += "↓ Descendre d'un cran" to { mgrGroup = g; moveGroupInTab(sid, tab, g, 1) }
        // Le curseur reste à la même place : on peut envoyer les catégories voisines l'une après l'autre.
        opts += "⇈ Placer en tête (le curseur reste ici)" to {
            keepGroupIndex = mgrVisible.indexOf(tab to g).takeIf { it >= 0 }
            moveGroupInTab(sid, tab, g, -100_000)
        }
        opts += "⇊ Placer en fin (le curseur reste ici)" to {
            keepGroupIndex = mgrVisible.indexOf(tab to g).takeIf { it >= 0 }
            moveGroupInTab(sid, tab, g, 100_000)
        }
        val locked = g in Prefs.lockedGroups
        opts += (if (locked) "🔓 Déverrouiller (code parental)" else "🔒 Verrouiller (code parental)") to {
            fun go() {
                Prefs.lockedGroups = if (locked) Prefs.lockedGroups - g else Prefs.lockedGroups + g
                if (Prefs.pin.isBlank() && !locked) Ui.toast(this, "Définissez un code dans Réglages > Contrôle parental pour activer le verrou.")
                invalidate()
            }
            if (locked) Ui.askPin(this) { go() } else go()
        }
        opts += "📺 Afficher sur l'accueil du téléviseur" to {
            homeRow("group", sid + "\u0001" + g, "Omni · " + g.removePrefix("Films · ").removePrefix("Séries · "))
        }
        opts += "★ Ajouter toute la catégorie à une liste…" to {
            val items = (try { Edits.view(sid) } catch (e: Exception) { emptyList() }).filter { it.group == g }
            chooseList("Ajouter ${items.size} élément(s) à…") { id -> FavLists.add(id, items); Ui.toast(this, "Ajouté à « ${FavLists.name(id)} »") }
        }
        menu(g, opts)
    }

    private fun managerChannelMenu(ch: Channel, list: List<Channel>) {
        val sid = ch.sourceId
        val opts = ArrayList<Pair<String, () -> Unit>>()
        opts += "▶ Lire" to { play(ch, list.filter { !Edits.isHidden(it) }) }
        opts += "★ Listes de favoris…" to { favDialog(ch) }
        opts += "✎ Renommer" to { askText("Nom affiché", ch.name) { n -> Edits.update(sid, ch.key) { it.name = n }; invalidate(sid) } }
        val hidden = Edits.isHidden(ch)
        opts += (if (hidden) "👁 Afficher" else "🚫 Masquer") to { Edits.update(sid, ch.key) { it.hidden = !hidden }; invalidate(sid) }
        opts += "↑ Monter" to { Edits.moveChannel(sid, ch.key, -1); invalidate(sid) }
        opts += "↓ Descendre" to { Edits.moveChannel(sid, ch.key, 1); invalidate(sid) }
        opts += "⇈ Placer en tête de la catégorie" to { Edits.moveChannel(sid, ch.key, -10_000); invalidate(sid) }
        opts += "➜ Changer de catégorie…" to { moveToGroup(ch) }
        opts += "🖼 Logo (adresse d'image)…" to {
            askText("Adresse du logo", ch.logo) { u -> Edits.update(sid, ch.key) { it.logo = u.ifBlank { null } }; invalidate(sid) }
        }
        opts += "↺ Réinitialiser cet élément" to { Edits.clear(sid, ch.key); invalidate(sid) }
        menu(ch.name, opts)
    }

    private fun moveToGroup(ch: Channel) {
        val groups = (try { Edits.groups(ch.sourceId) } catch (e: Exception) { emptyList() })
        val labels = groups + "＋ Nouvelle catégorie…"
        AlertDialog.Builder(this).setTitle("Déplacer « ${ch.name} » vers")
            .setItems(labels.toTypedArray()) { _, w ->
                if (w == groups.size) askText("Nom de la nouvelle catégorie", "") { n ->
                    Edits.update(ch.sourceId, ch.key) { it.group = n }; invalidate(ch.sourceId)
                } else { Edits.update(ch.sourceId, ch.key) { it.group = groups[w] }; invalidate(ch.sourceId) }
            }.setNegativeButton("Annuler", null).show()
    }

    // ======================================================================
    // Sources : ajouter, modifier, supprimer, actualiser
    // ======================================================================
    private fun buildSources(): View {
        chip("＋ Ajouter une source", false) { addSourceDialog() }
        chip("⟳ Tout actualiser", false) { refreshAll(onlyShown = false) }
        chip("🧹 Libérer de l'espace", false) { freeSpace() }
        chip("Dépôts", false) { startActivity(Intent(this, ReposActivity::class.java)) }
        chip("Listes gratuites", false) { startActivity(Intent(this, FreeSearchActivity::class.java)) }
        val sources = Repo.sources
        status.text = "${sources.size} source(s)  •  OK sur une source : ouvrir, actualiser, modifier, supprimer…"
        lifecycleScope.launch {
            val st = withContext(Dispatchers.IO) { Repo.storage() }
            if (sec == Sec.SOURCES) status.text = "${sources.size} source(s)  •  Stockage : ${st.free / 1048576} Mo libres  •  " +
                "listes ${st.lists / 1048576} Mo, guides TV ${st.guides / 1048576} Mo, images ${st.images / 1048576} Mo"
        }
        if (sources.isEmpty()) return welcome()
        val list = listView()
        list.adapter = object : BaseAdapter() {
            override fun getCount() = sources.size
            override fun getItem(p: Int): Any = sources[p]
            override fun getItemId(p: Int) = p.toLong()
            override fun getView(p: Int, cv: View?, parent: ViewGroup?): View {
                val s = sources[p]
                val v = (cv as? LinearLayout) ?: LinearLayout(this@TvHomeActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    background = ContextCompat.getDrawable(this@TvHomeActivity, R.drawable.bg_item)
                    setPadding(dp(16), dp(12), dp(16), dp(12))
                    addView(text(19f, Color.WHITE, true))
                    addView(text(14f, HINT, false))
                    addView(text(13f, HINT, false))
                }
                val all = viewCache[s.id]
                val inAll = s.id !in Prefs.hiddenSources
                val liveOnly = s.id in Prefs.liveOnlySources
                (v.getChildAt(0) as TextView).text = "${typeIcon(s.type)}  ${s.name}" +
                    (if (!inAll) "   (hors « Toutes les sources »)" else "") + (if (liveOnly) "   (chaînes seulement)" else "")
                (v.getChildAt(1) as TextView).text = "${s.typeLabel}  •  " + if (all != null)
                    "${fmt(all.count { !it.isVod })} chaînes  •  ${fmt(all.count { it.isMovie })} films  •  ${fmt(all.count { it.isSeries })} séries"
                else "liste enregistrée : ${Repo.listFileSize(s.id) / 1024} Ko (chargée à l'ouverture de Direct, Films ou Séries)"
                val last = Repo.lastRefresh(s.id)
                (v.getChildAt(2) as TextView).text = (if (last > 0) "Mise à jour : " +
                    SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.FRANCE).format(Date(last)) else "Jamais actualisée") +
                    Repo.lastDiff(s.id).let { if (it.isNotBlank()) "  •  $it" else "" }
                return v
            }
        }
        list.setOnItemClickListener { _, _, pos, _ -> sourceMenu(sources[pos]) }
        list.setOnItemLongClickListener { _, _, pos, _ -> sourceMenu(sources[pos]); true }
        leftTo(list, { toRail() })
        return list
    }

    private fun sourceMenu(s: Source) {
        val opts = ArrayList<Pair<String, () -> Unit>>()
        opts += "📺 Ouvrir dans Direct" to { Prefs.setTabSource("live", s.id); Repo.activeSourceId = s.id; show(Sec.LIVE); focusContent() }
        if (s.type == SourceType.XTREAM || s.type == SourceType.STALKER || s.type == SourceType.M3U) {
            opts += "🎬 Ouvrir dans Films" to { Prefs.setTabSource("movies", s.id); show(Sec.MOVIES); focusContent() }
            opts += "🎞 Ouvrir dans Séries" to { Prefs.setTabSource("series", s.id); show(Sec.SERIES); focusContent() }
        }
        opts += "⟳ Actualiser chaînes et guide TV" to { refreshSources(listOf(s)) }
        opts += "✎ Modifier (serveur, identifiants, MAC, nom)" to {
            if (s.type == SourceType.YOUTUBE) startActivity(Intent(this, YoutubeActivity::class.java)) else SourcePicker.edit(this, s.id)
        }
        val inAll = s.id !in Prefs.hiddenSources
        opts += (if (inAll) "Exclure de « Toutes les sources »" else "Inclure dans « Toutes les sources »") to {
            Prefs.hiddenSources = if (inAll) Prefs.hiddenSources + s.id else Prefs.hiddenSources - s.id
            invalidate()
        }
        opts += "🗂 Gérer les catégories et chaînes" to { mgrSource = s.id; mgrGroup = null; show(Sec.LISTS); focusContent() }
        if (s.type == SourceType.XTREAM || s.type == SourceType.STALKER || s.type == SourceType.M3U) {
            val liveOnly = s.id in Prefs.liveOnlySources
            opts += (if (liveOnly) "🎬 Charger aussi les films et séries" else "📺 Chaînes seulement (sans films ni séries)") to {
                Prefs.liveOnlySources = if (liveOnly) Prefs.liveOnlySources - s.id else Prefs.liveOnlySources + s.id
                if (!liveOnly) { Repo.clearList(s.id); dropCache(s.id) }
                refreshSources(listOf(s))
            }
        }
        opts += "🧹 Vider la liste enregistrée" to {
            confirm("Vider la liste enregistrée de « ${s.name} » ? Elle sera rechargée à la prochaine actualisation.") {
                Repo.clearList(s.id); dropCache(s.id); invalidate()
            }
        }
        if (s.type == SourceType.STALKER) opts += "🩺 Infos du compte et diagnostic" to { diagnostic(s) }
        opts += "🗑 Supprimer la source" to {
            confirm("Supprimer « ${s.name} » et sa liste enregistrée ?") {
                Repo.removeSource(s.id); Prefs.hiddenSources = Prefs.hiddenSources - s.id; dropCache(s.id); invalidate()
            }
        }
        menu(s.name, opts)
    }

    /** Crée (ou réactive) une rangée sur l'accueil Google TV et demande au téléviseur de l'afficher. */
    private fun homeRow(kind: String, arg: String, name: String) {
        Ui.toast(this, "Création de la rangée « $name »…")
        lifecycleScope.launch {
            val id = withContext(Dispatchers.IO) {
                val ctx = this@TvHomeActivity
                val existing = com.elarbi.omniiptv.home.HomeRows.rows(ctx).firstOrNull { it.kind == kind && it.arg == arg }
                val r = existing?.copy(enabled = true)?.also { com.elarbi.omniiptv.home.HomeRows.update(ctx, it) }
                    ?: com.elarbi.omniiptv.home.HomeRows.add(ctx, kind, arg, name)
                try { com.elarbi.omniiptv.home.HomeScreen.sync(ctx) } catch (_: Throwable) {}
                com.elarbi.omniiptv.home.HomeScreen.ensureChannel(ctx, r.id, r.name, false)
            }
            if (id == null) { Ui.toast(this@TvHomeActivity, "Cet appareil ne gère pas les rangées d'accueil."); return@launch }
            try {
                startActivity(Intent(androidx.tvprovider.media.tv.TvContractCompat.ACTION_REQUEST_CHANNEL_BROWSABLE)
                    .putExtra(androidx.tvprovider.media.tv.TvContractCompat.EXTRA_CHANNEL_ID, id))
            } catch (e: Exception) {
                Ui.toast(this@TvHomeActivity, "Rangée créée : ajoutez-la depuis l'accueil (Personnaliser les chaînes > Omni IPTV). " +
                    "Réglages > Écran d'accueil du TV pour la personnaliser.")
            }
        }
    }

    private fun addSourceDialog() {
        val opts = listOf<Pair<String, () -> Unit>>(
            "📡 Portail MAC (Stalker / Ministra)" to { addSource(SourceType.STALKER) },
            "🔑 Compte Xtream Codes" to { addSource(SourceType.XTREAM) },
            "📄 Liste M3U / M3U8 (adresse ou fichier)" to { addSource(SourceType.M3U) },
            "▶ Chaînes YouTube Live" to { startActivity(Intent(this, YoutubeActivity::class.java)) },
            "📺 Chaînes du téléviseur (TNT, satellite, câble)" to {
                AppActions.run(this, MainFragment.A_TUNER) { tunerPermission.launch(TunerSource.permissions) }
            },
            "📦 Dépôts (listes en un clic)" to { startActivity(Intent(this, ReposActivity::class.java)) },
            "🌐 Listes gratuites et légales" to { startActivity(Intent(this, FreeSearchActivity::class.java)) }
        )
        menu("Ajouter une source", opts)
    }

    private fun addSource(t: SourceType) =
        startActivity(Intent(this, AddSourceActivity::class.java).putExtra("type", t.name))

    private fun refreshSources(list: List<Source>) {
        if (list.isEmpty()) return
        ProgressUi.run(this, if (list.size == 1) "Actualisation de « ${list[0].name} »" else "Actualisation de ${list.size} sources", {
            val out = StringBuilder()
            for (s in list) {
                try { Repo.refresh(s); out.append("${s.name} : ${Repo.lastDiff(s.id)}\n") }
                catch (e: Exception) { out.append("${s.name} : échec — ${e.message}\n") }
            }
            out.toString().trim()
        }) { r ->
            r.onSuccess { msg -> AlertDialog.Builder(this).setTitle("Actualisation terminée").setMessage(msg).setPositiveButton("OK", null).show() }
                .onFailure { e -> Ui.toast(this, "Échec : ${e.message}") }
            HomeSyncWorker.runNow(this)
            invalidate()
        }
    }

    private fun refreshAll(onlyShown: Boolean) {
        val l = Repo.sources.filter {
            it.type == SourceType.M3U || it.type == SourceType.XTREAM || it.type == SourceType.STALKER
        }.filter { !onlyShown || it.id !in Prefs.hiddenSources }
        if (l.isEmpty()) Ui.toast(this, "Aucune source à actualiser.") else refreshSources(l)
    }

    private fun diagnostic(s: Source) {
        Ui.toast(this, "Connexion au portail…")
        lifecycleScope.launch {
            val text = withContext(Dispatchers.IO) {
                try { com.elarbi.omniiptv.data.StalkerClient(s).diagnostic() } catch (e: Exception) { "Erreur : ${e.message}" }
            }
            AlertDialog.Builder(this@TvHomeActivity).setTitle(s.name).setMessage(text).setPositiveButton("OK", null).show()
        }
    }

    // ======================================================================
    // Lecture et menus communs
    // ======================================================================
    private fun play(ch: Channel, context: List<Channel>) {
        if (ch.isSeries) {
            startActivity(Intent(this, SeriesActivity::class.java).putExtra("key", ch.key)); return
        }
        stopPreview()
        Repo.playContext = context.filter { !it.isSeries }
        startActivity(Intent(this, PlayerActivity::class.java).putExtra("key", ch.key))
    }

    /** Menu d'un élément (appui long) : listes de favoris, à regarder, raccourci, personnalisation. */
    private fun itemMenu(ch: Channel, list: List<Channel>, favListId: String?) {
        val opts = ArrayList<Pair<String, () -> Unit>>()
        opts += (if (ch.isSeries) "▶ Ouvrir la série" else "▶ Lire") to { play(ch, list) }
        val inLists = FavLists.containing(ch.key)
        opts += "★ Listes de favoris…" + (if (inLists.isNotEmpty()) "  (${inLists.joinToString { it.name }})" else "") to { favDialog(ch) }
        if (favListId != null) {
            opts += "↑ Monter dans la liste" to { FavLists.move(favListId, ch.key, -1); invalidate() }
            opts += "↓ Descendre dans la liste" to { FavLists.move(favListId, ch.key, 1); invalidate() }
            opts += "⇈ Placer en tête" to { FavLists.move(favListId, ch.key, -10_000); invalidate() }
            opts += "Retirer de « ${FavLists.name(favListId)} »" to { FavLists.remove(favListId, ch.key); invalidate() }
        }
        if (ch.isVod) opts += (if (Library.isWatch(ch.key)) "Retirer de « À regarder »" else "Ajouter à « À regarder »") to { Library.toggleWatch(ch); invalidate() }
        if (Library.resume.containsKey(ch.key)) opts += "Retirer de « Continuer à regarder »" to { Library.finish(ch); invalidate() }
        opts += "Raccourci sur l'écran d'accueil du téléviseur" to {
            if (!com.elarbi.omniiptv.home.Shortcuts.pin(this, ch))
                Ui.toast(this, "Sur ce téléviseur, les favoris et films en cours apparaissent dans la rangée Omni IPTV de l'accueil.")
        }
        if (Repo.sources.any { it.id == ch.sourceId }) {
            opts += "✎ Renommer" to { askText("Nom affiché", ch.name) { n -> Edits.update(ch.sourceId, ch.key) { it.name = n }; invalidate(ch.sourceId) } }
            opts += "🚫 Masquer" to { Edits.update(ch.sourceId, ch.key) { it.hidden = true }; invalidate(ch.sourceId) }
            opts += "🗂 Gérer sa catégorie « ${ch.group} »" to { mgrSource = ch.sourceId; mgrGroup = ch.group; show(Sec.LISTS); focusContent() }
        }
        menu(ch.name, opts)
    }

    /** Cocher les listes de favoris qui contiennent l'élément, ou en créer une. */
    private fun favDialog(ch: Channel) {
        val lists = FavLists.lists()
        val checked = BooleanArray(lists.size) { FavLists.contains(lists[it].id, ch.key) }
        AlertDialog.Builder(this).setTitle("« ${ch.name} » dans les listes")
            .setMultiChoiceItems(lists.map { it.name }.toTypedArray(), checked) { _, w, c -> checked[w] = c }
            .setPositiveButton("Enregistrer") { _, _ ->
                lists.forEachIndexed { i, l ->
                    val has = FavLists.contains(l.id, ch.key)
                    if (checked[i] && !has) FavLists.add(l.id, listOf(ch))
                    if (!checked[i] && has) FavLists.remove(l.id, ch.key)
                }
                HomeSyncWorker.runNow(this); invalidate()
            }
            .setNeutralButton("＋ Nouvelle liste") { _, _ ->
                askText("Nouvelle liste de favoris", "") { n ->
                    val l = FavLists.create(n); FavLists.add(l.id, listOf(ch))
                    Ui.toast(this, "« ${ch.name} » ajouté à « ${l.name} »"); invalidate()
                }
            }
            .setNegativeButton("Annuler", null).show()
    }

    private fun chooseList(title: String, done: (String) -> Unit) {
        val lists = FavLists.lists()
        AlertDialog.Builder(this).setTitle(title)
            .setItems((lists.map { "★ ${it.name}" } + "＋ Nouvelle liste…").toTypedArray()) { _, w ->
                if (w == lists.size) askText("Nouvelle liste de favoris", "") { n -> done(FavLists.create(n).id); invalidate() }
                else { done(lists[w].id); invalidate() }
            }.setNegativeButton("Annuler", null).show()
    }

    // ======================================================================
    // Petits outils d'interface
    // ======================================================================
    private fun menu(title: String, opts: List<Pair<String, () -> Unit>>) {
        AlertDialog.Builder(this).setTitle(title)
            .setItems(opts.map { it.first }.toTypedArray()) { _, w -> opts[w].second() }
            .setNegativeButton("Fermer", null).show()
    }

    private fun confirm(msg: String, ok: () -> Unit) {
        AlertDialog.Builder(this).setMessage(msg)
            .setPositiveButton("Oui") { _, _ -> ok() }
            .setNegativeButton("Non", null).show()
    }

    private fun askText(title: String, initial: String, ok: (String) -> Unit) {
        val e = EditText(this).apply {
            setText(initial); setSelection(initial.length)
            inputType = InputType.TYPE_CLASS_TEXT
            setTextColor(Color.WHITE)
        }
        AlertDialog.Builder(this).setTitle(title).setView(e)
            .setPositiveButton("OK") { _, _ -> e.text.toString().trim().takeIf { it.isNotEmpty() }?.let(ok) }
            .setNegativeButton("Annuler", null).show()
    }

    /** Touche gauche : remonte d'un niveau (chaînes → catégories → barre latérale). */
    private fun leftTo(v: View, target: () -> View?, atLeftEdge: () -> Boolean = { true }) {
        v.setOnKeyListener { _, code, ev ->
            if (code == KeyEvent.KEYCODE_DPAD_LEFT && ev.action == KeyEvent.ACTION_DOWN && atLeftEdge()) {
                target()?.requestFocus(); true
            } else false
        }
    }

    private fun toRail(): View? = railItems[sec]

    private fun listView() = ListView(this).apply {
        divider = null
        dividerHeight = dp(2)
        choiceMode = ListView.CHOICE_MODE_SINGLE
        selector = ContextCompat.getDrawable(this@TvHomeActivity, R.drawable.bg_item)
        isVerticalScrollBarEnabled = true
        itemsCanFocus = false
    }

    private fun header(t: String) = text(13f, ThemeManager.accent, true).apply { text = t.uppercase(); setPadding(dp(4), 0, 0, dp(6)) }

    private fun column(title: String, list: View): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(header(title))
        addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
    }

    private fun searchField(hint: String) = EditText(this).apply {
        this.hint = hint
        isSingleLine = true
        textSize = 15f
        setTextColor(Color.WHITE)
        setHintTextColor(HINT)
        backgroundTintList = android.content.res.ColorStateList.valueOf(ThemeManager.accent)
        imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_DONE
    }

    private fun text(size: Float, color: Int, bold: Boolean) = TextView(this).apply {
        textSize = size
        setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    private fun watcher(f: () -> Unit) = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        override fun afterTextChanged(s: Editable?) = f()
    }

    private fun emptyText(tab: String) = when {
        Repo.sources.isEmpty() -> "Aucune source. Ouvrez « Sources » dans la barre de gauche pour ajouter un portail MAC, un compte Xtream ou une liste M3U."
        tab == "live" -> "Aucune chaîne pour cette sélection. Choisissez une autre source en haut, ou actualisez-la dans « Sources »."
        else -> "Aucun contenu dans cette section pour cette sélection. Les films et séries viennent des comptes Xtream et des portails MAC."
    }

    private fun fmt(n: Int) = String.format(Locale.FRANCE, "%,d", n).replace(' ', ' ').replace(' ', ' ')

    private fun dp(v: Int) = Ui.dp(this, v)

    private fun darker(c: Int): Int {
        val f = 0.55f
        return Color.rgb((Color.red(c) * f).toInt(), (Color.green(c) * f).toInt(), (Color.blue(c) * f).toInt())
    }

    companion object {
        private const val HINT = 0x99FFFFFF.toInt()
    }
}
